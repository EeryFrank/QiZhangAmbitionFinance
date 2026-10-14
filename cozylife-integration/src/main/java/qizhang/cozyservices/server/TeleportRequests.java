package qizhang.cozyservices.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import qizhang.cozyservices.QiZhangCozyServices;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit recipient consent; state and all world access remain on the server thread. */
public final class TeleportRequests {
    static final long SECOND=1_000_000_000L, REQUEST_LIFETIME=60*SECOND, COOLDOWN=10*SECOND, LOAD_TIMEOUT=15*SECOND;
    private static final int TICKET_RADIUS=1;
    private static final class ChunkTickets {
        // Distinct from HomeService's 641-tick type, including vanilla TicketType value equality.
        // Not persisted; bounded expiry also protects against a failure inside the removal API.
        static final TicketType TYPE=new TicketType(642,TicketType.FLAG_LOADING);
    }
    private record TicketKey(ServerLevel level,ChunkPos center) {}

    /** Pure request state. A player participates in at most one request, including accepted loads. */
    static final class Entry {
        final UUID sender,recipient;
        final long token;
        long deadline;
        boolean loading;
        Entry(UUID sender,UUID recipient,long token,long deadline){this.sender=sender;this.recipient=recipient;this.token=token;this.deadline=deadline;}
    }
    static final class State {
        final Map<UUID,Entry> participants=new HashMap<>();
        final Map<UUID,Long> cooldowns=new HashMap<>();
        long sequence;
        Entry add(UUID sender,UUID recipient,long now) {
            if(sender==null||recipient==null)throw new IllegalArgumentException("玩家身份无效");
            if(sender.equals(recipient))throw new IllegalArgumentException("不能向自己申请传送");
            if(participants.containsKey(sender))throw new IllegalArgumentException("你已有传送申请，请先处理或等待它结束");
            if(participants.containsKey(recipient))throw new IllegalArgumentException("对方正在处理另一笔传送申请，请稍后再试");
            long remaining=seconds(cooldowns.getOrDefault(sender,now)-now);
            if(remaining>0)throw new IllegalArgumentException("发送冷却中，请在 "+remaining+" 秒后再试");
            Entry e=new Entry(sender,recipient,++sequence,now+REQUEST_LIFETIME);
            participants.put(sender,e);participants.put(recipient,e);cooldowns.put(sender,now+COOLDOWN);return e;
        }
        Entry incoming(UUID recipient){Entry e=participants.get(recipient);return e!=null&&e.recipient.equals(recipient)?e:null;}
        Entry outgoing(UUID sender){Entry e=participants.get(sender);return e!=null&&e.sender.equals(sender)?e:null;}
        Entry respond(UUID recipient,boolean accept,long now) {
            Entry e=incoming(recipient);
            if(e==null)throw new IllegalArgumentException("你没有待处理的传送申请");
            if(now>=e.deadline){remove(e);throw new IllegalArgumentException("这笔传送申请已失效，请对方重新申请");}
            if(e.loading)throw new IllegalArgumentException("这笔申请已同意，正在准备安全落点");
            if(accept){e.loading=true;e.deadline=now+LOAD_TIMEOUT;}else remove(e);
            return e;
        }
        void remove(Entry e){participants.remove(e.sender,e);participants.remove(e.recipient,e);}
        List<Entry> expired(long now) {
            List<Entry> result=new ArrayList<>();
            for(Entry e:List.copyOf(participants.values()))if(now>=e.deadline&&participants.get(e.sender)==e){remove(e);result.add(e);}
            cooldowns.entrySet().removeIf(e->now>=e.getValue());return result;
        }
        void clear(){participants.clear();cooldowns.clear();}
    }

    private static final class Transfer {
        final Entry entry;
        final ServerPlayer sender,recipient;
        final TravelFees.Quote fee;final String paymentId=UUID.randomUUID().toString();
        ServerLevel target;
        int chunkX,chunkZ;
        TicketKey ticket;
        Transfer(Entry entry,ServerPlayer sender,ServerPlayer recipient,TravelFees.Quote fee){this.entry=entry;this.sender=sender;this.recipient=recipient;this.fee=fee;}
    }
    private final MinecraftServer server;
    private final State state=new State();
    private final Map<Long,Transfer> transfers=new HashMap<>();
    private final Map<TicketKey,Integer> ticketReferences=new HashMap<>();
    private final Set<TicketKey> releaseRetries=new HashSet<>();
    private int retryTicks;
    private boolean closed;

    public TeleportRequests(MinecraftServer server){this.server=java.util.Objects.requireNonNull(server);}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tpa")
            .executes(c->{c.getSource().getPlayerOrException();c.getSource().sendFailure(Component.literal("用法：/tpa 在线玩家名；对方 /yes 同意后才会传送"));return 0;})
            .then(Commands.argument("player",StringArgumentType.word())
                .suggests((c,b)->SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(),b))
                .executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();TeleportRequests s=QiZhangCozyServices.teleports();
                    p.sendSystemMessage(Component.literal(s==null?"传送系统尚未就绪":s.request(p,StringArgumentType.getString(c,"player"))));return s==null?0:1;})));
        for(String command:List.of("yes","no"))event.getDispatcher().register(Commands.literal(command).executes(c->{
            ServerPlayer p=c.getSource().getPlayerOrException();TeleportRequests s=QiZhangCozyServices.teleports();
            p.sendSystemMessage(Component.literal(s==null?"传送系统尚未就绪":s.respond(p,"yes".equals(command))));return s==null?0:1;
        }));
    }

    public String request(ServerPlayer requester,String targetUUIDorName) {
        thread();if(closed)return "传送系统已关闭";reap();
        if(!eligible(requester))return "当前状态不能申请传送，请确认存活并离开载具或床";
        ServerPlayer recipient=resolve(targetUUIDorName);
        if(recipient==null||!live(recipient))return "找不到这位在线玩家，请刷新在线玩家列表";
        if(!eligible(recipient))return "对方当前状态不能接受传送，请稍后再试";
        try {
            TravelFees.Quote fee=TravelFees.forServer(server).quote(requester,recipient.level(),recipient.position());
            TravelFees.economy().checkTeleportFunds(requester,fee.fee());
            Entry e=state.add(requester.getUUID(),recipient.getUUID(),System.nanoTime());
            transfers.put(e.token,new Transfer(e,requester,recipient,fee));
            tell(recipient,name(requester)+" 申请传送到你身边，由申请者支付 "+fee.fee()+" 余额。输入 /yes 同意、/no 拒绝，或打开出行菜单处理；60 秒后失效。");
            return "已向 "+name(recipient)+" 发送传送申请，本次锁定 "+fee.fee()+" 余额，实际传送前由你支付，传送失败退款；申请被拒绝、超时或在传送前取消不扣费（60 秒有效）";
        } catch(IOException|RuntimeException e){return TravelFees.error(e);}
    }

    public String respond(ServerPlayer recipient,boolean accept) {
        thread();if(closed)return "传送系统已关闭";reap();
        if(!eligible(recipient))return "当前状态不能处理传送，请确认存活并离开载具或床";
        Entry e=state.incoming(recipient.getUUID());
        Transfer transfer=e==null?null:transfers.get(e.token);
        if(transfer==null||transfer.recipient!=recipient)return "你没有待处理的传送申请";
        if(!eligible(transfer.sender)){finish(transfer,"传送申请已取消：申请人离线或状态发生变化");return "申请人当前不可传送，申请已取消";}
        try {
            if(accept&&!TravelFees.forServer(server).sameChargeBand(transfer.fee,transfer.sender,recipient.level(),recipient.position())){
                finish(transfer,"传送距离或维度已改变，本次报价作废，未扣费；请重新申请");return "传送报价已变化，原申请已取消，请对方重新申请";
            }
            state.respond(recipient.getUUID(),accept,System.nanoTime());
        }
        catch(IllegalArgumentException ex){
            // A deadline may elapse between reap() and respond(); drop the matching runtime object too.
            if(state.incoming(recipient.getUUID())!=e)remove(transfer);
            return ex.getMessage();
        }
        if(!accept){remove(transfer);tell(transfer.sender,name(recipient)+" 已拒绝你的传送申请");return "已拒绝 "+name(transfer.sender)+" 的传送申请";}
        try {
            transfer.target=recipient.level();BlockPos anchor=recipient.blockPosition();transfer.chunkX=anchor.getX()>>4;transfer.chunkZ=anchor.getZ()>>4;
            acquireTicket(transfer);
            tell(transfer.sender,name(recipient)+" 已同意，正在准备附近安全落点…");
            return "已同意 "+name(transfer.sender)+" 的申请；安全落点就绪后立即传送";
        } catch(RuntimeException ex){finish(transfer,"传送未完成：目标区块准备失败，请稍后重新申请");log(transfer,ex);return "目标区块准备失败，本次未传送";}
    }

    public JsonObject snapshot(ServerPlayer player) {
        thread();if(!closed)reap();JsonObject result=new JsonObject();long now=System.nanoTime();
        try{result.addProperty("fee_formula",TravelFees.forServer(server).formula());}catch(RuntimeException ex){result.addProperty("fee_error",TravelFees.error(ex));}
        result.add("incoming",JsonNull.INSTANCE);result.add("outgoing",JsonNull.INSTANCE);
        result.addProperty("cooldown_seconds",seconds(state.cooldowns.getOrDefault(player.getUUID(),now)-now));
        Entry e=state.participants.get(player.getUUID());Transfer transfer=e==null?null:transfers.get(e.token);
        if(transfer!=null) {
            boolean incoming=e.recipient.equals(player.getUUID());ServerPlayer other=incoming?transfer.sender:transfer.recipient;
            JsonObject detail=new JsonObject();detail.addProperty("uuid",other.getUUID().toString());detail.addProperty("name",name(other));
            detail.addProperty("expires_seconds",seconds(e.deadline-now));detail.addProperty("loading",e.loading);
            detail.addProperty("fee",transfer.fee.fee());detail.addProperty("payer_uuid",transfer.sender.getUUID().toString());detail.addProperty("payer_name",name(transfer.sender));
            result.add(incoming?"incoming":"outgoing",detail);
        }
        JsonArray players=new JsonArray();
        if(!closed&&live(player))for(ServerPlayer online:server.getPlayerList().getPlayers())if(online!=player&&eligible(online)) {
            JsonObject row=new JsonObject();row.addProperty("uuid",online.getUUID().toString());row.addProperty("name",name(online));
            try{row.addProperty("fee",TravelFees.forServer(server).quote(player,online.level(),online.position()).fee());}catch(RuntimeException ex){row.addProperty("fee_error",TravelFees.error(ex));}
            row.addProperty("busy",state.participants.containsKey(online.getUUID()));players.add(row);
        }
        result.add("players",players);return result;
    }

    public void tick() {
        thread();if(closed)return;reap();
        if(++retryTicks>=20){retryTicks=0;retryReleases();}
        for(Transfer t:List.copyOf(transfers.values())) {
            try {
                if(!t.entry.loading)continue;
                if(t.target!=t.recipient.level()||t.chunkX!=(t.recipient.blockPosition().getX()>>4)||t.chunkZ!=(t.recipient.blockPosition().getZ()>>4)) {
                    finish(t,"传送已取消：接收者更换了区块或维度，请重新申请");continue;
                }
                // getChunkNow is a non-loading lookup. getChunkFuture is deliberately not used:
                // the installed Youer implementation may managedBlock even though it returns a future.
                if(!chunksReady(t))continue;
                if(!TravelFees.forServer(server).sameChargeBand(t.fee,t.sender,t.recipient.level(),t.recipient.position())){finish(t,"传送距离或维度已改变，锁定报价已作废，未扣费；请重新申请");continue;}
                Vec3 destination=findSafe(t);
                if(destination==null){finish(t,"传送已取消：对方附近没有安全、空闲且有地面支撑的落点");continue;}
                // Consume consent before invoking teleport hooks; no retry or second teleport is possible.
                consume(t);
                try {
                    if(TravelFees.economy().paidTeleport(t.sender,t.fee.fee(),"tpa:"+t.paymentId,()->{
                        boolean moved=t.sender.teleportTo(t.target,destination.x,destination.y,destination.z,Set.of(),t.sender.getYRot(),t.sender.getXRot(),true);
                        if(moved){t.sender.resetFallDistance();t.sender.setDeltaMovement(Vec3.ZERO);}return moved;
                    })) {
                        tell(t.sender,"已传送到 "+name(t.recipient)+" 附近，已支付 "+t.fee.fee()+" 余额");tell(t.recipient,name(t.sender)+" 已传送到你附近，费用由申请者支付");
                    } else {tell(t.sender,"传送未完成，预扣费用已退回，可能被其他服务器规则阻止；请重新申请");tell(t.recipient,"本次传送未完成，申请者的预扣费用已退回，原申请已结束");}
                } finally {releaseTicket(t);}
            } catch(IOException|RuntimeException ex){finish(t,TravelFees.error(ex));log(t,ex);}
        }
    }

    public void logout(ServerPlayer player) {
        thread();Entry e=state.participants.get(player.getUUID());Transfer t=e==null?null:transfers.get(e.token);
        if(t!=null&&(t.sender==player||t.recipient==player))finish(t,"传送申请已取消：一方已退出服务器");
    }
    public void close(){
        thread();closed=true;
        for(Transfer t:List.copyOf(transfers.values()))remove(t);
        retryReleases();transfers.clear();state.clear();
        if(!ticketReferences.isEmpty()||!releaseRetries.isEmpty())org.slf4j.LoggerFactory.getLogger("QiZhangEconomy").warn("TPA ticket cleanup incomplete on close; nonpersistent 642-tick tickets will expire: active={}, retry={}",ticketReferences.size(),releaseRetries.size());
    }

    private void reap() {
        for(Entry e:state.expired(System.nanoTime())){Transfer t=transfers.get(e.token);if(t!=null)finish(t,e.loading?"传送已取消：安全落点加载超过 15 秒，请重新申请":"传送申请已超过 60 秒，自动失效");}
        for(Transfer t:List.copyOf(transfers.values()))try {
            if(!eligible(t.sender)||!eligible(t.recipient))finish(t,"传送申请已取消：一方离线、死亡或状态发生变化");
        } catch(RuntimeException ex){finish(t,"传送申请已取消：玩家状态读取失败");log(t,ex);}
    }
    private ServerPlayer resolve(String input) {
        if(input==null||input.length()>64)return null;
        if(input.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return server.getPlayerList().getPlayer(UUID.fromString(input));
        if(!input.matches("[A-Za-z0-9_]{1,16}"))return null;
        for(ServerPlayer p:server.getPlayerList().getPlayers())if(name(p).equalsIgnoreCase(input))return p;return null;
    }
    private boolean live(ServerPlayer p){return p!=null&&!p.hasDisconnected()&&server.getPlayerList().getPlayer(p.getUUID())==p;}
    private boolean eligible(ServerPlayer p){return live(p)&&p.isAlive()&&!p.isSpectator()&&!p.isSleeping()&&!p.isPassenger()&&!p.isChangingDimension();}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("Teleport requests must run on the server thread");}
    private void consume(Transfer t){state.remove(t.entry);transfers.remove(t.entry.token,t);}
    private void remove(Transfer t){consume(t);releaseTicket(t);}
    private void finish(Transfer t,String message){remove(t);notifyBoth(t,message);}
    private void notifyBoth(Transfer t,String message){tell(t.sender,message);tell(t.recipient,message);}
    private void tell(ServerPlayer p,String message){
        try{if(live(p))p.sendSystemMessage(Component.literal(message));}
        catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger("QiZhangEconomy").warn("Could not send TPA notification",ex);}
    }
    private static String name(ServerPlayer player){return player.getName().getString();}
    static long seconds(long nanos){return nanos<=0?0:1+(nanos-1)/SECOND;}
    private static void log(Transfer t,Exception ex){org.slf4j.LoggerFactory.getLogger("QiZhangEconomy").error("传送申请处理失败：{} -> {}",t.entry.sender,t.entry.recipient,ex);}

    private void acquireTicket(Transfer t) {
        TicketKey key=new TicketKey(t.target,new ChunkPos(t.chunkX,t.chunkZ));
        // Record ownership before the API call so a partially failing add is still cleaned up.
        t.ticket=key;ticketReferences.merge(key,1,Integer::sum);releaseRetries.remove(key);
        // The exact client and Youer bytecode only adds a loading ticket here; neither waits for chunks.
        // Refresh on every acquisition so a newer request cannot inherit an almost-expired shared ticket.
        key.level.getChunkSource().addTicketWithRadius(ChunkTickets.TYPE,key.center,TICKET_RADIUS);
    }
    private void releaseTicket(Transfer t) {
        TicketKey key=t.ticket;if(key==null)return;t.ticket=null;
        int remaining=ticketReferences.getOrDefault(key,1)-1;
        if(remaining>0){ticketReferences.put(key,remaining);return;}
        ticketReferences.remove(key);
        try {key.level.getChunkSource().removeTicketWithRadius(ChunkTickets.TYPE,key.center,TICKET_RADIUS);releaseRetries.remove(key);}
        catch(RuntimeException ex){releaseRetries.add(key);log(t,ex);}
    }
    private void retryReleases() {
        for(TicketKey key:List.copyOf(releaseRetries)) {
            if(ticketReferences.containsKey(key)){releaseRetries.remove(key);continue;}
            try {key.level.getChunkSource().removeTicketWithRadius(ChunkTickets.TYPE,key.center,TICKET_RADIUS);releaseRetries.remove(key);}
            catch(RuntimeException ignored){/* Retry next second; the type has a bounded nonpersistent expiry. */}
        }
    }
    private boolean chunksReady(Transfer t) {
        if(t.ticket==null||t.target==null)return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(t.target.getChunkSource().getChunkNow(t.chunkX+dx,t.chunkZ+dz)==null)return false;
        return true;
    }

    private Vec3 findSafe(Transfer t) {
        Vec3 target=t.recipient.position();BlockPos anchor=BlockPos.containing(target);
        if(!Double.isFinite(target.x)||!Double.isFinite(target.y)||!Double.isFinite(target.z))return null;
        // Search nearby rings and the recipient's current height +/- 2, including half blocks.
        for(int radius=0;radius<=2;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
            double x=anchor.getX()+dx+.5,z=anchor.getZ()+dz+.5;
            for(int dy:new int[]{0,1,-1,2,-2})for(double y:new double[]{target.y+dy,anchor.getY()+dy,anchor.getY()+dy+.5}) {
                if(Math.abs(y-target.y)>2.00001)continue;
                Vec3 candidate=new Vec3(x,y,z);
                if(safe(t.target,t.sender,candidate))return candidate;
            }
        }
        return null;
    }
    private static boolean safe(ServerLevel level,ServerPlayer player,Vec3 position) {
        double half=player.getBbWidth()/2.;AABB body=new AABB(position.x-half,position.y+.001,position.z-half,position.x+half,position.y+player.getBbHeight(),position.z+half);
        if(position.y<level.getMinY()+1||body.maxY>level.getMaxY()||!level.getWorldBorder().isWithinBounds(body))return false;
        // Scan the full body and its floor, including a small contact margin for cactus/fire edges.
        AABB scan=new AABB(body.minX-.1,position.y-.06,body.minZ-.1,body.maxX+.1,body.maxY,body.maxZ+.1);
        for(BlockPos point:BlockPos.betweenClosed(BlockPos.containing(scan.minX,scan.minY,scan.minZ),BlockPos.containing(scan.maxX,scan.maxY,scan.maxZ))) {
            if(level.getChunkSource().getChunkNow(point.getX()>>4,point.getZ()>>4)==null)return false;
            BlockState block=level.getBlockState(point);
            if(!block.getFluidState().isEmpty()||block.is(Blocks.FIRE)||block.is(Blocks.SOUL_FIRE)||block.is(Blocks.MAGMA_BLOCK)||block.is(Blocks.CACTUS)
                ||block.is(Blocks.CAMPFIRE)||block.is(Blocks.SOUL_CAMPFIRE)||block.is(Blocks.POWDER_SNOW)||block.is(Blocks.SWEET_BERRY_BUSH)
                ||block.is(Blocks.WITHER_ROSE)||block.is(Blocks.POINTED_DRIPSTONE)||block.is(Blocks.NETHER_PORTAL)||block.is(Blocks.END_PORTAL)||block.is(Blocks.END_GATEWAY))return false;
        }
        AABB support=new AABB(body.minX+.02,position.y-.06,body.minZ+.02,body.maxX-.02,position.y+.001,body.maxZ-.02);
        return level.findSupportingBlock(player,support).isPresent()&&level.noCollision(player,body);
    }
}

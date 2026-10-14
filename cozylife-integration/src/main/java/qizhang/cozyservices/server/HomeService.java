package qizhang.cozyservices.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One durable home per UUID; successful travel settles a server-quoted balance fee. */
public final class HomeService {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("QiZhangEconomy");
    // Different from TPA's timeout 642: the client API compares TicketType record fields by value.
    private static final TicketType HOME_TICKET=new TicketType(641,TicketType.FLAG_LOADING);
    private static final int LOAD_RADIUS=1; // FULL level 33 - 1 loads the surrounding 3 by 3 chunks.
    private static final long WARMUP=3_000_000_000L,TIMEOUT=30_000_000_000L,OVERLAY_INTERVAL=200_000_000L;
    private record Home(String dimension,double x,double y,double z,float yaw,float pitch) {}
    private record TicketKey(ServerLevel level,ChunkPos center) {}
    private static final class Pending {
        final ServerPlayer player;final Home home;final ServerLevel target;final Vec3 origin;
        final String originDimension;final float health;final long started;final TicketKey ticket;final TravelFees.Quote fee;final String paymentId=UUID.randomUUID().toString();
        long lastOverlay;boolean overlaySent;
        Pending(ServerPlayer player,Home home,ServerLevel target,long now,TicketKey ticket,TravelFees.Quote fee){
            this.player=player;this.home=home;this.target=target;this.origin=player.position();
            this.originDimension=player.level().dimension().identifier().toString();this.health=player.getHealth();
            this.started=now;this.ticket=ticket;this.fee=fee;
        }
    }
    private final MinecraftServer server;
    private final Path directory;
    private final Map<UUID,Home> homes=new HashMap<>();
    private final Set<UUID> loaded=new java.util.HashSet<>();
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<UUID,Long> cooldown=new HashMap<>();
    private final HomeLoadHolds<TicketKey> tickets=new HomeLoadHolds<>(
        key->key.level.getChunkSource().addTicketWithRadius(HOME_TICKET,key.center,LOAD_RADIUS),
        key->key.level.getChunkSource().removeTicketWithRadius(HOME_TICKET,key.center,LOAD_RADIUS));
    private boolean closed;
    public HomeService(MinecraftServer server) {
        this.server=server;directory=server.getWorldPath(LevelResource.ROOT).resolve("qizhang-homes");
    }
    public JsonObject snapshot(ServerPlayer player) {
        JsonObject data=new JsonObject();
        try {
            Home home=load(player.getUUID());data.addProperty("set",home!=null);
            if(home!=null){data.addProperty("dimension",home.dimension);data.addProperty("x",home.x);data.addProperty("y",home.y);data.addProperty("z",home.z);
                try{TravelFees fees=TravelFees.forServer(server);Pending task=pending.get(player.getUUID());ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(home.dimension)));
                    data.addProperty("fee",task==null?fees.quote(player,level,new Vec3(home.x,home.y,home.z)).fee():task.fee.fee());data.addProperty("fee_formula",fees.formula());}
                catch(RuntimeException e){data.addProperty("fee_error",TravelFees.error(e));}
            }
        } catch(IOException|RuntimeException e) {data.addProperty("set",false);data.addProperty("error","家的位置记录无法读取，请联系管理员");}
        data.addProperty("pending",pending.containsKey(player.getUUID()));
        Long until=cooldown.get(player.getUUID());
        data.addProperty("cooldown_seconds",until==null?0L:Math.max(0L,(until-System.nanoTime()+999_999_999L)/1_000_000_000L));
        return data;
    }
    public String action(ServerPlayer player,String action) {
        if(!server.isSameThread())throw new IllegalStateException("Home action must run on server thread");
        if(closed)return "家的服务正在关闭，请稍后再试";
        if(player.hasDisconnected()||server.getPlayerList().getPlayer(player.getUUID())!=player||!player.isAlive()||player.isSpectator())return "当前状态不能设置家或传送";
        UUID id=player.getUUID();
        try {
            if("home_set".equals(action)) {
                if(player.isPassenger()||!safe(player.level(),player,player.position()))return "请站在安全的地面上，再设置家的位置";
                Home home=new Home(player.level().dimension().identifier().toString(),player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
                JsonObject data=json(home);data.addProperty("schema",1);data.addProperty("uuid",id.toString());
                LocalStateFiles.atomicWrite(directory.resolve(id+".json"),data+"\n");
                homes.put(id,home);loaded.add(id);cancel(id,false);return "家的位置已更新，之后将回到本次设置的位置";
            }
            if(!"home_go".equals(action))return "不支持的家的操作";
            Home home=load(id);if(home==null)return "请先设置家的位置";
            if(pending.containsKey(id))return "正在准备回家，请保持原地";
            long now=System.nanoTime();Long until=cooldown.get(id);
            if(until!=null&&until-now>0)return "回家冷却中，请在 "+((until-now+999_999_999L)/1_000_000_000L)+" 秒后再试";
            if(player.isPassenger()||player.isSleeping()||player.hurtTime>0)return "请离开载具或床，并在未受伤时再传送";
            ServerLevel target=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(home.dimension)));
            if(target==null)return "家的维度暂不可用，请重新设置家";
            BlockPos pos=BlockPos.containing(home.x,home.y,home.z);
            if(!target.getWorldBorder().isWithinBounds(pos))return "家的位置已在世界边界外，请重新设置";
            TicketKey key=new TicketKey(target,new ChunkPos(pos.getX()>>4,pos.getZ()>>4));
            TravelFees.Quote fee=TravelFees.forServer(server).quote(player,target,new Vec3(home.x,home.y,home.z));
            TravelFees.economy().checkTeleportFunds(player,fee.fee());
            Pending task=new Pending(player,home,target,now,key,fee);
            tickets.acquire(key);
            try {pending.put(id,task);cooldown.put(id,now+TIMEOUT);}
            catch(RuntimeException e){pending.remove(id);cooldown.remove(id);release(task);throw e;}
            return "本次回家费用 "+fee.fee()+" 余额，实际传送前扣款，传送失败退款。3 秒后传送，请保持原地；倒计时中移动或受伤取消不收费";
        } catch(IOException|RuntimeException e) {
            if(!(e instanceof IllegalArgumentException))LOGGER.error("家的记录或传送准备失败，玩家 {}",id,e);
            return TravelFees.error(e);
        }
    }
    public void tick() {
        if(!server.isSameThread())throw new IllegalStateException("Home tick must run on server thread");
        if(closed)return;
        for(UUID id:java.util.List.copyOf(pending.keySet())) {
            Pending task=pending.get(id);if(task==null)continue;
            try {tickOne(id,task);}
            catch(IOException|RuntimeException e) {
                cancel(id,true);LOGGER.error("回家任务失败，玩家 {}；其他任务继续",id,e);
                try{task.player.sendSystemMessage(Component.literal(TravelFees.error(e)));}
                catch(RuntimeException notification){LOGGER.warn("无法发送回家失败通知，玩家 {}",id,notification);}
            }
        }
    }
    private void tickOne(UUID id,Pending task)throws IOException {
            ServerPlayer p=task.player;
            if(p.hasDisconnected()||server.getPlayerList().getPlayer(id)!=p){cancel(id,false);return;}
            if(server.getLevel(task.target.dimension())!=task.target){cancel(id,true);p.sendSystemMessage(Component.literal("家的维度已卸载，请稍后再试"));return;}
            if(!p.isAlive()||p.isSpectator()||p.isPassenger()||p.isSleeping()||p.hurtTime>0||p.getHealth()<task.health
                ||p.position().distanceToSqr(task.origin)>.04||!p.level().dimension().identifier().toString().equals(task.originDimension)) {
                cancel(id,true);p.sendSystemMessage(Component.literal("已取消回家，未扣费：移动、受伤或状态发生变化"),true);return;
            }
            long now=System.nanoTime(),elapsed=now-task.started;
            if(elapsed>=TIMEOUT){cancel(id,true);p.sendSystemMessage(Component.literal("回家区块加载超时，未传送、未扣费；请稍后再试"));return;}
            if(elapsed<WARMUP) {
                overlay(task,now,"回家倒计时 "+((WARMUP-elapsed+999_999_999L)/1_000_000_000L)+" 秒 · 移动或受伤取消");return;
            }
            if(!loaded(task.ticket)){overlay(task,now,"正在准备家的区块，请保持原地…");return;}
            // Keep the ticket through collision checks and teleport callbacks; every terminal path releases it.
            pending.remove(id,task);
            try {
            Vec3 position=new Vec3(task.home.x,task.home.y,task.home.z);
            if(!safe(task.target,p,position)) {
                cooldown.remove(id);p.sendSystemMessage(Component.literal("家的落点已被阻挡或不安全，未传送、未扣费；请重新设置家"));return;
            }
            if(TravelFees.economy().paidTeleport(p,task.fee.fee(),"home:"+task.paymentId,()->{
                boolean moved=p.teleportTo(task.target,position.x,position.y,position.z,Set.of(),task.home.yaw,task.home.pitch,true);
                if(moved){p.resetFallDistance();p.setDeltaMovement(Vec3.ZERO);}return moved;
            })) {
                p.sendSystemMessage(Component.literal("欢迎回家，已支付 "+task.fee.fee()+" 余额"),true);
            } else {cooldown.remove(id);p.sendSystemMessage(Component.literal("本次回家传送未完成，预扣费用已退回；请稍后再试"));}
            } finally {release(task);}
    }
    private static boolean loaded(TicketKey key) {
        for(int dx=-LOAD_RADIUS;dx<=LOAD_RADIUS;dx++)for(int dz=-LOAD_RADIUS;dz<=LOAD_RADIUS;dz++)
            if(key.level.getChunkSource().getChunkNow(key.center.x()+dx,key.center.z()+dz)==null)return false;
        return true;
    }
    private static void overlay(Pending task,long now,String message) {
        if(task.overlaySent&&now-task.lastOverlay<OVERLAY_INTERVAL)return;
        task.lastOverlay=now;task.overlaySent=true;task.player.sendSystemMessage(Component.literal(message),true);
    }
    public void logout(ServerPlayer player){Pending task=pending.get(player.getUUID());if(task!=null&&task.player==player)cancel(player.getUUID(),false);}
    public void close(){closed=true;for(UUID id:java.util.List.copyOf(pending.keySet()))cancel(id,false);homes.clear();loaded.clear();cooldown.clear();}
    private void cancel(UUID id,boolean resetCooldown){Pending task=pending.remove(id);if(resetCooldown)cooldown.remove(id);if(task!=null)release(task);}
    private void release(Pending task){try{tickets.release(task.ticket);}catch(RuntimeException e){LOGGER.warn("无法释放回家区块票，有限超时仍会清理，玩家 {}",task.player.getUUID(),e);}}
    private Home load(UUID id)throws IOException {
        if(loaded.contains(id))return homes.get(id);
        Path path=directory.resolve(id+".json");
        if(Files.exists(path)) {
            if(Files.size(path)>8192)throw new IOException("Home record exceeds limit");
            JsonObject data=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(data.get("schema").getAsInt()!=1||!id.toString().equals(data.get("uuid").getAsString()))throw new IOException("Home identity mismatch");
            Home home=new Home(data.get("dimension").getAsString(),data.get("x").getAsDouble(),data.get("y").getAsDouble(),data.get("z").getAsDouble(),data.get("yaw").getAsFloat(),data.get("pitch").getAsFloat());
            Identifier.parse(home.dimension);
            if(!Double.isFinite(home.x)||!Double.isFinite(home.y)||!Double.isFinite(home.z)||!Float.isFinite(home.yaw)||!Float.isFinite(home.pitch)
                ||Math.abs(home.x)>30_000_000||Math.abs(home.z)>30_000_000||Math.abs(home.y)>4096)throw new IOException("Home coordinate invalid");
            homes.put(id,home);
        }
        loaded.add(id);return homes.get(id);
    }
    private static JsonObject json(Home h){JsonObject o=new JsonObject();o.addProperty("dimension",h.dimension);o.addProperty("x",h.x);o.addProperty("y",h.y);o.addProperty("z",h.z);o.addProperty("yaw",h.yaw);o.addProperty("pitch",h.pitch);return o;}
    public static boolean safe(ServerLevel level,ServerPlayer p,Vec3 pos) {
        double width=p.getBbWidth(),height=p.getBbHeight();
        if(!Double.isFinite(pos.x)||!Double.isFinite(pos.y)||!Double.isFinite(pos.z)
            ||!Double.isFinite(width)||!Double.isFinite(height)||width<.1||width>4||height<.1||height>8)return false;
        double half=width/2.;AABB body=new AABB(pos.x-half,pos.y+.001,pos.z-half,pos.x+half,pos.y+height,pos.z+half);
        if(pos.y<level.getMinY()+1||body.maxY>level.getMaxY()||!level.getWorldBorder().isWithinBounds(body))return false;
        // Collision iterators may read one block beyond the body (e.g. a neighbouring fence). Verify those chunks first.
        int minChunkX=BlockPos.containing(body.minX-1.01,pos.y,body.minZ-1.01).getX()>>4;
        int minChunkZ=BlockPos.containing(body.minX-1.01,pos.y,body.minZ-1.01).getZ()>>4;
        int maxChunkX=BlockPos.containing(body.maxX+1.01,pos.y,body.maxZ+1.01).getX()>>4;
        int maxChunkZ=BlockPos.containing(body.maxX+1.01,pos.y,body.maxZ+1.01).getZ()>>4;
        for(int x=minChunkX;x<=maxChunkX;x++)for(int z=minChunkZ;z<=maxChunkZ;z++)
            if(level.getChunkSource().getChunkNow(x,z)==null)return false;
        // Include feet, head, side contact and the supporting surface, including negative coordinates and chunk edges.
        BlockPos from=BlockPos.containing(body.minX-.1,pos.y-.06,body.minZ-.1);
        BlockPos to=BlockPos.containing(body.maxX+.1,body.maxY,body.maxZ+.1);
        for(BlockPos point:BlockPos.betweenClosed(from,to)) {
            BlockState state=level.getBlockState(point);
            if(!state.getFluidState().isEmpty()||state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)||state.is(Blocks.MAGMA_BLOCK)
                ||state.is(Blocks.CACTUS)||state.is(Blocks.CAMPFIRE)||state.is(Blocks.SOUL_CAMPFIRE)||state.is(Blocks.POWDER_SNOW)
                ||state.is(Blocks.SWEET_BERRY_BUSH)||state.is(Blocks.WITHER_ROSE)||state.is(Blocks.POINTED_DRIPSTONE)
                ||state.is(Blocks.NETHER_PORTAL)||state.is(Blocks.END_PORTAL)||state.is(Blocks.END_GATEWAY))return false;
        }
        AABB support=new AABB(body.minX+.02,pos.y-.06,body.minZ+.02,body.maxX-.02,pos.y+.001,body.maxZ-.02);
        return level.findSupportingBlock(p,support).isPresent()&&level.noCollision(p,body);
    }
}

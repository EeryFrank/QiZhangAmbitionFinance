package qizhang.cozyservices.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneTeleportContext;
import net.blay09.mods.waystones.api.event.BuildWaystoneSelectionMenuEvent;
import net.blay09.mods.waystones.core.PersonalizedWaystoneImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Waystones menu-only price labels plus charging around the real successful entity teleport. */
public final class WaystonesTravelFees {
    private record MenuQuotes(ServerPlayer player,long expires,Map<UUID,TravelFees.Quote> quotes,Set<UUID> destinations) {}
    private static final Map<UUID,MenuQuotes> MENUS=new HashMap<>();
    private static final Map<UUID,Long> LAST_NOTICE=new HashMap<>();
    private static boolean registered;
    private WaystonesTravelFees(){}
    public static void register(){
        if(registered)return;registered=true;
        BuildWaystoneSelectionMenuEvent.EVENT.register(Identifier.fromNamespaceAndPath("cozylife_economy","travel_fee_labels"),WaystonesTravelFees::menu);
    }
    public static void logout(ServerPlayer player){MenuQuotes q=MENUS.get(player.getUUID());if(q!=null&&q.player==player)MENUS.remove(player.getUUID());LAST_NOTICE.remove(player.getUUID());}
    public static void close(){MENUS.clear();LAST_NOTICE.clear();}
    private static void menu(BuildWaystoneSelectionMenuEvent event){
        if(!(event.getPlayer() instanceof ServerPlayer p))return;
        if(!p.level().getServer().isSameThread())throw new IllegalStateException("传送石碑菜单必须在服务器主线程构建");
        MENUS.entrySet().removeIf(e->e.getValue().expires<System.nanoTime()||e.getValue().player.hasDisconnected());
        Map<UUID,TravelFees.Quote> quotes=new HashMap<>();Set<UUID> destinations=new HashSet<>();TravelFees fees=TravelFees.forServer(p.level().getServer());
        var entries=event.getWaystones();
        for(int i=0;i<entries.size();i++) {
            var original=entries.get(i);var display=new PersonalizedWaystoneImpl(original.getBackingWaystone(),original.getAlias().map(Component::copy).orElse(null),original.getConfiguredGroups(),original.isHidden());
            destinations.add(original.getWaystoneUid());
            // The copy owns its alias; no world/waystone-store or saved personal alias is changed.
            try {
                if(i>=4096)throw new IllegalArgumentException("目的地过多，请减少同时显示的传送石碑");
                ServerLevel target=p.level().getServer().getLevel(original.getDimension());
                TravelFees.Quote quote=fees.quote(p,target,center(original));quotes.put(original.getWaystoneUid(),quote);
                display.setAlias(original.getEffectiveName().copy().append(" 〔"+quote.fee()+" 余额〕"));
            } catch(RuntimeException e){display.setAlias(original.getEffectiveName().copy().append(" 〔收费暂不可用〕"));}
            entries.set(i,display);
        }
        MENUS.put(p.getUUID(),new MenuQuotes(p,System.nanoTime()+120_000_000_000L,Map.copyOf(quotes),Set.copyOf(destinations)));
        try{notice(p,"传送石碑按余额收费，列表价签为本次报价，实际传送前扣款，传送失败退款；关闭列表不扣款。"+fees.formula(),true);}
        catch(RuntimeException e){notice(p,TravelFees.error(e),true);}
    }
    public static boolean teleport(WaystoneTeleportContext context,Entity entity,ServerLevel target,Vec3 actualDestination,BooleanSupplier original){
        if(!(entity instanceof ServerPlayer p))return original.getAsBoolean();
        if(!p.level().getServer().isSameThread())throw new IllegalStateException("传送石碑收费必须在服务器主线程执行");
        if(!p.isAlive()||p.hasDisconnected())return false;
        try {
            TravelFees fees=TravelFees.forServer(p.level().getServer());Waystone stone=context.getTargetWaystone();
            Vec3 quotedDestination=center(stone);
            // Normal landing offsets are tiny. If another mod replaces the destination, quote that actual position instead.
            if(!target.dimension().equals(stone.getDimension())||actualDestination.distanceToSqr(quotedDestination)>256)quotedDestination=actualDestination;
            MenuQuotes menu=MENUS.get(p.getUUID());TravelFees.Quote quote;
            if(menu!=null&&menu.player==p&&menu.destinations.contains(stone.getWaystoneUid())) {
                if(!menu.quotes.containsKey(stone.getWaystoneUid()))throw new IllegalArgumentException("该目的地尚无有效收费报价，未传送且未扣费；请重新打开列表或联系管理员");
                quote=menu.quotes.get(stone.getWaystoneUid());
                if(System.nanoTime()>menu.expires||!fees.sameChargeBand(quote,p,target,quotedDestination))throw new IllegalArgumentException("传送石碑报价已过期或距离改变，未传送、未扣费；请重新打开目的地列表");
            } else quote=fees.quote(p,target,quotedDestination);
            TravelFees.economy().checkTeleportFunds(p,quote.fee());
            notice(p,"本次传送石碑费用 "+quote.fee()+" 余额，即将在实际传送前扣款，传送失败退款。",false);
            if(menu!=null&&menu.player==p)MENUS.put(p.getUUID(),new MenuQuotes(p,0,menu.quotes,menu.destinations));
            boolean success=TravelFees.economy().paidTeleport(p,quote.fee(),"waystones:"+UUID.randomUUID(),()->{
                boolean moved=original.getAsBoolean();
                if(moved){p.resetFallDistance();if(!p.isFallFlying()){p.setDeltaMovement(p.getDeltaMovement().multiply(1,0,1));p.setOnGround(true);}}
                return moved;
            });
            if(success)notice(p,"传送石碑传送成功，已支付 "+quote.fee()+" 余额。",false);
            else notice(p,"传送石碑传送已取消或被规则阻止，预扣费用已退回。",false);
            return success;
        } catch(IOException|RuntimeException e) {
            boolean reported=notice(p,TravelFees.error(e),true);
            if(reported&&!(e instanceof IllegalArgumentException))org.slf4j.LoggerFactory.getLogger("QiZhangEconomy").warn("传送石碑收费未完成，玩家 {}",p.getUUID(),e);
            return false;
        }
    }
    private static boolean notice(ServerPlayer p,String text,boolean limited){
        long now=System.nanoTime();if(limited&&now-LAST_NOTICE.getOrDefault(p.getUUID(),now-2_000_000_000L)<2_000_000_000L)return false;
        if(p.hasDisconnected())return false;LAST_NOTICE.put(p.getUUID(),now);
        try{p.sendSystemMessage(Component.literal(text));return true;}catch(RuntimeException ignored){return false;}
    }
    private static Vec3 center(Waystone stone){return new Vec3(stone.getPos().getX()+.5,stone.getPos().getY(),stone.getPos().getZ()+.5);}
}

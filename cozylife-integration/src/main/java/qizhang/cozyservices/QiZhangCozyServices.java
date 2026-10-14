package qizhang.cozyservices;

import com.google.gson.JsonObject;
import cozylife.economy.network.CozyNetwork;
import cozylife.economy.network.NavigationExtension;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import qizhang.cozyservices.server.HomeService;
import qizhang.cozyservices.server.TeleportRequests;
import qizhang.cozyservices.server.TravelFees;
import qizhang.cozyservices.server.WaystonesTravelFees;

/** Optional CozyLife menu and travel integration. The finance core owns all money. */
@Mod(QiZhangCozyServices.ID)
public final class QiZhangCozyServices {
    public static final String ID = "qizhang_cozy_services";
    private static HomeService homes;
    private static TeleportRequests teleports;
    private static boolean waystones;

    public QiZhangCozyServices(IEventBus ignored) {
        waystones = ModList.get().isLoaded("waystones");
        // This branch is the only route to classes requiring the optional Waystones API.
        if (waystones) WaystonesTravelFees.register();
        CozyNetwork.registerNavigationExtension(new Navigation());
        NeoForge.EVENT_BUS.addListener(QiZhangCozyServices::starting);
        NeoForge.EVENT_BUS.addListener(QiZhangCozyServices::stopped);
        NeoForge.EVENT_BUS.addListener(QiZhangCozyServices::logout);
        NeoForge.EVENT_BUS.addListener(QiZhangCozyServices::tick);
        NeoForge.EVENT_BUS.addListener(TeleportRequests::register);
    }
    public static HomeService homes() { return homes; }
    public static TeleportRequests teleports() { return teleports; }
    private static void starting(ServerStartingEvent event) {
        if (homes != null) homes.close();
        if (teleports != null) teleports.close();
        homes = new HomeService(event.getServer());
        teleports = new TeleportRequests(event.getServer());
        TravelFees.forServer(event.getServer());
    }
    private static void stopped(ServerStoppedEvent event) {
        if (homes != null) homes.close();
        if (teleports != null) teleports.close();
        homes = null;
        teleports = null;
        if (waystones) WaystonesTravelFees.close();
        TravelFees.close(event.getServer());
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (homes != null) homes.logout(player);
            if (teleports != null) teleports.logout(player);
            if (waystones) WaystonesTravelFees.logout(player);
        }
    }
    private static void tick(ServerTickEvent.Post event) {
        if (homes != null) homes.tick();
        if (teleports != null) teleports.tick();
    }
    private static final class Navigation implements NavigationExtension {
        @Override public boolean handles(String action) {
            return switch(action) { case "home_set", "home_go", "tpa_request", "tpa_yes", "tpa_no" -> true; default -> false; };
        }
        @Override public String execute(ServerPlayer player, String action, String id) {
            if (action.startsWith("home_")) return homes == null ? "家园服务尚未就绪" : homes.action(player, action);
            if (teleports == null) return "传送服务尚未就绪";
            return switch(action) {
                case "tpa_request" -> teleports.request(player,id);
                case "tpa_yes" -> teleports.respond(player,true);
                case "tpa_no" -> teleports.respond(player,false);
                default -> "未知导航操作";
            };
        }
        @Override public Map<String, JsonObject> sections(ServerPlayer player) {
            Map<String, JsonObject> result = new LinkedHashMap<>();
            if (homes != null) result.put("home", homes.snapshot(player));
            if (teleports != null) result.put("teleport", teleports.snapshot(player));
            return result;
        }
    }
}

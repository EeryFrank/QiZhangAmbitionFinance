package cozylife.economy;

import cozylife.economy.network.CozyNetwork;
import cozylife.economy.server.CoinRewards;
import cozylife.economy.server.EconomyService;
import cozylife.economy.server.EconomyCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CozyEconomy.ID)
public final class CozyEconomy {
    public static final String ID = "cozylife_economy";
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredItem<CoinItem> COPPER = ITEMS.registerItem("copper_coin", p -> new CoinItem(p, 1L));
    public static final DeferredItem<CoinItem> SILVER = ITEMS.registerItem("silver_coin", p -> new CoinItem(p, 100L));
    public static final DeferredItem<CoinItem> GOLD = ITEMS.registerItem("gold_coin", p -> new CoinItem(p, 10000L));
    private static EconomyService service;

    public CozyEconomy(IEventBus bus) {
        ITEMS.register(bus);
        CoinRewards.register(bus);
        bus.addListener(CozyNetwork::register);
        CoinRewards.initialize(FMLPaths.CONFIGDIR.get().resolve("cozylife-economy/rewards.json"));
        NeoForge.EVENT_BUS.addListener(CoinRewards::onLootTableLoad);
        NeoForge.EVENT_BUS.addListener(CoinRewards::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(CozyEconomy::starting);
        NeoForge.EVENT_BUS.addListener(CozyEconomy::stopped);
        NeoForge.EVENT_BUS.addListener(CozyEconomy::login);
        NeoForge.EVENT_BUS.addListener(CozyEconomy::logout);
        NeoForge.EVENT_BUS.addListener(EconomyCommands::register);
        NeoForge.EVENT_BUS.addListener(CozyEconomy::tick);
    }

    public static EconomyService service() { return service; }
    private static void starting(ServerStartingEvent event) {
        if (service != null) service.close();
        CozyNetwork.clear();
        service = new EconomyService(event.getServer());
        cozylife.economy.server.FinancePermissions.of(event.getServer());
    }
    private static void stopped(ServerStoppedEvent event) {
        if (service != null) service.close();
        service = null;
        cozylife.economy.server.FinancePermissions.close(event.getServer());
        CozyNetwork.clear();
    }
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (service != null && event.getEntity() instanceof ServerPlayer player) {
            service.onLogin(player);
            cozylife.economy.server.FinancePermissions.login(player);
        }
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            cozylife.economy.server.FinancePermissions.logout(player);
            CozyNetwork.logout(player);
            if (service != null) service.onLogout(player);
        }
    }
    private static void tick(ServerTickEvent.Post event) {
        if (service != null) service.tick();
        cozylife.economy.server.FinancePermissions.tick(event.getServer());
    }
}

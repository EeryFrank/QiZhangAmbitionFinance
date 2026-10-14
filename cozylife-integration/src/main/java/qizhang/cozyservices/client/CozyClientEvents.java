package qizhang.cozyservices.client;

import com.mojang.blaze3d.platform.InputConstants;
import cozylife.economy.CozyEconomy;
import qizhang.cozyservices.QiZhangCozyServices;
import cozylife.economy.network.CozyNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** Client types live exclusively behind the physical-client event subscriber. */
@EventBusSubscriber(modid = QiZhangCozyServices.ID, value = Dist.CLIENT)
public final class CozyClientEvents {
    public static final KeyMapping MENU = new KeyMapping("key.cozylife_economy.menu", InputConstants.Type.KEYBOARD,
        InputConstants.KEY_V, KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CozyEconomy.ID, "menu")));
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(MENU); }
    @SubscribeEvent public static void payloads(RegisterClientPayloadHandlersEvent event) {
        CozyNetwork.setClientReplyListener(CozyClientBridge::accept);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (MENU.consumeClick()) {
            if (mc.player != null && mc.level != null && mc.gui.screen() == null) CozyMenuScreen.openMenu();
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { CozyClientBridge.reset(); }
}

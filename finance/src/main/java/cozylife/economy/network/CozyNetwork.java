package cozylife.economy.network;

import com.google.gson.JsonObject;
import cozylife.economy.CozyEconomy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Small bounded requests. Identity, item IDs, prices and balances are resolved on the server. */
public final class CozyNetwork {
    public record Request(String action, String id, int count, long sequence) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CozyEconomy.ID, "request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeUtf(p.action, 16); b.writeUtf(p.id, 96); b.writeVarInt(p.count); b.writeVarLong(p.sequence); },
            b -> new Request(b.readUtf(16), b.readUtf(96), b.readVarInt(), b.readVarLong()));
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Reply(String json) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CozyEconomy.ID, "reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = CustomPacketPayload.codec(
            (p, b) -> b.writeUtf(p.json, 262144), b -> new Reply(b.readUtf(262144)));
        @Override public Type<Reply> type() { return TYPE; }
    }
    private record Guard(Object connection, long sequence, long window, int count) {}
    private static final Map<UUID, Guard> GUARDS = new HashMap<>();
    private static final Set<String> FINANCE_ACTIONS = Set.of("open", "quote", "trade", "buy", "sell", "withdraw", "mail", "claim", "vip_quote", "service_quote", "service_buy", "checkin_calendar", "checkin_prepare", "checkin_confirm");
    private static volatile NavigationExtension navigation;
    private static volatile Consumer<Reply> clientReplyListener = reply -> {};
    /** Register the optional integration once during mod construction. */
    public static synchronized void registerNavigationExtension(NavigationExtension extension) {
        Objects.requireNonNull(extension);
        if (navigation != null && navigation != extension) throw new IllegalStateException("只能注册一个菜单导航扩展");
        navigation = extension;
    }
    /** A physical-client addon may subscribe without making the core depend on UI classes. */
    public static void setClientReplyListener(Consumer<Reply> listener) { clientReplyListener = Objects.requireNonNull(listener); }
    public static void clear() { GUARDS.clear(); }
    public static void logout(ServerPlayer player) { GUARDS.remove(player.getUUID()); }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.CODEC, CozyNetwork::receive);
        registrar.playToClient(Reply.TYPE, Reply.CODEC, (reply, context) -> context.enqueueWork(() -> clientReplyListener.accept(reply)));
    }
    private static void receive(Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || player.hasDisconnected()) return;
            var service = CozyEconomy.service();
            if (service == null) return;
            long now = System.nanoTime();
            Guard guard = GUARDS.get(player.getUUID());
            if (guard == null || guard.connection != context.connection()) guard = new Guard(context.connection(), 0, now, 0);
            // Replay/flood rejection does not read the ledger or build a shop snapshot.
            if (request.sequence <= guard.sequence || request.sequence <= 0) return;
            long window = now - guard.window >= 1_000_000_000L ? now : guard.window;
            int count = window == now ? 1 : guard.count + 1;
            GUARDS.put(player.getUUID(), new Guard(context.connection(), request.sequence, window, Math.min(count, 7)));
            if (count > 6) return;
            JsonObject result;
            NavigationExtension extension = navigation;
            if (extension != null && !FINANCE_ACTIONS.contains(request.action) && extension.handles(request.action))
                result = service.snapshot(player, extension.execute(player,request.action,request.id));
            else result = service.handleRequest(player, request.action, request.id, request.count, request.sequence);
            if (extension != null) extension.sections(player).forEach((key, section) -> {
                if (!result.has(key) && !"response_id".equals(key)) result.add(key, section);
            });
            result.addProperty("response_id", request.sequence);
            context.reply(new Reply(result.toString()));
        });
    }
}

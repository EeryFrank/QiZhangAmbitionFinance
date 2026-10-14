package qizhang.cozyservices.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cozylife.economy.network.CozyNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public final class CozyClientBridge {
    private static JsonObject snapshot;
    private static Object connection;
    private static long sequence;
    public static void reset() { snapshot = null; connection = null; sequence = 0; }
    public static JsonObject snapshot() {
        if (connection != Minecraft.getInstance().getConnection()) reset();
        return snapshot;
    }
    public static void request(String action, String id, int count) {
        var current = Minecraft.getInstance().getConnection();
        if (current == null) { reset(); return; }
        if (connection != current) { reset(); connection = current; }
        ClientPacketDistributor.sendToServer(new CozyNetwork.Request(action, id, count, ++sequence));
    }
    public static void accept(CozyNetwork.Reply reply) {
        JsonObject next = JsonParser.parseString(reply.json()).getAsJsonObject();
        if (!next.has("response_id")) return;
        long response = next.get("response_id").getAsLong();
        if (response <= 0 || response > sequence) return;
        if (snapshot != null && response <= snapshot.get("response_id").getAsLong()) return;
        snapshot = next;
    }
}

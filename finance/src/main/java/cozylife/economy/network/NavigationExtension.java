package cozylife.economy.network;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;

/** Optional server-side navigation adapter; all calls run on the server thread.
 * This interface cannot access or replace the finance ledger. Snapshot keys must
 * be distinct from the core response fields. Implementations own their lifecycle.
 */
public interface NavigationExtension {
    /** Whether this extension owns an action; never claim a finance action. */
    boolean handles(String action);
    /** Perform the action as the authenticated connection's player. */
    String execute(ServerPlayer player, String action, String id);
    /** Additional, separately named snapshot sections. */
    Map<String, JsonObject> sections(ServerPlayer player);
}

package cozylife.economy.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-thread writer; immutable snapshots are safely published to command-tree readers. */
final class PermissionVisibilityCache<P> {
    record Decision<P>(P player, boolean allowed) {}
    private volatile Map<UUID, Decision<P>> entries = Map.of();

    Map<UUID, Decision<P>> snapshot() { return entries; }
    boolean allowed(UUID id, P player) {
        Decision<P> decision = entries.get(id);
        return decision != null && decision.player() == player && decision.allowed();
    }
    List<P> publish(Map<UUID, Decision<P>> next) {
        Map<UUID, Decision<P>> immutable = Map.copyOf(next), previous = entries;
        List<P> changed = new ArrayList<>();
        immutable.forEach((id, value) -> {
            Decision<P> old = previous.get(id);
            if (old == null || old.player() != value.player() || old.allowed() != value.allowed())
                changed.add(value.player());
        });
        entries = immutable;
        return List.copyOf(changed);
    }
}

package qizhang.cozyservices.server;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Main-thread ownership of shared finite-lived chunk tickets. Minecraft may deduplicate equal ticket types. */
final class HomeLoadHolds<K> {
    private final Map<K,Integer> users=new HashMap<>();
    private final Consumer<K> add,remove;
    HomeLoadHolds(Consumer<K> add,Consumer<K> remove){this.add=add;this.remove=remove;}
    void acquire(K key) {
        int before=users.getOrDefault(key,0);
        if(before==Integer.MAX_VALUE)throw new IllegalStateException("Too many home ticket holders");
        try {add.accept(key);} // Also refresh TTL when a later request shares an existing ticket.
        catch(RuntimeException e) {
            if(before==0)try{remove.accept(key);}catch(RuntimeException cleanup){e.addSuppressed(cleanup);}
            throw e;
        }
        users.put(key,before+1);
    }
    void release(K key) {
        Integer before=users.get(key);if(before==null)return;
        if(before>1){users.put(key,before-1);return;}
        users.remove(key); // Removal failures do not allow a second release to consume someone else's hold.
        remove.accept(key); // A finite ticket timeout remains the fallback if Minecraft rejects cleanup.
    }
    int count(K key){return users.getOrDefault(key,0);}
    int size(){return users.size();}
}

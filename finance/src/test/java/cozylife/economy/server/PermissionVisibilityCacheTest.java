package cozylife.economy.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PermissionVisibilityCacheTest {
    @Test void missingAndOldConnectionsFailClosedAndPublishedMapsAreImmutable(){
        var cache=new PermissionVisibilityCache<Object>();UUID id=UUID.randomUUID();Object oldPlayer=new Object(),newPlayer=new Object();
        assertFalse(cache.allowed(id,oldPlayer));
        var mutable=new HashMap<UUID,PermissionVisibilityCache.Decision<Object>>();mutable.put(id,new PermissionVisibilityCache.Decision<>(oldPlayer,true));
        cache.publish(mutable);mutable.clear();assertTrue(cache.allowed(id,oldPlayer));
        assertFalse(cache.allowed(id,newPlayer));assertThrows(UnsupportedOperationException.class,()->cache.snapshot().clear());
        cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(newPlayer,false)));
        assertFalse(cache.allowed(id,oldPlayer));assertFalse(cache.allowed(id,newPlayer));
        cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(newPlayer,true)));assertTrue(cache.allowed(id,newPlayer));
        cache.publish(Map.of());assertFalse(cache.allowed(id,newPlayer));
    }
    @Test void pollingOnlyNotifiesChangedDecisionOrConnection(){
        var cache=new PermissionVisibilityCache<Object>();UUID id=UUID.randomUUID();Object player=new Object(),reconnected=new Object();
        assertEquals(1,cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(player,false))).size());
        assertTrue(cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(player,false))).isEmpty());
        assertEquals(1,cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(player,true))).size());
        assertTrue(cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(player,true))).isEmpty());
        assertEquals(1,cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(reconnected,true))).size());
        assertEquals(1,cache.publish(Map.of(id,new PermissionVisibilityCache.Decision<>(reconnected,false))).size());
    }
    @Test void asynchronousReadersOnlyObserveCompletePublishedSnapshots()throws Exception{
        var cache=new PermissionVisibilityCache<Object>();UUID a=UUID.randomUUID(),b=UUID.randomUUID();Object player=new Object();
        Map<UUID,PermissionVisibilityCache.Decision<Object>> allowed=Map.of(a,new PermissionVisibilityCache.Decision<>(player,true),b,new PermissionVisibilityCache.Decision<>(player,true));
        Map<UUID,PermissionVisibilityCache.Decision<Object>> denied=Map.of(a,new PermissionVisibilityCache.Decision<>(player,false),b,new PermissionVisibilityCache.Decision<>(player,false));
        cache.publish(denied);CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var writer=executor.submit(()->{start.await();for(int i=0;i<5000;i++)cache.publish((i&1)==0?allowed:denied);return true;});
            var reader=executor.submit(()->{start.await();for(int i=0;i<10000;i++){var view=cache.snapshot();assertEquals(2,view.size());assertEquals(view.get(a).allowed(),view.get(b).allowed());}return true;});
            start.countDown();assertTrue(writer.get(5,TimeUnit.SECONDS));assertTrue(reader.get(5,TimeUnit.SECONDS));
        }
    }
}

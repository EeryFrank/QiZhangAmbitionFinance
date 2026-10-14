package cozylife.economy.server;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.commands.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** File policy plus optional permissions-plugin resolver; no command-block or remote-console access. */
public final class FinancePermissions {
    public static final Set<String> OPERATIONS=Set.of("list","show","enable","category","price","sellprice","step","recycle","limit","activity","add","addlive","estimate","profile","rule","grantcoins","export","players","stats","job","rewards","config","permissions");
    private static final Map<MinecraftServer,FinancePermissions> INSTANCES=new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final Path file;private JsonObject policy;private BiFunction<UUID,String,Boolean> resolver;
    private final PermissionVisibilityCache<ServerPlayer> visibility=new PermissionVisibilityCache<>();
    private volatile boolean closed;
    private int refreshTicks;
    private FinancePermissions(MinecraftServer server){this.server=server;file=server.getServerDirectory().resolve("config/qizhang-finance/permissions.json");try{reload();}catch(IOException|RuntimeException e){policy=null;org.slf4j.LoggerFactory.getLogger("QiZhangAmbitionFinance").error("权限配置未加载；玩家管理指令已关闭，控制台可修复后重载。",e);}}
    public static FinancePermissions of(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("权限接口必须在服务器主线程");return INSTANCES.computeIfAbsent(server,FinancePermissions::new);}
    private void mainThread(){if(!server.isSameThread())throw new IllegalStateException("权限接口必须在服务器主线程");if(closed)throw new IllegalStateException("权限服务已关闭");}
    public void setResolver(BiFunction<UUID,String,Boolean> value){mainThread();resolver=value;refreshAll(true);}
    public static void close(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("权限接口必须在服务器主线程");FinancePermissions p=INSTANCES.remove(server);if(p!=null){p.closed=true;p.resolver=null;p.visibility.publish(Map.of());}}
    public void reload()throws IOException{mainThread();if(!Files.exists(file))AccountLedger.atomicWrite(file,new GsonBuilder().setPrettyPrinting().create().toJson(defaults())+"\n");if(Files.size(file)>131072)throw new IOException("权限文件过大");JsonObject next=JsonParser.parseString(Files.readString(file)).getAsJsonObject();validate(next);policy=next;refreshAll(true);}
    public static JsonObject defaults(){JsonObject p=new JsonObject();p.addProperty("schema",1);p.addProperty("default_op_level",4);JsonObject operations=new JsonObject();for(String op:new TreeSet<>(OPERATIONS)){JsonObject entry=new JsonObject();entry.addProperty("op_level",4);entry.add("allow_players",new JsonArray());entry.add("deny_players",new JsonArray());operations.add(op,entry);}p.add("operations",operations);return p;}
    public static void validate(JsonObject p){if(AccountLedger.number(p,"schema")!=1)throw new IllegalArgumentException("权限文件版本错误");level(p,"default_op_level");JsonObject ops=p.getAsJsonObject("operations");for(var e:ops.entrySet()){if(!OPERATIONS.contains(e.getKey()))throw new IllegalArgumentException("未知权限操作："+e.getKey());JsonObject entry=e.getValue().getAsJsonObject();level(entry,"op_level");for(String list:List.of("allow_players","deny_players")){if(entry.getAsJsonArray(list).size()>10000)throw new IllegalArgumentException("权限名单过长");for(var id:entry.getAsJsonArray(list))UUID.fromString(id.getAsString());}}}
    private static int level(JsonObject p,String key){int n=Math.toIntExact(AccountLedger.number(p,key));if(n<0||n>4)throw new IllegalArgumentException("OP等级必须为0至4");return n;}
    public boolean allowed(CommandSourceStack source,String op){
        mainThread();
        if(source.source==source.getServer())return true;
        if(!(source.source instanceof ServerPlayer player)||source.getEntity()!=player||policy==null||!OPERATIONS.contains(op))return false;
        JsonObject entry=policy.getAsJsonObject("operations").has(op)?policy.getAsJsonObject("operations").getAsJsonObject(op):null;String uuid=player.getUUID().toString();
        if(entry!=null&&contains(entry.getAsJsonArray("deny_players"),uuid))return false;
        if(resolver!=null){try{Boolean decision=resolver.apply(player.getUUID(),"qizhangfinance.admin."+op);if(decision!=null)return decision;}catch(RuntimeException failure){return false;}}
        if(entry!=null&&contains(entry.getAsJsonArray("allow_players"),uuid))return true;
        int n=entry==null?level(policy,"default_op_level"):level(entry,"op_level");
        var check=switch(n){case 0->Commands.LEVEL_ALL;case 1->Commands.LEVEL_MODERATORS;case 2->Commands.LEVEL_GAMEMASTERS;case 3->Commands.LEVEL_ADMINS;default->Commands.LEVEL_OWNERS;};return Commands.hasPermission(check).test(source);
    }
    private static boolean contains(JsonArray values,String uuid){for(var value:values)if(value.getAsString().equals(uuid))return true;return false;}
    private boolean anyAllowed(CommandSourceStack source){for(String op:OPERATIONS)if(allowed(source,op))return true;return false;}
    /** Youer sends command trees asynchronously. Never load policy or invoke a resolver from that reader. */
    public static boolean canUseRoot(CommandSourceStack source){
        MinecraftServer server=source.getServer();
        if(source.source==server)return true;
        if(!(source.source instanceof ServerPlayer player)||source.getEntity()!=player)return false;
        if(server.isSameThread())return of(server).anyAllowed(source);
        FinancePermissions p=INSTANCES.get(server);
        return p!=null&&!p.closed&&p.visibility.allowed(player.getUUID(),player);
    }
    private void refreshAll(boolean notify){
        mainThread();Map<UUID,PermissionVisibilityCache.Decision<ServerPlayer>> next=new HashMap<>();
        for(ServerPlayer player:server.getPlayerList().getPlayers())
            next.put(player.getUUID(),new PermissionVisibilityCache.Decision<>(player,anyAllowed(player.createCommandSourceStack())));
        List<ServerPlayer> changed=visibility.publish(next);
        if(notify)for(ServerPlayer player:changed)server.getCommands().sendCommands(player);
    }
    public static void login(ServerPlayer player){
        FinancePermissions p=of(player.level().getServer());p.mainThread();
        Map<UUID,PermissionVisibilityCache.Decision<ServerPlayer>> next=new HashMap<>(p.visibility.snapshot());
        next.put(player.getUUID(),new PermissionVisibilityCache.Decision<>(player,p.anyAllowed(player.createCommandSourceStack())));
        p.visibility.publish(next);
        // The first tree can precede PlayerLoggedInEvent. Always resend after this connection has a snapshot.
        p.server.getCommands().sendCommands(player);
    }
    public static void logout(ServerPlayer player){
        FinancePermissions p=INSTANCES.get(player.level().getServer());if(p==null)return;p.mainThread();
        Map<UUID,PermissionVisibilityCache.Decision<ServerPlayer>> next=new HashMap<>(p.visibility.snapshot());
        var old=next.get(player.getUUID());if(old!=null&&old.player()==player){next.remove(player.getUUID());p.visibility.publish(next);}
    }
    public static void tick(MinecraftServer server){FinancePermissions p=INSTANCES.get(server);if(p==null||p.closed)return;p.mainThread();if(++p.refreshTicks>=20){p.refreshTicks=0;p.refreshAll(true);}}
    public static void require(CommandSourceStack source,String input){String[] parts=input.trim().split("\\s+");String op=parts.length==0?"list":parts[0];if("api".equals(op)){if(parts.length<3)throw new IllegalArgumentException("管理API缺少请求编号或操作");op=parts[2];if("command".equals(op)){if(parts.length<4)throw new IllegalArgumentException("缺少商城管理操作");op=parts[3];}}if(!OPERATIONS.contains(op))throw new IllegalArgumentException("未知管理操作，请查看帮助");if(!of(source.getServer()).allowed(source,op))throw new IllegalArgumentException("没有此操作权限：qizhangfinance.admin."+op);}
}

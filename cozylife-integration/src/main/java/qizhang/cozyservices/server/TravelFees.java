package qizhang.cozyservices.server;

import com.google.gson.JsonObject;
import cozylife.economy.server.EconomyService;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative travel tariff. Quotes are fixed before a pending task starts. */
public final class TravelFees {
    private static final Map<MinecraftServer,TravelFees> INSTANCES=new WeakHashMap<>();
    public record Quote(long fee,double distance,boolean crossDimension,String fromDimension,String toDimension) {}
    public record Tariff(long base,long blocksPerStep,long pointsPerStep,long crossDimensionSurcharge) {
        public Tariff {
            if(base<0||base>1_000_000||blocksPerStep<1||blocksPerStep>1_000_000||pointsPerStep<0||pointsPerStep>1_000_000||crossDimensionSurcharge<0||crossDimensionSurcharge>1_000_000)
                throw new IllegalArgumentException("传送费配置超出安全范围");
        }
        public long calculate(double distance,boolean crossDimension) {
            if(!Double.isFinite(distance)||distance<0||distance>200_000_000)throw new IllegalArgumentException("传送距离无效");
            long steps=(long)Math.ceil(distance/blocksPerStep);
            long fee=Math.addExact(Math.addExact(base,Math.multiplyExact(steps,pointsPerStep)),crossDimension?crossDimensionSurcharge:0);
            if(fee>1_000_000_000L)throw new IllegalArgumentException("本次传送费用超出安全范围");return fee;
        }
        public String formula(){return "起步 "+base+" + 每 "+blocksPerStep+" 格 "+pointsPerStep+"（不足一段向上取整）；跨维度加 "+crossDimensionSurcharge+"。按三维直线距离，跨维度直接比较坐标，不作 8 倍换算";}
    }
    private final Tariff tariff;
    private final String unavailable;
    private TravelFees(MinecraftServer server) {
        Tariff loaded=null;String failure=null;
        Path path=server.getServerDirectory().resolve("config/qizhang-travel-fees.json");
        try {
            if(!Files.exists(path)) {
                JsonObject out=new JsonObject();out.addProperty("schema",1);out.addProperty("base",10);out.addProperty("blocks_per_step",100);out.addProperty("points_per_step",1);out.addProperty("cross_dimension_surcharge",50);
                LocalStateFiles.atomicWrite(path,out+"\n");
            }
            if(Files.size(path)>8192)throw new IOException("传送费配置文件过大");
            JsonObject config=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(LocalStateFiles.number(config,"schema")!=1)throw new IllegalArgumentException("传送费配置版本错误");
            loaded=new Tariff(LocalStateFiles.number(config,"base"),LocalStateFiles.number(config,"blocks_per_step"),LocalStateFiles.number(config,"points_per_step"),LocalStateFiles.number(config,"cross_dimension_surcharge"));
        } catch(IOException|RuntimeException e) {
            failure="传送费配置无法读取，收费传送已停用，请联系管理员";
            org.slf4j.LoggerFactory.getLogger("QiZhangEconomy").error("无法读取传送费配置 {}",path,e);
        }
        tariff=loaded;unavailable=failure;
    }
    public static synchronized TravelFees forServer(MinecraftServer server){return INSTANCES.computeIfAbsent(server,TravelFees::new);}
    public static synchronized void close(MinecraftServer server){INSTANCES.remove(server);}
    public String formula(){if(unavailable!=null)throw new IllegalStateException(unavailable);return tariff.formula();}
    public Quote quote(ServerPlayer player,ServerLevel target,Vec3 position) {
        if(unavailable!=null)throw new IllegalStateException(unavailable);
        if(target==null||position==null)throw new IllegalArgumentException("传送目标暂不可用");
        Vec3 start=player.position();
        if(!finite(start)||!finite(position))throw new IllegalArgumentException("传送坐标无效");
        String from=player.level().dimension().identifier().toString(),to=target.dimension().identifier().toString();
        double distance=Math.hypot(Math.hypot(position.x-start.x,position.y-start.y),position.z-start.z);
        boolean cross=!from.equals(to);return new Quote(tariff.calculate(distance,cross),distance,cross,from,to);
    }
    public boolean sameChargeBand(Quote locked,ServerPlayer player,ServerLevel target,Vec3 position) {
        Quote current=quote(player,target,position);
        return sameChargeBand(locked,current);
    }
    public static boolean sameChargeBand(Quote locked,Quote current) {
        return current.fee==locked.fee&&current.crossDimension==locked.crossDimension&&current.fromDimension.equals(locked.fromDimension)&&current.toDimension.equals(locked.toDimension);
    }
    private static boolean finite(Vec3 p){return Double.isFinite(p.x)&&Double.isFinite(p.y)&&Double.isFinite(p.z)&&Math.abs(p.x)<=30_000_000&&Math.abs(p.z)<=30_000_000&&Math.abs(p.y)<=4096;}
    public static EconomyService economy(){EconomyService service=cozylife.economy.CozyEconomy.service();if(service==null)throw new IllegalStateException("经济系统尚未就绪，未传送且未扣费");return service;}
    public static String error(Exception e){
        String message=e.getMessage();
        if(message!=null&&!message.isBlank()&&Character.UnicodeScript.of(message.codePointAt(0))==Character.UnicodeScript.HAN)return message;
        return "传送或扣费结果尚待核实，请联系管理员，勿重复提交";
    }
}

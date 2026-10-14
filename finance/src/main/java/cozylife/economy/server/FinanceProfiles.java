package cozylife.economy.server;

import com.google.gson.*;
import java.util.List;

/** Manual policies: recommendations never move money, reset quotas or change item bases. */
public final class FinanceProfiles {
    public record Profile(String id,String name,String step,int observationDays,String maxWeeklyAdjustment,int minimumSample) {}
    public static final List<Profile> ALL=List.of(
        new Profile("small","小型服 · 1–19 位活跃玩家","0.0001",14,"0.05",3),
        new Profile("medium","中型服 · 20–49 位活跃玩家","0.00005",7,"0.05",10),
        new Profile("large","大型服 · 50 位及以上活跃玩家","0.00002",7,"0.03",30));
    private FinanceProfiles(){}
    public static Profile get(String id){return ALL.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("金融档位必须是 small、medium 或 large"));}
    public static String recommended(int active){if(active<0)throw new IllegalArgumentException("活跃人数不能为负");return active<20?"small":active<50?"medium":"large";}
    public static void apply(JsonObject config,String id){Profile p=get(id);config.addProperty("finance_profile",p.id());config.addProperty("step",p.step());}
    public static void validate(JsonObject config){if(config.has("finance_profile"))get(config.get("finance_profile").getAsString());}
    public static void describe(JsonObject config,JsonObject stats){
        String selected=config.has("finance_profile")?config.get("finance_profile").getAsString():"small";get(selected);
        stats.addProperty("finance_profile",selected);stats.addProperty("recommended_profile",recommended(stats.get("active_players_7d").getAsInt()));
        JsonArray choices=new JsonArray();for(Profile p:ALL){JsonObject o=new JsonObject();o.addProperty("id",p.id());o.addProperty("name",p.name());o.addProperty("step",p.step());o.addProperty("observation_days",p.observationDays());o.addProperty("max_weekly_adjustment",p.maxWeeklyAdjustment());o.addProperty("minimum_sample",p.minimumSample());choices.add(o);}stats.add("profile_options",choices);
        stats.addProperty("profile_note","按近7天活跃人数推荐，由管理员手动切换。仅应用该档成交步长，不改变余额、官方价、市价、VIP或已用回收额度；观察期和每周调幅只是人工参考，不会自动执行。手动 step 可覆盖本档步长，下次切档重新应用推荐值。");
    }
}

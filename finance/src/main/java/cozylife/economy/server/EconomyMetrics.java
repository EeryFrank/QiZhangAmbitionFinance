package cozylife.economy.server;

import com.google.gson.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** Counters participate in the same ledger commit; wallet conversions are never monetary issuance. */
public final class EconomyMetrics {
    private static final List<String> KEYS=List.of("recycle_issued","shop_sink","travel_sink","repair_sink","vip_sink","coin_deposit","coin_withdraw","admin_coin_mail_issued","api_credit_issued","api_debit_sink","api_transfers","checkin_issued","checkin_milestone_coin_issued");
    private EconomyMetrics(){}
    public static void record(JsonObject state,UUID player,String action,JsonObject detail,long now) {
        JsonObject metrics=state.has("economic_metrics")?state.getAsJsonObject("economic_metrics"):new JsonObject();
        if(!metrics.has("started_at"))metrics.addProperty("started_at",now);
        JsonObject days=metrics.has("days")?metrics.getAsJsonObject("days"):new JsonObject();String day=MarketPricing.day(now);
        JsonObject row=days.has(day)?days.getAsJsonObject(day):new JsonObject();
        for(String key:KEYS)if(!row.has(key))row.addProperty(key,0L);
        String field=switch(action){case "sell"->"recycle_issued";case "buy"->"shop_sink";case "paid_teleport"->"travel_sink";case "service_repair"->"repair_sink";case "vip"->"vip_sink";case "deposit"->"coin_deposit";case "withdraw"->"coin_withdraw";case "admin_coin_mail"->"admin_coin_mail_issued";case "api_credit"->"api_credit_issued";case "api_debit"->"api_debit_sink";case "api_transfer"->"api_transfers";default->null;};
        if(field!=null){long amount=AccountLedger.number(detail,"total");if(amount<0)throw new IllegalArgumentException("经济统计金额不能为负");row.addProperty(field,Math.addExact(AccountLedger.number(row,field),amount));}
        if("checkin".equals(action)){row.addProperty("checkin_issued",Math.addExact(AccountLedger.number(row,"checkin_issued"),AccountLedger.number(detail,"total")));row.addProperty("checkin_milestone_coin_issued",Math.addExact(AccountLedger.number(row,"checkin_milestone_coin_issued"),AccountLedger.number(detail,"milestone_coin_value")));}
        if("sell".equals(action)) {
            JsonObject items=row.has("recycle_by_item")?row.getAsJsonObject("recycle_by_item"):new JsonObject();String item=AccountLedger.text(detail,"item");
            items.addProperty(item,Math.addExact(items.has(item)?items.get(item).getAsLong():0L,AccountLedger.number(detail,"total")));row.add("recycle_by_item",items);
        }
        days.add(day,row);String oldest=LocalDate.parse(day).minusDays(399).toString();for(String key:List.copyOf(days.keySet()))if(key.compareTo(oldest)<0)days.remove(key);
        metrics.add("days",days);state.add("economic_metrics",metrics);
        if(!action.startsWith("api_")&&!"admin_coin_mail".equals(action))MarketLedger.account(state,player).addProperty("last_activity",now);
    }
    public static JsonObject report(JsonObject state,Set<UUID> online,long now) {
        JsonObject result=new JsonObject(),accounts=state.getAsJsonObject("accounts"),known=KnownEconomyPlayers.entries(state);long total=0,max=0,activeTotal=0;List<Long> active=new ArrayList<>();
        for(var entry:accounts.entrySet()) {
            JsonObject account=entry.getValue().getAsJsonObject();long value=AccountLedger.number(account,"balance");total=Math.addExact(total,value);
            long last=account.has("last_activity")?AccountLedger.number(account,"last_activity"):0;
            if(known.has(entry.getKey()))last=Math.max(last,AccountLedger.number(known.getAsJsonObject(entry.getKey()),"last_seen"));
            if(online.contains(UUID.fromString(entry.getKey()))||last>0&&now-last<=7L*86400000){active.add(value);activeTotal=Math.addExact(activeTotal,value);max=Math.max(max,value);}
        }
        Collections.sort(active);BigDecimal median=BigDecimal.ZERO;
        if(!active.isEmpty()){int n=active.size();median=BigDecimal.valueOf(active.get(n/2));if(n%2==0)median=median.add(BigDecimal.valueOf(active.get(n/2-1))).divide(BigDecimal.valueOf(2));}
        result.addProperty("total_balance",total);result.addProperty("known_accounts",accounts.size());result.addProperty("active_players_7d",active.size());
        result.addProperty("active_balance_mean",active.isEmpty()?BigDecimal.ZERO:BigDecimal.valueOf(activeTotal).divide(BigDecimal.valueOf(active.size()),2,RoundingMode.HALF_UP));result.addProperty("active_balance_median",median);result.addProperty("active_balance_max",max);
        Set<String> basket=Set.of("minecraft:oak_log","minecraft:wheat","minecraft:potato","minecraft:cod","minecraft:raw_iron","minecraft:coal","minecraft:redstone","farmersdelight:tomato");
        BigDecimal index=BigDecimal.ZERO;int used=0;Set<String> seen=new HashSet<>();
        for(var value:state.getAsJsonObject("config").getAsJsonArray("products")){JsonObject product=value.getAsJsonObject();String item=AccountLedger.text(product,"item");if(basket.contains(item)&&seen.add(item)) {index=index.add(new BigDecimal(state.getAsJsonObject("market").get(item).getAsString()).divide(BigDecimal.valueOf(AccountLedger.number(product,"base")),12,RoundingMode.HALF_UP));used++;}}
        result.addProperty("price_index",used==0?BigDecimal.valueOf(100):index.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(used),2,RoundingMode.HALF_UP));result.addProperty("basket_items",used);
        JsonArray rows=new JsonArray();JsonObject metrics=state.has("economic_metrics")?state.getAsJsonObject("economic_metrics"):new JsonObject(),days=metrics.has("days")?metrics.getAsJsonObject("days"):new JsonObject();
        LocalDate today=LocalDate.parse(MarketPricing.day(now));
        for(int i=6;i>=0;i--){String day=today.minusDays(i).toString();JsonObject row=days.has(day)?days.getAsJsonObject(day).deepCopy():new JsonObject();row.addProperty("day",day);for(String key:KEYS)if(!row.has(key))row.addProperty(key,0L);rows.add(row);}
        result.add("days",rows);result.addProperty("metrics_started_at",metrics.has("started_at")?AccountLedger.number(metrics,"started_at"):0);
        result.addProperty("scope_note","余额统计不包含背包、箱子和地面上的实体币；存入与取出仅改变货币形态。管理员实体币邮件单列，已有怪物/箱子实体币投放尚未完整计量。统计从本版启用后累计，之前空白日期不表示当时无交易。");
        result.addProperty("policy_note",active.size()<3?"活跃样本不足3人，只观察，不自动调价。":"以7天活跃余额中位数、回收收入与消费、常用品指数辅助人工调控；平均值只作参考。当前不会自动修改价格或额度。");
        return result;
    }
    public static void validate(JsonObject state) {
        if(!state.has("economic_metrics"))return;JsonObject m=state.getAsJsonObject("economic_metrics");if(AccountLedger.number(m,"started_at")<0)throw new IllegalArgumentException("经济统计开始时间错误");JsonObject days=m.getAsJsonObject("days");if(days.size()>400)throw new IllegalArgumentException("经济统计日期过多");
        for(var entry:days.entrySet()){LocalDate.parse(entry.getKey());JsonObject row=entry.getValue().getAsJsonObject();for(String field:KEYS)if(row.has(field)&&AccountLedger.number(row,field)<0)throw new IllegalArgumentException("经济统计金额错误");if(row.has("recycle_by_item"))for(var amount:row.getAsJsonObject("recycle_by_item").entrySet())if(amount.getValue().getAsBigDecimal().longValueExact()<0)throw new IllegalArgumentException("商品收入统计错误");}
    }
}

package cozylife.economy.server;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.Set;

/** Optional business rules. Integer currency, replay protection and buy/sell spread remain mandatory. */
public final class FinanceRules {
    public static final Set<String> SWITCHES=Set.of("dynamic_pricing","recycle","daily_recycle_limit","weekly_reversion","vip_discounts");
    public static final Set<String> DECIMALS=Set.of("weekly_reversion_ratio","min_market_multiplier","max_market_multiplier");
    private FinanceRules(){}
    public static boolean enabled(JsonObject config,String key){return !config.has("rules")||!config.getAsJsonObject("rules").has(key)||config.getAsJsonObject("rules").get(key).getAsBoolean();}
    public static BigDecimal ratio(JsonObject config){return config.has("rules")&&config.getAsJsonObject("rules").has("weekly_reversion_ratio")?MarketPricing.decimal(config.getAsJsonObject("rules").get("weekly_reversion_ratio").getAsString()):new BigDecimal("0.2");}
    public static BigDecimal step(JsonObject config){return enabled(config,"dynamic_pricing")?MarketPricing.decimal(AccountLedger.text(config,"step")):BigDecimal.ZERO;}
    private static BigDecimal number(JsonObject config,String key,String fallback){return config.has("rules")&&config.getAsJsonObject("rules").has(key)?MarketPricing.decimal(config.getAsJsonObject("rules").get(key).getAsString()):new BigDecimal(fallback);}
    public static BigDecimal lower(JsonObject config){return number(config,"min_market_multiplier","0.5");}
    public static BigDecimal upper(JsonObject config){return number(config,"max_market_multiplier","1.5");}
    public static MarketPricing.Total total(JsonObject config,long base,long sellBase,BigDecimal market,BigDecimal multiplier,int count,boolean buying){
        if(base<1||base>1_000_000_000L||count<1||count>64)throw new IllegalArgumentException("交易价格或数量无效");
        BigDecimal official=BigDecimal.valueOf(base),min=official.multiply(lower(config)),max=official.multiply(upper(config)),current=market.max(min).min(max),delta=official.multiply(step(config));long total=0;
        for(int i=0;i<count;i++){long unit=MarketPricing.buy(current,multiplier);if(!buying){unit=MarketPricing.independentSell(base,sellBase,current,unit);if(unit==0)throw new IllegalArgumentException("当前售价为1或回收未启用，该商品不能回收");}total=Math.addExact(total,unit);current=(buying?current.add(delta):current.subtract(delta)).max(min).min(max);}return new MarketPricing.Total(total,current);
    }
    public static BigDecimal weekly(JsonObject config,long base,BigDecimal market,long weeks){if(weeks<0||weeks>5200)throw new IllegalArgumentException("周次跨度异常");BigDecimal official=BigDecimal.valueOf(base),value=market;if(enabled(config,"weekly_reversion")&&ratio(config).signum()>0)for(long i=0;i<weeks;i++)value=official.add(value.subtract(official).multiply(BigDecimal.ONE.subtract(ratio(config)))).setScale(8,java.math.RoundingMode.HALF_EVEN);return value.max(official.multiply(lower(config))).min(official.multiply(upper(config)));}
    public static void set(JsonObject config,String key,String value){JsonObject rules=config.has("rules")?config.getAsJsonObject("rules"):new JsonObject();if(SWITCHES.contains(key)){if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("调控开关只能填写 true 或 false");rules.addProperty(key,Boolean.parseBoolean(value));}else if(DECIMALS.contains(key)){rules.addProperty(key,MarketPricing.decimal(value).toPlainString());}else throw new IllegalArgumentException("未知调控项："+key);config.add("rules",rules);validate(config);}
    public static void validate(JsonObject config){if(!config.has("rules"))return;JsonObject rules=config.getAsJsonObject("rules");for(var e:rules.entrySet()){if(SWITCHES.contains(e.getKey())){if(!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("调控开关必须为布尔值");}else if(!DECIMALS.contains(e.getKey()))throw new IllegalArgumentException("未知调控条件："+e.getKey());}BigDecimal ratio=ratio(config);if(ratio.signum()<0||ratio.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("每周回调比例范围是 0 至 1");if(lower(config).compareTo(new BigDecimal("0.1"))<0||lower(config).compareTo(BigDecimal.ONE)>0||upper(config).compareTo(BigDecimal.ONE)<0||upper(config).compareTo(new BigDecimal("5"))>0)throw new IllegalArgumentException("市价下限须为0.1至1、上限须为1至5；默认0.5至1.5");}
}

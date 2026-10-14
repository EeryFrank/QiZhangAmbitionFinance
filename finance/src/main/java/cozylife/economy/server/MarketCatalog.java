package cozylife.economy.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

public final class MarketCatalog {
    public static final Set<String> CATEGORIES=Set.of("building","ores","farming","equipment","modded","rare");
    private MarketCatalog(){}
    public static JsonObject initial(long now) {
        JsonObject state=new JsonObject();state.addProperty("revision",0L);state.addProperty("market_revision",0L);state.addProperty("last_monday",MarketPricing.monday(now));
        JsonObject config=defaults();state.add("config",config);state.add("accounts",new JsonObject());
        JsonObject market=new JsonObject();for(var el:config.getAsJsonArray("products")){JsonObject p=el.getAsJsonObject();market.addProperty(p.get("item").getAsString(),p.get("base").getAsString());}
        state.add("market",market);return state;
    }
    public static JsonObject defaults(){
        JsonObject config=new JsonObject();config.addProperty("schema",3);config.addProperty("finance_profile","small");config.addProperty("step","0.0001");
        config.addProperty("activity_enabled",false);config.addProperty("activity_discount","1");config.addProperty("transaction_cooldown_ticks",4);
        config.addProperty("vip_normal_discount","0.95");config.addProperty("vip_activity_discount","0.98");
        config.add("checkin",CheckInRules.defaults());
        JsonArray products=new JsonArray();
        String[] logs={"oak_log","spruce_log","birch_log","jungle_log","acacia_log","dark_oak_log","mangrove_log","cherry_log","pale_oak_log"};
        String[] names={"橡木原木","云杉原木","白桦原木","丛林原木","金合欢原木","深色橡木原木","红树原木","樱花原木","苍白橡木原木"};
        for(int i=0;i<logs.length;i++)add(products,"minecraft:"+logs[i],names[i],"building",16,true,128);
        add(products,"minecraft:raw_copper","粗铜","ores",20,true,128);add(products,"minecraft:raw_iron","粗铁","ores",70,true,64);
        add(products,"minecraft:raw_gold","粗金","ores",350,true,32);
        add(products,"minecraft:coal","煤炭","ores",24,true,128);add(products,"minecraft:redstone","红石粉","ores",16,true,128);
        add(products,"minecraft:lapis_lazuli","青金石","ores",30,true,128);add(products,"minecraft:quartz","下界石英","ores",40,true,64);
        add(products,"minecraft:amethyst_shard","紫水晶碎片","ores",24,true,64);
        String[] crops={"wheat","carrot","potato","beetroot","sugar_cane","sweet_berries","wheat_seeds","beetroot_seeds","melon_seeds","pumpkin_seeds"};
        String[] cropNames={"小麦","胡萝卜","马铃薯","甜菜根","甘蔗","甜浆果","小麦种子","甜菜种子","西瓜种子","南瓜种子"};
        for(int i=0;i<crops.length;i++)add(products,"minecraft:"+crops[i],cropNames[i],"farming",i>=6?4:8,true,128);
        add(products,"minecraft:cod","生鳕鱼","farming",20,true,64);add(products,"minecraft:salmon","生鲑鱼","farming",24,true,64);
        add(products,"minecraft:tropical_fish","热带鱼","farming",40,true,32);add(products,"minecraft:pufferfish","河豚","farming",40,true,32);
        add(products,"minecraft:string","线","farming",12,true,64);add(products,"minecraft:leather","皮革","farming",28,true,64);
        add(products,"minecraft:bone","骨头","farming",12,true,64);
        add(products,"minecraft:fishing_rod","钓鱼竿","equipment",160,false,0);add(products,"minecraft:iron_pickaxe","铁镐","equipment",360,false,0);
        add(products,"minecraft:iron_axe","铁斧","equipment",360,false,0);
        add(products,"farmersdelight:rice","稻米","modded",12,true,128);add(products,"farmersdelight:tomato","番茄","modded",10,true,128);
        add(products,"farmersdelight:cabbage","卷心菜","modded",12,true,128);add(products,"farmersdelight:onion","洋葱","modded",10,true,128);
        add(products,"farmersdelight:straw","草秆","modded",6,true,64);add(products,"farmersdelight:cabbage_seeds","卷心菜种子","modded",5,true,128);
        add(products,"farmersdelight:tomato_seeds","番茄种子","modded",5,true,128);
        add(products,"ae2:silicon","硅","modded",96,false,0);add(products,"ae2:certus_quartz_crystal","赛特斯石英水晶","modded",120,false,0);add(products,"ae2:fluix_crystal","福鲁伊克斯水晶","modded",240,false,0);
        add(products,"minecraft:diamond","钻石","rare",2000,false,0);add(products,"minecraft:emerald","绿宝石","rare",1400,false,0);
        add(products,"minecraft:nautilus_shell","鹦鹉螺壳","rare",240,false,0);add(products,"minecraft:ender_pearl","末影珍珠","rare",120,false,0);
        config.add("products",products);return config;
    }
    private static void add(JsonArray list,String item,String name,String category,long base,boolean sell,int cap){
        JsonObject p=new JsonObject();p.addProperty("id",String.format("#%03d",list.size()+1));p.addProperty("item",item);p.addProperty("name",name);
        p.addProperty("category",category);p.addProperty("base",base);p.addProperty("sell_base",sell?Math.max(1,base/5):0);p.addProperty("enabled",true);p.addProperty("sell_enabled",sell);
        p.addProperty("daily_sell_limit",cap);p.addProperty("activity_enabled",false);p.addProperty("activity_discount","1");list.add(p);
    }
    public static JsonObject product(JsonObject config,String id){
        for(var p:config.getAsJsonArray("products"))if(p.getAsJsonObject().get("id").getAsString().equals(id))return p.getAsJsonObject();
        throw new IllegalArgumentException("商品编号不存在");
    }
    public static void validate(JsonObject config){
        long schema=AccountLedger.number(config,"schema");if(schema!=2&&schema!=3)throw new IllegalArgumentException("商品配置版本错误");
        FinanceProfiles.validate(config);FinanceRules.validate(config);CheckInRules.validateConfig(config);MarketPricing.validateStep(MarketPricing.decimal(AccountLedger.text(config,"step")));
        MarketPricing.validateDiscount(MarketPricing.decimal(AccountLedger.text(config,"activity_discount")));
        MarketPricing.validateDiscount(MarketPricing.decimal(AccountLedger.text(config,"vip_normal_discount")));MarketPricing.validateDiscount(MarketPricing.decimal(AccountLedger.text(config,"vip_activity_discount")));
        int cooldown=Math.toIntExact(AccountLedger.number(config,"transaction_cooldown_ticks"));if(cooldown<4||cooldown>200)throw new IllegalArgumentException("交易冷却错误");
        Set<String> ids=new HashSet<>();java.util.Map<String,JsonObject> items=new java.util.HashMap<>();
        if(config.getAsJsonArray("products").size()>128)throw new IllegalArgumentException("最多上架128种商品");
        for(var el:config.getAsJsonArray("products")){
            JsonObject p=el.getAsJsonObject();String id=AccountLedger.text(p,"id"),item=AccountLedger.text(p,"item");
            if(!id.matches("#[0-9]{3,5}")||!ids.add(id)||!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||item.startsWith("cozylife_economy:"))throw new IllegalArgumentException("重复或不合法商品编号/真实物品ID");
            JsonObject previous=items.putIfAbsent(item,p);if(previous!=null)for(String field:java.util.List.of("base","sell_enabled","daily_sell_limit","activity_enabled","activity_discount"))if(!previous.get(field).equals(p.get(field)))throw new IllegalArgumentException("同一真实物品的价格、回收、额度和活动参数必须共享："+item);
            if(lowValueExcluded(item))throw new IllegalArgumentException("禁止上架随处可挖的低值方块");
            if(excludedByOwner(item))throw new IllegalArgumentException("服主已排除矿锭、矿块、基础材料压缩块及原木以外木制建材："+item);
            if(!CATEGORIES.contains(AccountLedger.text(p,"category")))throw new IllegalArgumentException("分类不受支持");
            long base=AccountLedger.number(p,"base"),cap=AccountLedger.number(p,"daily_sell_limit");
            if(base<1||base>1_000_000_000L||cap<0||cap>100000)throw new IllegalArgumentException("官方价或每日回收额度错误");
            if(p.get("sell_enabled").getAsBoolean()&&cap==0)throw new IllegalArgumentException("开启回收必须设置正的每日额度");
            if("rare".equals(p.get("category").getAsString())&&p.get("sell_enabled").getAsBoolean())throw new IllegalArgumentException("稀有物品只出售，不回收");
            MarketPricing.validateDiscount(MarketPricing.decimal(AccountLedger.text(p,"activity_discount")));
            String name=AccountLedger.text(p,"name");if(name.isBlank()||name.length()>40)throw new IllegalArgumentException("商品中文名称错误");
            if(schema==3){long sell=AccountLedger.number(p,"sell_base");if(sell<0||sell>=base)throw new IllegalArgumentException("回收基准价必须为0或低于官方买价");if(p.get("sell_enabled").getAsBoolean()&&sell==0)throw new IllegalArgumentException("回收基准为0时不能开启回收");if(previous!=null&&!previous.get("sell_base").equals(p.get("sell_base")))throw new IllegalArgumentException("同物品回收基准必须共享");}
            else if(p.get("enabled").getAsBoolean()&&p.get("sell_enabled").getAsBoolean())validateRoundTrip(config,p);
        }
        if(schema==2)validateConversions(config,items);
    }
    public static boolean lowValueExcluded(String item){return Set.of("minecraft:dirt","minecraft:cobblestone","minecraft:stone","minecraft:deepslate","minecraft:cobbled_deepslate","minecraft:netherrack","minecraft:sand","minecraft:gravel").contains(item);}
    public static boolean excludedByOwner(String item){
        if(Set.of("minecraft:crimson_fungus","minecraft:warped_fungus").contains(item))return false;
        String path=item.substring(item.indexOf(':')+1);
        if(path.endsWith("_ingot"))return true;
        if(Set.of("minecraft:copper_block","minecraft:iron_block","minecraft:gold_block","minecraft:netherite_block","minecraft:coal_block","minecraft:diamond_block","minecraft:emerald_block","minecraft:redstone_block","minecraft:lapis_block","minecraft:raw_copper_block","minecraft:raw_iron_block","minecraft:raw_gold_block","minecraft:hay_block","minecraft:bone_block","minecraft:slime_block","minecraft:honey_block","minecraft:dried_kelp_block","minecraft:bamboo_block","minecraft:stripped_bamboo_block","minecraft:packed_mud","ae2:fluix_block","ae2:quartz_block","ae2:certus_quartz_block","farmersdelight:rice_bag","farmersdelight:rice_bale","farmersdelight:straw_bale").contains(item))return true;
        if(item.startsWith("farmersdelight:")&&path.endsWith("_crate"))return true;
        if(item.startsWith("minecraft:")){
            if(path.startsWith("stripped_")||path.endsWith("_planks")||path.endsWith("_wood")||path.endsWith("_hyphae"))return true;
            for(String wood:java.util.List.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","pale_oak","bamboo","crimson","warped")){
                if(path.startsWith(wood+"_")&&!path.equals(wood+"_log")&&!path.equals(wood+"_stem")&&!path.endsWith("_leaves")&&!path.endsWith("_sapling")&&!path.endsWith("_propagule"))return true;
            }
            if(Set.of("minecraft:stick","minecraft:ladder","minecraft:chest","minecraft:trapped_chest","minecraft:barrel","minecraft:crafting_table","minecraft:bookshelf").contains(item))return true;
        }return false;
    }

    /** Check all discontinuities that can violate the exact integer buy/sell inequality. */
    public static void validateRoundTrip(JsonObject config,JsonObject p){
        BigDecimal base=BigDecimal.valueOf(AccountLedger.number(p,"base")),low=base.multiply(new BigDecimal("0.5")),high=base.multiply(new BigDecimal("1.5"));
        BigDecimal step=MarketPricing.decimal(AccountLedger.text(config,"step")),delta=base.multiply(step),best=multiplier(config,p,true).min(new BigDecimal("0.95"));
        if(best.compareTo(new BigDecimal("0.8"))<=0)throw new IllegalArgumentException("可回收商品优惠过深，活动结束后存在倒卖风险："+p.get("id").getAsString());
        // sell(ceil(p+delta)) < .8*p + .8*delta + 1.8; above bound no rounding can invert spread.
        BigDecimal bound=delta.multiply(new BigDecimal("0.8")).add(new BigDecimal("1.8")).divide(best.subtract(new BigDecimal("0.8")),12,java.math.RoundingMode.CEILING).min(high);
        checkRoundTrip(p,low,high,delta,best);checkRoundTrip(p,high,high,delta,best);
        if(bound.compareTo(low)<0)return;
        long first=low.add(delta).setScale(0,java.math.RoundingMode.FLOOR).longValueExact(),last=bound.add(delta).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
        if(last-first>20000)throw new IllegalArgumentException("当前步长和折扣难以安全验证，请减小步长或取消该回收优惠");
        BigDecimal epsilon=new BigDecimal("0.000000000001");
        for(long k=first;k<=last;k++){BigDecimal point=BigDecimal.valueOf(k).subtract(delta).add(epsilon);if(point.compareTo(low)>=0&&point.compareTo(high)<=0)checkRoundTrip(p,point,high,delta,best);}
    }
    private static void checkRoundTrip(JsonObject p,BigDecimal market,BigDecimal high,BigDecimal delta,BigDecimal best){
        long paid=MarketPricing.buy(market,best),received=MarketPricing.sell(MarketPricing.buy(market.add(delta).min(high),BigDecimal.ONE));
        if(received>paid)throw new IllegalArgumentException("拒绝可识别的买回套利配置："+p.get("id").getAsString()+" 在市价 "+market.toPlainString()+" 最低买 "+paid+"，活动结束普通回收 "+received);
    }
    private static void validateConversions(JsonObject config,java.util.Map<String,JsonObject> items){
        for(String metal:java.util.List.of("copper","iron","gold"))checkConversion(config,items,"minecraft:raw_"+metal,1,"minecraft:"+metal+"_ingot",1);
        checkConversion(config,items,"minecraft:oak_log",1,"minecraft:oak_planks",4);
        checkConversion(config,items,"minecraft:bone",1,"minecraft:bone_meal",3);
        for(String metal:java.util.List.of("copper","iron","gold")){checkConversion(config,items,"minecraft:"+metal+"_ingot",9,"minecraft:"+metal+"_block",1);checkConversion(config,items,"minecraft:"+metal+"_block",1,"minecraft:"+metal+"_ingot",9);}
    }
    private static void checkConversion(JsonObject config,java.util.Map<String,JsonObject> items,String input,int inCount,String output,int outCount){
        JsonObject in=items.get(input),out=items.get(output);if(in==null||out==null||!in.get("enabled").getAsBoolean()||!out.get("enabled").getAsBoolean()||!out.get("sell_enabled").getAsBoolean())return;
        long cheapest=MarketPricing.buy(BigDecimal.valueOf(AccountLedger.number(in,"base")).multiply(new BigDecimal("0.5")),multiplier(config,in,true).min(new BigDecimal("0.95")))*inCount;
        long payout=MarketPricing.sell(MarketPricing.buy(BigDecimal.valueOf(AccountLedger.number(out,"base")).multiply(new BigDecimal("1.5")),BigDecimal.ONE))*outCount;
        if(payout>cheapest)throw new IllegalArgumentException("拒绝已知加工套利链："+input+" → "+output+"；请关闭输出回收或调整价格");
    }
    public static BigDecimal multiplier(JsonObject config,JsonObject product,boolean vip){
        BigDecimal d=BigDecimal.ONE;boolean activity=false;
        if(config.get("activity_enabled").getAsBoolean()){d=d.multiply(MarketPricing.decimal(config.get("activity_discount").getAsString()));activity=true;}
        if(product.get("activity_enabled").getAsBoolean()){d=d.multiply(MarketPricing.decimal(product.get("activity_discount").getAsString()));activity=true;}
        if(vip&&FinanceRules.enabled(config,"vip_discounts"))d=d.multiply(MarketPricing.decimal(AccountLedger.text(config,activity?"vip_activity_discount":"vip_normal_discount")));return d;
    }
}

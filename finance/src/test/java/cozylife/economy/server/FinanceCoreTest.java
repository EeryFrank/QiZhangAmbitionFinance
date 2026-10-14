package cozylife.economy.server;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FinanceCoreTest {
    @TempDir Path temporary;
    private static final long NOW=1791980000000L;
    private static BigDecimal d(String n){return new BigDecimal(n);}
    @Test void independentRecycleKeepsMandatorySpreadAtEveryDiscountAndBoundary(){
        for(long base:new long[]{2,4,16,100,2000})for(String factor:new String[]{"0.5","1","1.5"})for(String discount:new String[]{"1","0.95","0.49","0.001"}){
            BigDecimal market=BigDecimal.valueOf(base).multiply(d(factor));long buy=MarketPricing.buy(market,d(discount)),sell=MarketPricing.independentSell(base,Math.max(1,base/5),market,buy);
            if(buy==1)assertEquals(0,sell);else {assertTrue(sell>=1);assertTrue(sell<=buy-1);}
        }
        assertEquals(20,MarketPricing.independentSell(100,20,d("100"),100));
        assertEquals(30,MarketPricing.independentSell(100,20,d("150"),150));
    }
    @Test void batchUsesEachPriceAndSharedMarketAndContinuesAtBounds(){
        JsonObject config=MarketCatalog.defaults();config.addProperty("step","0.01");
        var bought=FinanceRules.total(config,100,20,d("100"),BigDecimal.ONE,16,true);
        assertEquals(1720,bought.total());assertEquals(0,d("116").compareTo(bought.after()));
        var sold=FinanceRules.total(config,100,20,bought.after(),BigDecimal.ONE,16,false);
        assertTrue(sold.total()>16*20);assertEquals(0,d("100").compareTo(sold.after()));
        assertEquals(9600,FinanceRules.total(config,100,20,d("150"),BigDecimal.ONE,64,true).total());
        assertEquals(640,FinanceRules.total(config,100,20,d("50"),BigDecimal.ONE,64,false).total());
    }
    @Test void switchesAndZeroWeeklyRatioPreservePriceExactly(){
        JsonObject config=MarketCatalog.defaults();FinanceRules.set(config,"dynamic_pricing","false");
        var total=FinanceRules.total(config,100,20,d("110"),BigDecimal.ONE,64,true);assertEquals(7040,total.total());assertEquals(0,d("110").compareTo(total.after()));
        FinanceRules.set(config,"weekly_reversion","false");assertEquals(0,d("150").compareTo(FinanceRules.weekly(config,100,d("150"),3)));
        FinanceRules.set(config,"weekly_reversion","true");assertEquals(0,d("140").compareTo(FinanceRules.weekly(config,100,d("150"),1)));
        FinanceRules.set(config,"weekly_reversion_ratio","0");assertEquals(d("100.0000000001"),FinanceRules.weekly(config,100,d("100.0000000001"),1));
        assertThrows(IllegalArgumentException.class,()->FinanceRules.set(config,"weekly_reversion_ratio","1.1"));
    }
    @Test void profileThresholdsAndSwitchesDoNotResetAccountsOrMarket(){
        assertEquals("small",FinanceProfiles.recommended(19));assertEquals("medium",FinanceProfiles.recommended(20));assertEquals("medium",FinanceProfiles.recommended(49));assertEquals("large",FinanceProfiles.recommended(50));
        JsonObject state=MarketCatalog.initial(NOW);UUID player=UUID.randomUUID();JsonObject a=MarketLedger.account(state,player);a.addProperty("balance",98765);a.addProperty("vip_until",NOW+99999);a.getAsJsonObject("daily").addProperty("minecraft:oak_log",64);
        JsonObject beforeAccounts=state.getAsJsonObject("accounts").deepCopy(),beforeMarket=state.getAsJsonObject("market").deepCopy();
        for(String profile:new String[]{"large","small","medium"})FinanceProfiles.apply(state.getAsJsonObject("config"),profile);
        assertEquals(beforeAccounts,state.get("accounts"));assertEquals(beforeMarket,state.get("market"));assertEquals("0.00005",state.getAsJsonObject("config").get("step").getAsString());
        assertThrows(IllegalArgumentException.class,()->FinanceProfiles.get("unknown"));
    }
    @Test void idempotentCreditAndTransferSurviveRestartAndRejectConflictingRetry()throws Exception{
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),credit=UUID.randomUUID(),transfer=UUID.randomUUID();
        try(MarketLedger ledger=new MarketLedger(temporary,MarketCatalog.initial(NOW))){JsonObject state=ledger.state();ApiTransactions.apply(state,credit,a,null,500,"credit","test",NOW);ledger.replace(state,"test_credit");state=ledger.state();ApiTransactions.apply(state,transfer,a,b,125,"transfer","test",NOW);ledger.replace(state,"test_transfer");}
        try(MarketLedger ledger=new MarketLedger(temporary,MarketCatalog.initial(NOW))){JsonObject state=ledger.state();assertTrue(ApiTransactions.apply(state,transfer,a,b,125,"transfer","test",NOW).get("replay").getAsBoolean());assertEquals(375,MarketLedger.account(state,a).get("balance").getAsLong());assertEquals(125,MarketLedger.account(state,b).get("balance").getAsLong());assertThrows(IllegalArgumentException.class,()->ApiTransactions.apply(state,transfer,a,b,126,"transfer","test",NOW));assertThrows(IllegalArgumentException.class,()->ApiTransactions.apply(state,UUID.randomUUID(),a,b,999,"transfer","test",NOW));assertEquals(375,MarketLedger.account(ledger.state(),a).get("balance").getAsLong());}
    }
    @Test void coinConversionsAreNotIssueAndAdminMailDoesNotMakePlayerActive(){
        JsonObject state=MarketCatalog.initial(NOW);UUID player=UUID.randomUUID();JsonObject detail=new JsonObject();detail.addProperty("total",10000);detail.addProperty("item","cozylife_economy:gold_coin");
        EconomyMetrics.record(state,player,"admin_coin_mail",detail,NOW);assertFalse(MarketLedger.account(state,player).has("last_activity"));
        EconomyMetrics.record(state,player,"deposit",detail,NOW);EconomyMetrics.record(state,player,"withdraw",detail,NOW);
        JsonObject today=state.getAsJsonObject("economic_metrics").getAsJsonObject("days").getAsJsonObject(MarketPricing.day(NOW));assertEquals(0,today.get("recycle_issued").getAsLong());assertEquals(10000,today.get("coin_deposit").getAsLong());assertEquals(10000,today.get("coin_withdraw").getAsLong());
    }
    @Test void extremeExponentCannotAllocateAnEnormousPlainString(){
        assertThrows(IllegalArgumentException.class,()->MarketPricing.decimal("1e+1000000000"));assertThrows(IllegalArgumentException.class,()->MarketPricing.decimal("1e-1000000000"));assertEquals(d("0.000000000001"),MarketPricing.decimal("1e-12"));
    }
    @Test void discountRulesUseActivitySpecificVipRate(){
        JsonObject config=MarketCatalog.defaults(),product=config.getAsJsonArray("products").get(0).getAsJsonObject();assertEquals(0,d("0.95").compareTo(MarketCatalog.multiplier(config,product,true)));
        config.addProperty("activity_enabled",true);config.addProperty("activity_discount","0.9");assertEquals(0,d("0.882").compareTo(MarketCatalog.multiplier(config,product,true)));
        FinanceRules.set(config,"vip_discounts","false");assertEquals(0,d("0.9").compareTo(MarketCatalog.multiplier(config,product,true)));
    }
    @Test void aliasesCannotBypassSharedRecycleBaseOrQuota(){
        JsonObject config=MarketCatalog.defaults(),alias=config.getAsJsonArray("products").get(0).getAsJsonObject().deepCopy();alias.addProperty("id","#999");config.getAsJsonArray("products").add(alias);MarketCatalog.validate(config);alias.addProperty("sell_base",4);assertThrows(IllegalArgumentException.class,()->MarketCatalog.validate(config));
    }
    @Test void pendingInventoryReceiptCommitsOnceOrAbortsWithoutMinting()throws Exception{
        UUID player=UUID.randomUUID();String tx;
        try(MarketLedger ledger=new MarketLedger(temporary,MarketCatalog.initial(NOW))){JsonObject after=ledger.state();MarketLedger.account(after,player).addProperty("balance",100);tx=ledger.prepare(player,after,"test",new JsonObject());}
        try(MarketLedger ledger=new MarketLedger(temporary,MarketCatalog.initial(NOW))){ledger.recover(id->tx);assertEquals(100,MarketLedger.account(ledger.state(),player).get("balance").getAsLong());ledger.recover(id->tx);assertEquals(100,MarketLedger.account(ledger.state(),player).get("balance").getAsLong());JsonObject after=ledger.state();MarketLedger.account(after,player).addProperty("balance",200);ledger.prepare(player,after,"test",new JsonObject());ledger.recover(id->tx);assertEquals(100,MarketLedger.account(ledger.state(),player).get("balance").getAsLong());}
    }
}

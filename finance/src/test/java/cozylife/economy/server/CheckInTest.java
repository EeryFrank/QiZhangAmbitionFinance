package cozylife.economy.server;

import com.google.gson.*;
import java.math.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckInTest {
    @TempDir Path temporary;
    private static long at(String day){return LocalDate.parse(day).atTime(12,0).atZone(CheckInRules.ZONE).toInstant().toEpochMilli();}
    private static JsonObject account(String first){JsonObject state=MarketCatalog.initial(at(first));JsonObject a=MarketLedger.account(state,UUID.randomUUID());CheckInRules.register(a,null,at(first));return a;}
    private static JsonObject config(){return MarketCatalog.defaults();}
    private static JsonObject signed(JsonObject a,String day){return CheckInRules.settle(a,config(),CheckInRules.date(day),at(day)).account();}
    private static long n(JsonObject a,String k){return a.get(k).getAsLong();}
    private static JsonObject c(JsonObject a){return a.getAsJsonObject("checkin");}
    @Test void fourPurchasesAddTwentyEightDaysAndFourPermanentCredits(){
        long now=at("2026-10-14");JsonObject a=account("2026-10-14");a.addProperty("balance",1_000_000);
        for(int i=1;i<=4;i++){a=VipMembership.purchase(a,now);assertEquals(now+i*VipMembership.DURATION,n(a,"vip_until"));assertEquals(i,n(c(a),"makeup_credits"));}
        JsonObject finalAccount=a;assertThrows(IllegalArgumentException.class,()->VipMembership.purchase(finalAccount,now));assertEquals(1_000_000-4*77777,n(a,"balance"));assertEquals(4,n(c(a),"makeup_credits"));
        assertEquals(4,n(CheckInRules.summary(a,now+29*VipMembership.DAY),"makeup_credits"));
    }
    @Test void twentyOneDaysIsInclusiveButOneMillisecondMoreIsRejected(){
        long now=at("2026-10-14");JsonObject a=account("2026-10-14");a.addProperty("balance",77777);a.addProperty("vip_until",now+21*VipMembership.DAY);
        assertTrue(VipMembership.canRenew(a,now));assertEquals(now+28*VipMembership.DAY,n(VipMembership.purchase(a,now),"vip_until"));a.addProperty("vip_until",now+21*VipMembership.DAY+1);assertFalse(VipMembership.canRenew(a,now));
    }
    @Test void failedVipPurchaseDoesNotDebitOrGrantCredit(){
        JsonObject a=account("2026-10-14"),before=a.deepCopy();a.addProperty("balance",77776);before=a.deepCopy();assertThrows(IllegalArgumentException.class,()->VipMembership.purchase(a,at("2026-10-14")));assertEquals(before,a);
        a.addProperty("balance",77777);c(a).addProperty("makeup_credits",Long.MAX_VALUE);JsonObject old=a.deepCopy();assertThrows(ArithmeticException.class,()->VipMembership.purchase(a,at("2026-10-14")));assertEquals(old,a);
    }
    @Test void compoundRewardMatchesExactDecimalAndFloorsOnlyAtTheEnd(){
        for(int streak:new int[]{1,2,3,7,14,30,60,100,365,1000})for(boolean vip:new boolean[]{false,true}){
            BigDecimal exact=new BigDecimal("100").multiply(new BigDecimal("1.01").pow(streak-1));if(vip)exact=exact.multiply(new BigDecimal("1.05"));
            assertEquals(exact.setScale(0,RoundingMode.FLOOR).longValueExact(),CheckInRules.reward(config(),streak,vip));
        }
        assertEquals(102,CheckInRules.reward(config(),3,false));assertEquals(107,CheckInRules.reward(config(),3,true));
        assertEquals(106,CheckInRules.reward(config(),7,false));
    }
    @Test void exactOneMultiplierSupportsVeryLongStreakWithoutAllocatingHugeNumbers(){
        JsonObject config=config();config.getAsJsonObject("checkin").addProperty("growth_factor","1");assertEquals(100,CheckInRules.reward(config,3_000_000,false));
        config.getAsJsonObject("checkin").addProperty("growth_factor","1.000000000001");assertEquals(100,CheckInRules.reward(config,3_000_000,false));
    }
    @Test void finalFloorCanEqualTheLargestValidBalance(){JsonObject cfg=config();cfg.getAsJsonObject("checkin").addProperty("base_reward",1);cfg.getAsJsonObject("checkin").addProperty("growth_factor",Long.toString(Long.MAX_VALUE));assertEquals(Long.MAX_VALUE,CheckInRules.reward(cfg,2,false));}
    @Test void rewardAndBalanceOverflowRefuseWithoutConsumingCredits(){
        assertThrows(IllegalArgumentException.class,()->CheckInRules.reward(config(),10000,false));
        JsonObject a=account("2026-10-13");a.addProperty("balance",Long.MAX_VALUE);c(a).addProperty("makeup_credits",1);JsonObject before=a.deepCopy();
        assertThrows(IllegalArgumentException.class,()->CheckInRules.settle(a,config(),CheckInRules.date("2026-10-13"),at("2026-10-14")));assertEquals(before,a);
    }
    @Test void todayAndCurrentStreakUsesYesterdayUntilTodayIsClaimed(){
        JsonObject a=signed(account("2026-10-12"),"2026-10-12");a=signed(a,"2026-10-13");assertEquals(2,n(CheckInRules.summary(a,at("2026-10-14")),"current_streak"));assertFalse(CheckInRules.summary(a,at("2026-10-14")).get("signed_today").getAsBoolean());
        a=signed(a,"2026-10-14");assertEquals(3,n(CheckInRules.summary(a,at("2026-10-14")),"current_streak"));assertEquals(0,n(CheckInRules.summary(a,at("2026-10-16")),"current_streak"));
    }
    @Test void missedDayUsesAllPriorDaysAndBridgesFollowingDatesWithoutRepricingThem(){
        JsonObject a=account("2026-10-01");for(String day:List.of("2026-10-01","2026-10-02","2026-10-04","2026-10-05","2026-10-06","2026-10-07"))a=signed(a,day);
        JsonObject originalLater=c(a).getAsJsonObject("signed").getAsJsonObject("2026-10-07").deepCopy();c(a).addProperty("makeup_credits",1);
        var result=CheckInRules.settle(a,config(),CheckInRules.date("2026-10-03"),at("2026-10-08"));assertEquals(3,result.streak());assertEquals(7,result.longest());assertEquals(102,result.reward());assertTrue(result.makeup());assertEquals(0,n(c(result.account()),"makeup_credits"));assertEquals(originalLater,c(result.account()).getAsJsonObject("signed").get("2026-10-07"));assertEquals(1,result.milestones().size());assertEquals(1,n(result.account().getAsJsonArray("mail").get(0).getAsJsonObject(),"count"));
    }
    @Test void bridgingCanUnlockMultipleMilestonesOnlyOnce(){
        JsonObject a=account("2026-01-01");for(int day=1;day<=30;day++)if(day!=15)a=signed(a,LocalDate.of(2026,1,day).toString());
        c(a).addProperty("makeup_credits",2);var result=CheckInRules.settle(a,config(),CheckInRules.date("2026-01-15"),at("2026-02-01"));assertEquals(15,result.streak());assertEquals(30,result.longest());assertEquals(1,result.milestones().size());assertEquals(30,n(result.milestones().get(0).getAsJsonObject(),"days"));assertEquals(14,n(result.account().getAsJsonArray("mail").get(0).getAsJsonObject(),"count"));
        JsonObject claimed=c(result.account()).getAsJsonObject("claimed_milestones").deepCopy();JsonObject next=signed(result.account(),"2026-02-01");assertEquals(claimed,c(next).get("claimed_milestones"));
    }
    @Test void makeupVipMultiplierUsesClaimTimeNotTargetDay(){
        JsonObject a=account("2026-10-01");c(a).addProperty("makeup_credits",2);a.addProperty("vip_until",at("2026-10-14")+1);var first=CheckInRules.settle(a,config(),CheckInRules.date("2026-10-01"),at("2026-10-14"));assertEquals(105,first.reward());assertTrue(first.vip());
        var second=CheckInRules.settle(first.account(),config(),CheckInRules.date("2026-10-02"),at("2026-10-15"));assertEquals(101,second.reward());assertFalse(second.vip());assertTrue(c(second.account()).getAsJsonObject("signed").getAsJsonObject("2026-10-01").get("vip_bonus_applied").getAsBoolean());
    }
    @Test void oneBridgeClaimsSixtyAndHundredTogetherAndNeverRepeats(){
        LocalDate start=LocalDate.of(2026,1,1);JsonObject a=account(start.toString());for(int i=0;i<100;i++)if(i!=49)a=signed(a,start.plusDays(i).toString());
        assertEquals(3,c(a).getAsJsonObject("claimed_milestones").size());c(a).addProperty("makeup_credits",1);var result=CheckInRules.settle(a,config(),start.plusDays(49),at(start.plusDays(100).toString()));assertEquals(50,result.streak());assertEquals(100,result.longest());assertEquals(2,result.milestones().size());assertEquals(60,n(result.milestones().get(0).getAsJsonObject(),"days"));assertEquals(100,n(result.milestones().get(1).getAsJsonObject(),"days"));assertEquals(84,n(result.account().getAsJsonArray("mail").get(0).getAsJsonObject(),"count"));
        JsonObject next=signed(result.account(),start.plusDays(100).toString());assertEquals(5,c(next).getAsJsonObject("claimed_milestones").size());assertEquals(84,n(next.getAsJsonArray("mail").get(0).getAsJsonObject(),"count"));
    }
    @Test void strictDatesLeapYearsAndBeijingBoundary(){
        assertEquals(LocalDate.of(2024,2,29),CheckInRules.date("2024-02-29"));for(String invalid:List.of("2026-02-29","2024-2-29","0000-01-01","2026-13-01","2026-10-14T00:00:00","2026-10-14 "))assertThrows(IllegalArgumentException.class,()->CheckInRules.date(invalid));
        assertEquals(LocalDate.of(2026,10,15),CheckInRules.today(Instant.parse("2026-10-14T16:00:00Z").toEpochMilli()));
        JsonObject a=signed(account("2024-02-28"),"2024-02-28");a=signed(a,"2024-03-01");c(a).addProperty("makeup_credits",1);var leap=CheckInRules.settle(a,config(),CheckInRules.date("2024-02-29"),at("2024-03-02"));assertEquals(2,leap.streak());assertEquals(3,leap.longest());
    }
    @Test void futurePreregistrationDuplicateAndNoCreditAreRejected(){
        JsonObject a=account("2026-10-13");for(String day:List.of("2026-10-12","2026-10-13","2026-10-15"))assertThrows(IllegalArgumentException.class,()->CheckInRules.settle(a,config(),CheckInRules.date(day),at("2026-10-14")));
        JsonObject claimed=signed(a,"2026-10-14");assertThrows(IllegalArgumentException.class,()->CheckInRules.settle(claimed,config(),CheckInRules.date("2026-10-14"),at("2026-10-14")));
    }
    @Test void registrationDoesNotUseLastSeenOrOfflineAccountCreation(){
        JsonObject state=MarketCatalog.initial(at("2026-10-01")),a=MarketLedger.account(state,UUID.randomUUID());a.addProperty("balance",900);JsonObject known=new JsonObject();known.addProperty("last_seen",at("2026-10-01"));assertFalse(a.has("checkin"));CheckInRules.register(a,known,at("2026-10-14"));assertEquals("2026-10-14",c(a).get("registered_date").getAsString());assertEquals(900,n(a,"balance"));assertFalse(CheckInRules.register(a,known,at("2026-10-15")));assertEquals("2026-10-14",c(a).get("registered_date").getAsString());
    }
    @Test void verifiableFirstJoinCanMigrateButFutureInvalidValueCannot(){
        JsonObject state=MarketCatalog.initial(at("2026-10-01")),a=MarketLedger.account(state,UUID.randomUUID()),known=new JsonObject();known.addProperty("first_join_at",at("2026-10-01"));CheckInRules.register(a,known,at("2026-10-14"));assertEquals("verified_first_join_at",c(a).get("registration_source").getAsString());assertEquals("2026-10-01",c(a).get("registered_date").getAsString());
        JsonObject b=MarketLedger.account(state,UUID.randomUUID());known.addProperty("first_join_at",at("2026-10-15"));CheckInRules.register(b,known,at("2026-10-14"));assertEquals("2026-10-14",c(b).get("registered_date").getAsString());
    }
    @Test void calendarMarksUnavailableDatesWithoutClaimingThem(){
        JsonObject a=signed(account("2026-10-12"),"2026-10-12");JsonObject cal=CheckInRules.calendar(a,config(),YearMonth.of(2026,10),at("2026-10-14"));assertEquals(31,cal.getAsJsonArray("days").size());
        String[] statuses={"before_registration","signed","missed","today","future"};int[] indices={10,11,12,13,14};for(int i=0;i<indices.length;i++)assertEquals(statuses[i],cal.getAsJsonArray("days").get(indices[i]).getAsJsonObject().get("status").getAsString());
        assertFalse(cal.getAsJsonArray("days").get(12).getAsJsonObject().get("can_prepare").getAsBoolean());c(a).addProperty("makeup_credits",1);assertTrue(CheckInRules.calendar(a,config(),YearMonth.of(2026,10),at("2026-10-14")).getAsJsonArray("days").get(12).getAsJsonObject().get("can_prepare").getAsBoolean());
    }
    @Test void quoteConnectionAndOneUseReplayAreStrict(){
        CheckInOffers<Object> offers=new CheckInOffers<>();UUID player=UUID.randomUUID();Object connection=new Object();JsonObject a=account("2026-10-14");long now=at("2026-10-14");var issued=offers.issue(player,connection,CheckInRules.date("2026-10-14"),a,config(),now);
        assertThrows(IllegalArgumentException.class,()->offers.consume(UUID.randomUUID(),connection,issued.token()));assertThrows(IllegalArgumentException.class,()->offers.consume(player,new Object(),issued.token()));var taken=offers.consume(player,connection,issued.token());offers.validate(taken,a,config(),now+1);assertThrows(IllegalArgumentException.class,()->offers.consume(player,connection,issued.token()));
    }
    @Test void quoteRevalidatesAccountConfigExpiryBeijingDayAndVipExpiration(){
        CheckInOffers<Object> offers=new CheckInOffers<>();UUID player=UUID.randomUUID();Object connection=new Object();JsonObject a=account("2026-10-14"),config=config();long now=at("2026-10-14");a.addProperty("vip_until",now+10);var q=offers.issue(player,connection,CheckInRules.date("2026-10-14"),a,config,now);
        assertThrows(IllegalArgumentException.class,()->offers.validate(q,a,config,now+10));assertThrows(IllegalArgumentException.class,()->offers.validate(q,a,config,now+30000));JsonObject changed=a.deepCopy();changed.addProperty("balance",1);assertThrows(IllegalArgumentException.class,()->offers.validate(q,changed,config,now));JsonObject cfg=config.deepCopy();cfg.getAsJsonObject("checkin").addProperty("base_reward",101);assertThrows(IllegalArgumentException.class,()->offers.validate(q,a,cfg,now));
        long midnight=Instant.parse("2026-10-14T16:00:00Z").toEpochMilli();var nearMidnight=offers.issue(player,connection,CheckInRules.date("2026-10-14"),a,config,midnight-1000);assertThrows(IllegalArgumentException.class,()->offers.validate(nearMidnight,a,config,midnight));
    }
    @Test void pendingCheckinAndVipRemainExactlyOnceAcrossRestart()throws Exception{
        UUID id=UUID.randomUUID();String tx;long now=at("2026-10-14");JsonObject initial=MarketCatalog.initial(now);JsonObject a=MarketLedger.account(initial,id);CheckInRules.register(a,null,now);a.addProperty("balance",77777);initial.getAsJsonObject("accounts").add(id.toString(),VipMembership.purchase(a,now));
        try(MarketLedger ledger=new MarketLedger(temporary,initial)){JsonObject state=ledger.state();state.getAsJsonObject("accounts").add(id.toString(),CheckInRules.settle(MarketLedger.account(state,id),config(),CheckInRules.date("2026-10-14"),now).account());tx=ledger.prepare(id,state,"checkin",new JsonObject());}
        try(MarketLedger ledger=new MarketLedger(temporary,initial)){ledger.recover(uuid->tx);a=MarketLedger.account(ledger.state(),id);assertEquals(105,n(a,"balance"));assertEquals(1,n(c(a),"makeup_credits"));assertEquals(now+7*VipMembership.DAY,n(a,"vip_until"));assertEquals(1,c(a).getAsJsonObject("signed").size());ledger.commit(tx);JsonObject settled=a;assertThrows(IllegalArgumentException.class,()->CheckInRules.settle(settled,config(),CheckInRules.date("2026-10-14"),now));MarketLedger.validate(ledger.state());}
    }
    @Test void pendingAbortedCheckinDoesNotIssueRewardOrConsumeCredit()throws Exception{
        UUID id=UUID.randomUUID();JsonObject initial=MarketCatalog.initial(at("2026-10-13"));JsonObject a=MarketLedger.account(initial,id);CheckInRules.register(a,null,at("2026-10-13"));c(a).addProperty("makeup_credits",1);
        try(MarketLedger ledger=new MarketLedger(temporary,initial)){JsonObject state=ledger.state();state.getAsJsonObject("accounts").add(id.toString(),CheckInRules.settle(MarketLedger.account(state,id),config(),CheckInRules.date("2026-10-13"),at("2026-10-14")).account());ledger.prepare(id,state,"checkin",new JsonObject());}
        try(MarketLedger ledger=new MarketLedger(temporary,initial)){ledger.recover(uuid->"");a=MarketLedger.account(ledger.state(),id);assertEquals(0,n(a,"balance"));assertEquals(1,n(c(a),"makeup_credits"));assertEquals(0,c(a).getAsJsonObject("signed").size());}
    }
    @Test void milestoneMailCapacityFailureDoesNotLoseRewardOrCredit(){
        JsonObject a=account("2026-10-01");for(int day=1;day<=6;day++)a=signed(a,LocalDate.of(2026,10,day).toString());c(a).addProperty("makeup_credits",1);JsonObject mail=new JsonObject();mail.addProperty("id",UUID.randomUUID().toString());mail.addProperty("item",CheckInRules.SILVER);mail.addProperty("count",1_000_000);a.getAsJsonArray("mail").add(mail);JsonObject before=a.deepCopy(),target=a;assertThrows(IllegalArgumentException.class,()->CheckInRules.settle(target,config(),CheckInRules.date("2026-10-07"),at("2026-10-08")));assertEquals(before,a);
    }
    @Test void legacyAccountsRemainValidAndCheckinConfigurationIsManaged(){
        JsonObject state=MarketCatalog.initial(at("2026-10-14"));MarketLedger.account(state,UUID.randomUUID());state.getAsJsonObject("config").remove("checkin");MarketLedger.validate(state);assertEquals(100,CheckInRules.reward(state.getAsJsonObject("config"),1,false));JsonObject cfg=config();cfg.getAsJsonObject("checkin").addProperty("growth_factor","0.9");assertThrows(IllegalArgumentException.class,()->MarketCatalog.validate(cfg));cfg.getAsJsonObject("checkin").addProperty("growth_factor","1.01");cfg.getAsJsonObject("checkin").addProperty("enabled",false);assertThrows(IllegalArgumentException.class,()->CheckInRules.settle(account("2026-10-14"),cfg,CheckInRules.date("2026-10-14"),at("2026-10-14")));
    }
    @Test void exactFungusExceptionsDoNotAllowOtherWoodMaterials(){assertFalse(MarketCatalog.excludedByOwner("minecraft:crimson_fungus"));assertFalse(MarketCatalog.excludedByOwner("minecraft:warped_fungus"));assertTrue(MarketCatalog.excludedByOwner("minecraft:crimson_planks"));assertTrue(MarketCatalog.excludedByOwner("minecraft:warped_door"));}
}

package cozylife.economy.server;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.*;

/** Main-thread, one-use confirmation tickets bound to an exact live connection and state. */
public final class CheckInOffers<C> {
    public record Offer<C>(String token,UUID player,C connection,LocalDate date,LocalDate issuedDay,
                           JsonObject account,JsonObject settings,boolean vip,long expires){}
    private final Map<UUID,Offer<C>> offers=new HashMap<>();
    public Offer<C> issue(UUID player,C connection,LocalDate date,JsonObject account,JsonObject config,long now){
        Offer<C> offer=new Offer<>(UUID.randomUUID().toString(),player,connection,date,CheckInRules.today(now),account.deepCopy(),CheckInRules.settings(config).deepCopy(),AccountLedger.number(account,"vip_until")>now,Math.addExact(now,30_000));offers.put(player,offer);return offer;
    }
    public Offer<C> consume(UUID player,C connection,String token){
        Offer<C> offer=offers.get(player);if(offer==null||offer.connection()!=connection||!offer.token().equals(token))throw new IllegalArgumentException("签到确认已失效或不属于当前连接，请重新选择日期");offers.remove(player);return offer;
    }
    public void validate(Offer<C> offer,JsonObject account,JsonObject config,long now){
        if(now>=offer.expires()||!offer.issuedDay().equals(CheckInRules.today(now))||!offer.account().equals(account)||!offer.settings().equals(CheckInRules.settings(config))||offer.vip()!=(AccountLedger.number(account,"vip_until")>now))throw new IllegalArgumentException("签到日期、账户或奖励配置已变化，请重新选择日期并确认");
    }
    public void clear(UUID player){offers.remove(player);}
    public void clear(){offers.clear();}
}

package cozylife.economy.server;

import com.google.gson.JsonObject;

/** Exact-duration renewal policy; makeup credits never expire with membership. */
public final class VipMembership {
    public static final long COST=77777L, DAY=86_400_000L, DURATION=7*DAY, RENEW_AT=21*DAY;
    private VipMembership(){}
    public static long remaining(JsonObject account,long now){return Math.max(0,AccountLedger.number(account,"vip_until")-now);}
    public static boolean canRenew(JsonObject account,long now){return remaining(account,now)<=RENEW_AT;}
    /** Returns a copy: a failure cannot partly debit the supplied account. */
    public static JsonObject purchase(JsonObject account,long now){
        if(now<0)throw new IllegalArgumentException("服务器时间无效");
        if(!canRenew(account,now))throw new IllegalArgumentException("VIP 剩余时间超过 21 天，暂不能续期；最多可累计 28 天");
        JsonObject next=account.deepCopy();
        next.addProperty("balance",MoneyMath.debit(AccountLedger.number(next,"balance"),COST));
        next.addProperty("vip_until",Math.addExact(Math.max(now,AccountLedger.number(next,"vip_until")),DURATION));
        JsonObject checkin=CheckInRules.requireRegistered(next);
        checkin.addProperty("makeup_credits",Math.incrementExact(AccountLedger.number(checkin,"makeup_credits")));
        return next;
    }
}

package cozylife.economy.server;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** Exact decimal market mathematics; only final per-item payable amounts are rounded upward. */
public final class MarketPricing {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal HALF = new BigDecimal("0.5"), MAX = new BigDecimal("1.5");
    public record Total(long total, BigDecimal after) {}
    private MarketPricing() {}

    public static BigDecimal decimal(String value) {
        BigDecimal d = new BigDecimal(value);
        if (d.scale() > 12 || d.precision() > 24 || (long)d.precision() - (long)d.scale() > 24) throw new IllegalArgumentException("小数精度或范围过大");
        return d;
    }
    public static void validateDiscount(BigDecimal discount) {
        if (discount.signum() <= 0 || discount.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("折扣必须大于 0 且不超过 1");
    }
    public static void validateStep(BigDecimal step) {
        if (step.signum() < 0 || step.compareTo(new BigDecimal("0.01")) > 0)
            throw new IllegalArgumentException("步长比例必须在 0 至 0.01 之间");
    }
    public static long buy(BigDecimal market, BigDecimal multiplier) {
        validateDiscount(multiplier);
        return Math.max(1L, market.multiply(multiplier).setScale(0, RoundingMode.CEILING).longValueExact());
    }
    public static long sell(long actualBuy) {
        if (actualBuy <= 1) return 0;
        return Math.min(actualBuy - 1, BigDecimal.valueOf(actualBuy).multiply(new BigDecimal("0.8"))
                .setScale(0, RoundingMode.CEILING).longValueExact());
    }
    public static Total total(long base, BigDecimal market, BigDecimal step, BigDecimal multiplier, int count, boolean buy) {
        if (base < 1 || base > 1_000_000_000L) throw new IllegalArgumentException("官方价范围为 1 至 1000000000");
        if (count < 1 || count > 64) throw new IllegalArgumentException("交易数量无效");
        validateStep(step); validateDiscount(multiplier);
        BigDecimal official = BigDecimal.valueOf(base), min = official.multiply(HALF), max = official.multiply(MAX);
        BigDecimal delta = official.multiply(step), current = market.max(min).min(max);
        long total = 0;
        for (int i=0;i<count;i++) {
            long price = buy(current, multiplier);
            if (!buy) { price = sell(price); if (price == 0) throw new IllegalArgumentException("当前购买价为 1，该物品不能回收"); }
            total = Math.addExact(total, price);
            current = (buy ? current.add(delta) : current.subtract(delta)).max(min).min(max);
        }
        return new Total(total, current);
    }
    public static long independentSell(long base,long sellBase,BigDecimal market,long actualBuy) {
        if(base<1||sellBase<0||sellBase>=base)throw new IllegalArgumentException("回收基准不合法");
        if(sellBase==0||actualBuy<=1)return 0;
        long scaled=BigDecimal.valueOf(sellBase).multiply(market).divide(BigDecimal.valueOf(base),0,RoundingMode.CEILING).longValueExact();
        return Math.min(actualBuy-1,scaled);
    }
    public static Total totalIndependent(long base,long sellBase,BigDecimal market,BigDecimal step,BigDecimal multiplier,int count,boolean buying) {
        if(buying)return total(base,market,step,multiplier,count,true);
        if(base<1||base>1_000_000_000L||count<1||count>64)throw new IllegalArgumentException("回收价格或数量无效");
        validateStep(step);validateDiscount(multiplier);
        BigDecimal official=BigDecimal.valueOf(base),min=official.multiply(HALF),max=official.multiply(MAX),current=market.max(min).min(max),delta=official.multiply(step);
        long sum=0;for(int i=0;i<count;i++){long unit=independentSell(base,sellBase,current,buy(current,multiplier));if(unit==0)throw new IllegalArgumentException("当前价格不可回收");sum=Math.addExact(sum,unit);current=current.subtract(delta).max(min).min(max);}
        return new Total(sum,current);
    }
    public static BigDecimal weekly(long base, BigDecimal market, long elapsedWeeks) {
        if (elapsedWeeks < 0 || elapsedWeeks > 5200) throw new IllegalArgumentException("周次跨度异常");
        BigDecimal official = BigDecimal.valueOf(base), current = market;
        for (long i=0;i<elapsedWeeks;i++) current = official.add(current.subtract(official).multiply(new BigDecimal("0.8")))
                .setScale(8, RoundingMode.HALF_EVEN);
        return current.max(official.multiply(HALF)).min(official.multiply(MAX));
    }
    public static String day(long millis) { return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().toString(); }
    public static long monday(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }
    public static long weeksSince(long lastMonday, long now) {
        long current = monday(now);
        return Math.max(0, (current-lastMonday)/7);
    }
}

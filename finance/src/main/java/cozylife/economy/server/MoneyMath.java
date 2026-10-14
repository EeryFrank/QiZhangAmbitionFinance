package cozylife.economy.server;

/** All values are integral copper units. No client-supplied price enters this class. */
public final class MoneyMath {
    private MoneyMath() {}

    public static int count(int count) {
        if (count < 1 || count > 64) throw new IllegalArgumentException("数量必须在 1 到 64 之间");
        return count;
    }

    public static long coinValue(String denomination) {
        return switch (denomination) {
            case "copper" -> 1L;
            case "silver" -> 100L;
            case "gold" -> 10_000L;
            default -> throw new IllegalArgumentException("没有这种货币");
        };
    }

    public static long total(long unitPrice, int count) {
        if (unitPrice < 0) throw new IllegalArgumentException("价格不能为负数");
        return Math.multiplyExact(unitPrice, count(count));
    }

    public static long credit(long balance, long amount) {
        if (balance < 0 || amount < 0) throw new IllegalArgumentException("余额或交易金额无效");
        return Math.addExact(balance, amount);
    }

    public static long debit(long balance, long amount) {
        if (balance < 0 || amount < 0) throw new IllegalArgumentException("余额或交易金额无效");
        if (balance < amount) throw new IllegalArgumentException("余额不足");
        return balance - amount;
    }
}

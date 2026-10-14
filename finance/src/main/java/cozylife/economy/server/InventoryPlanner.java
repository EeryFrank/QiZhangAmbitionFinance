package cozylife.economy.server;

import java.util.List;

/** Pure, non-mutating inventory capacity checks, independently tested without Minecraft. */
public final class InventoryPlanner {
    private InventoryPlanner() {}

    public record Slot(String item, int count, int max, boolean plain) {
        public Slot {
            if (item == null || count < 0 || max < 1 || count > max)
                throw new IllegalArgumentException("背包堆叠状态异常");
        }
        public boolean empty() { return count == 0; }
    }

    public static int[] add(List<Slot> slots, String item, int count, int max) {
        MoneyMath.count(count);
        if (max < 1 || max > 99) throw new IllegalArgumentException("物品堆叠上限异常");
        int remaining = count;
        int[] delta = new int[slots.size()];
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            Slot s = slots.get(i);
            if (!s.empty() && s.plain && item.equals(s.item)) {
                int n = Math.min(remaining, Math.max(0, Math.min(max, s.max) - s.count));
                delta[i] = n;
                remaining -= n;
            }
        }
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            if (slots.get(i).empty()) {
                int n = Math.min(remaining, max);
                delta[i] = n;
                remaining -= n;
            }
        }
        if (remaining != 0) throw new IllegalArgumentException("背包空间不足，请先整理背包");
        return delta;
    }

    public static int[] remove(List<Slot> slots, String item, int count) {
        int remaining = MoneyMath.count(count);
        int[] delta = new int[slots.size()];
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            Slot s = slots.get(i);
            if (!s.empty() && s.plain && item.equals(s.item)) {
                int n = Math.min(remaining, s.count);
                delta[i] = -n;
                remaining -= n;
            }
        }
        if (remaining != 0)
            throw new IllegalArgumentException("普通物品数量不足；命名、附魔或带自定义数据的物品不会出售");
        return delta;
    }
}

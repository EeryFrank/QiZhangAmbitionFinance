package cozylife.economy.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prices are loaded once at server start. Future regulation belongs in this configuration boundary. */
public final class ShopCatalog {
    public record Product(String id, String item, String name, String category, long buy, long sell) {}
    private final Map<String, Product> products;
    private final int cooldownTicks;

    private ShopCatalog(Map<String, Product> products, int cooldownTicks) {
        this.products = Map.copyOf(products);
        this.cooldownTicks = cooldownTicks;
    }

    public List<Product> products() { return products.values().stream().sorted(java.util.Comparator.comparing(Product::id)).toList(); }
    public Product get(String id) {
        Product p = products.get(id);
        if (p == null) throw new IllegalArgumentException("商品不存在，请重新打开商店");
        return p;
    }
    public int cooldownTicks() { return cooldownTicks; }

    public static ShopCatalog load(Path path) throws IOException {
        if (!Files.exists(path)) AccountLedger.atomicWrite(path, new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(defaults()) + "\n");
        try {
            if (Files.size(path) > 131_072) throw new IOException("商店配置过大");
            return parse(JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException ex) { throw new IOException("商店配置错误，经济系统已保护停用", ex); }
    }

    public static ShopCatalog parse(JsonObject o) {
        if (AccountLedger.number(o, "schema") != 1) throw new IllegalArgumentException("商店配置版本不支持");
        if (!"fixed".equals(AccountLedger.text(o, "pricing_mode")))
            throw new IllegalArgumentException("尚未设置动态调控规则；当前只支持 fixed 固定价格");
        int cooldown = Math.toIntExact(AccountLedger.number(o, "transaction_cooldown_ticks"));
        if (cooldown < 4 || cooldown > 200) throw new IllegalArgumentException("交易冷却必须为 4 至 200 tick");
        JsonArray entries = o.getAsJsonArray("products");
        if (entries.size() < 1 || entries.size() > 128) throw new IllegalArgumentException("商品数量必须为 1 至 128");
        Map<String, Product> products = new LinkedHashMap<>();
        java.util.Set<String> items = new java.util.HashSet<>();
        for (JsonElement entry : entries) {
            JsonObject p = entry.getAsJsonObject();
            String id = AccountLedger.text(p, "id"), item = AccountLedger.text(p, "item");
            String name = AccountLedger.text(p, "name"), category = AccountLedger.text(p, "category");
            long buy = AccountLedger.number(p, "buy"), sell = AccountLedger.number(p, "sell");
            if (!id.matches("[a-z0-9_]{1,48}") || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("商品标识格式错误");
            if (item.startsWith("cozylife_economy:")) throw new IllegalArgumentException("硬币只能通过兑换页按固定面值兑换");
            if (buy < 0 || sell < 0 || buy > 1_000_000_000L || sell > 1_000_000_000L || (buy == 0 && sell == 0))
                throw new IllegalArgumentException("商品价格必须为有效整数；0 表示停用该方向");
            if (buy > 0 && sell > buy) throw new IllegalArgumentException("回收价不得超过购买价");
            if (name.isBlank() || name.length() > 40 || category.isBlank() || category.length() > 16)
                throw new IllegalArgumentException("商品名称或分类长度错误");
            if (products.putIfAbsent(id, new Product(id, item, name, category, buy, sell)) != null || !items.add(item))
                throw new IllegalArgumentException("重复的商品标识或物品：" + id);
        }
        return new ShopCatalog(products, cooldown);
    }

    public static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("schema", 1);
        o.addProperty("pricing_mode", "fixed");
        o.addProperty("transaction_cooldown_ticks", 4);
        o.addProperty("说明", "所有价格以铜币余额计价，单件价；0 表示禁用该方向。修改后重启服务器生效。动态调控待服主确定规则后加入。");
        JsonArray products = new JsonArray();
        add(products, "wheat", "小麦", "田园物产", 8, 2);
        add(products, "carrot", "胡萝卜", "田园物产", 8, 2);
        add(products, "potato", "马铃薯", "田园物产", 8, 2);
        add(products, "beetroot", "甜菜根", "田园物产", 8, 2);
        add(products, "pumpkin", "南瓜", "田园物产", 20, 4);
        add(products, "melon_slice", "西瓜片", "田园物产", 5, 1);
        add(products, "sugar_cane", "甘蔗", "田园物产", 8, 2);
        add(products, "bamboo", "竹子", "田园物产", 4, 1);
        add(products, "cactus", "仙人掌", "田园物产", 8, 2);
        add(products, "sweet_berries", "甜浆果", "田园物产", 8, 2);
        add(products, "cocoa_beans", "可可豆", "田园物产", 12, 3);
        add(products, "apple", "苹果", "田园物产", 16, 4);
        add(products, "wheat_seeds", "小麦种子", "田园物产", 4, 1);
        add(products, "beetroot_seeds", "甜菜种子", "田园物产", 4, 1);
        add(products, "melon_seeds", "西瓜种子", "田园物产", 4, 1);
        add(products, "pumpkin_seeds", "南瓜种子", "田园物产", 4, 1);
        add(products, "cod", "生鳕鱼", "垂钓渔获", 20, 5);
        add(products, "salmon", "生鲑鱼", "垂钓渔获", 24, 6);
        add(products, "tropical_fish", "热带鱼", "垂钓渔获", 40, 10);
        add(products, "pufferfish", "河豚", "垂钓渔获", 40, 10);
        add(products, "ink_sac", "墨囊", "垂钓渔获", 16, 4);
        add(products, "glow_ink_sac", "荧光墨囊", "垂钓渔获", 28, 7);
        add(products, "nautilus_shell", "鹦鹉螺壳", "垂钓渔获", 240, 48);
        add(products, "coal", "煤炭", "矿物材料", 24, 5);
        add(products, "charcoal", "木炭", "矿物材料", 20, 4);
        add(products, "raw_copper", "粗铜", "矿物材料", 20, 4);
        add(products, "copper_ingot", "铜锭", "矿物材料", 30, 6);
        add(products, "raw_iron", "粗铁", "矿物材料", 70, 14);
        add(products, "iron_ingot", "铁锭", "矿物材料", 100, 20);
        add(products, "raw_gold", "粗金", "矿物材料", 350, 70);
        add(products, "gold_ingot", "金锭", "矿物材料", 500, 100);
        add(products, "diamond", "钻石", "矿物材料", 2000, 400);
        add(products, "emerald", "绿宝石", "矿物材料", 1400, 280);
        add(products, "redstone", "红石粉", "矿物材料", 16, 3);
        add(products, "lapis_lazuli", "青金石", "矿物材料", 30, 6);
        add(products, "quartz", "下界石英", "矿物材料", 40, 8);
        add(products, "amethyst_shard", "紫水晶碎片", "矿物材料", 24, 5);
        add(products, "oak_log", "橡木原木", "木石建材", 16, 2);
        add(products, "spruce_log", "云杉原木", "木石建材", 16, 2);
        add(products, "birch_log", "白桦原木", "木石建材", 16, 2);
        add(products, "jungle_log", "丛林原木", "木石建材", 16, 2);
        add(products, "acacia_log", "金合欢原木", "木石建材", 16, 2);
        add(products, "dark_oak_log", "深色橡木原木", "木石建材", 16, 2);
        add(products, "mangrove_log", "红树原木", "木石建材", 16, 2);
        add(products, "cherry_log", "樱花原木", "木石建材", 16, 2);
        add(products, "oak_planks", "橡木木板", "木石建材", 4, 1);
        add(products, "cobblestone", "圆石", "木石建材", 2, 1);
        add(products, "stone", "石头", "木石建材", 4, 1);
        add(products, "sand", "沙子", "木石建材", 4, 1);
        add(products, "gravel", "沙砾", "木石建材", 4, 1);
        add(products, "dirt", "泥土", "木石建材", 2, 1);
        add(products, "clay_ball", "黏土球", "木石建材", 8, 2);
        add(products, "rotten_flesh", "腐肉", "日常杂货", 8, 2);
        add(products, "bone", "骨头", "日常杂货", 12, 3);
        add(products, "string", "线", "日常杂货", 12, 3);
        add(products, "spider_eye", "蜘蛛眼", "日常杂货", 16, 4);
        add(products, "gunpowder", "火药", "日常杂货", 24, 6);
        add(products, "leather", "皮革", "日常杂货", 28, 7);
        add(products, "feather", "羽毛", "日常杂货", 12, 3);
        add(products, "slime_ball", "黏液球", "日常杂货", 40, 8);
        add(products, "ender_pearl", "末影珍珠", "日常杂货", 120, 24);
        o.add("products", products);
        return o;
    }

    private static void add(JsonArray products, String id, String name, String category, long buy, long sell) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id); p.addProperty("item", "minecraft:" + id);
        p.addProperty("name", name); p.addProperty("category", category);
        p.addProperty("buy", buy); p.addProperty("sell", sell);
        products.add(p);
    }
}

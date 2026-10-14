package cozylife.economy.server;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** Versioned server-authoritative reference database, separate from the shop catalog. */
public final class OfficialPriceBook {
    private final JsonObject document;
    private final JsonObject entries;
    private final String revision;
    private OfficialPriceBook(JsonObject document) {
        this.document=document;this.entries=document.getAsJsonObject("items");this.revision=document.get("revision").getAsString();
        if(document.get("schema").getAsInt()!=1||entries.size()>50000||!revision.matches("[A-Za-z0-9._-]{1,96}"))throw new IllegalArgumentException("官方物价库版本或数量错误");
        for(var e:entries.entrySet()) {
            JsonObject p=e.getValue().getAsJsonObject();long price=p.get("official_price").getAsBigDecimal().longValueExact();
            if(!e.getKey().equals(p.get("item").getAsString())||price<0||price>1_000_000_000L||!MarketCatalog.CATEGORIES.contains(p.get("category").getAsString()))throw new IllegalArgumentException("官方物价库条目错误："+e.getKey());
            if(p.get("eligible").getAsBoolean()&&(price<1||MarketCatalog.excludedByOwner(e.getKey())||MarketCatalog.lowValueExcluded(e.getKey())||e.getKey().startsWith("cozylife_economy:")))throw new IllegalArgumentException("官方物价库试图启用禁售物品："+e.getKey());
        }
    }
    public static OfficialPriceBook load(MinecraftServer server)throws IOException {
        Path file=server.getServerDirectory().resolve("config/qizhang-prices/QiZhangOfficialPrices.json");
        if(!Files.exists(file))try(InputStream input=OfficialPriceBook.class.getResourceAsStream("/data/cozylife_economy/QiZhangOfficialPrices.json")) {
            if(input==null)throw new IOException("缺少官方物价数据库");
            AccountLedger.atomicWrite(file,new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
        if(Files.size(file)>64*1024*1024)throw new IOException("官方物价库超过安全大小");
        OfficialPriceBook book=new OfficialPriceBook(JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject());
        Set<String> actual=new TreeSet<>();for(var key:BuiltInRegistries.ITEM.keySet())actual.add(key.toString());
        for(String id:actual)if(!book.entries.has(id)) {JsonObject p=new JsonObject();p.addProperty("item",id);p.addProperty("name",id);p.addProperty("official_price",1000000);p.addProperty("official_sell_price",0);p.addProperty("default_sell_price",0);p.addProperty("category","modded");p.addProperty("eligible",false);p.addProperty("technical",id.startsWith("cozylife_economy:")||id.equals("minecraft:air"));p.addProperty("owner_excluded",MarketCatalog.excludedByOwner(id)||MarketCatalog.lowValueExcluded(id));p.addProperty("reason","新注册物品暂无可靠估价，参考值不可自动上架；需人工定价");p.addProperty("confidence","unreviewed");p.addProperty("default_daily_sell_limit",0);p.addProperty("source","运行时补录；未纳入当前整合包配方估价快照");book.entries.add(id,p);}
        book.document.addProperty("active_registered_items",actual.size());
        return book;
    }
    public JsonObject estimate(String item) {
        Identifier.parse(item);if(!entries.has(item))throw new IllegalArgumentException("官方物价库没有该注册物品，请管理员更新数据库");
        JsonObject out=entries.getAsJsonObject(item).deepCopy();out.addProperty("library_revision",revision);return out;
    }
    public long automaticPrice(String item) {
        JsonObject entry=estimate(item);
        if(!entry.get("eligible").getAsBoolean())throw new IllegalArgumentException("该物品不能自动上架："+entry.get("reason").getAsString());
        return entry.get("official_price").getAsLong();
    }
    public void checkListing(String item,boolean automatic) {
        JsonObject p=estimate(item);
        if(MarketCatalog.excludedByOwner(item)||MarketCatalog.lowValueExcluded(item)||(p.has("owner_excluded")&&p.get("owner_excluded").getAsBoolean()))throw new IllegalArgumentException("该物品属于服主禁售范围："+item);
        if(p.has("technical")&&p.get("technical").getAsBoolean())throw new IllegalArgumentException("技术、创造或特殊组件物品禁止进入普通商店："+item);
        if(automatic)automaticPrice(item);
    }
    public long repairPrice(String item,int missing,int maximum) {
        JsonObject p=estimate(item);
        if(maximum<1||missing<1||missing>maximum||!p.get("eligible").getAsBoolean()||p.get("official_price").getAsLong()<1)throw new IllegalArgumentException("该装备暂不支持官方保养，请使用原有修理方式");
        return Math.addExact(20L,java.math.BigDecimal.valueOf(p.get("official_price").getAsLong()).multiply(java.math.BigDecimal.valueOf(missing))
            .divide(java.math.BigDecimal.valueOf(maximum).multiply(java.math.BigDecimal.valueOf(4)),0,java.math.RoundingMode.CEILING).longValueExact());
    }
    public JsonObject summary(){JsonObject o=new JsonObject();o.addProperty("revision",revision);o.addProperty("items",entries.size());o.addProperty("label","本服官方物价库");return o;}
}

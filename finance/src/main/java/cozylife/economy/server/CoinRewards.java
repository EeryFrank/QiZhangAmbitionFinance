package cozylife.economy.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Physical rewards only. No balance minting, chest-open listener, Lootr refresh, or client authority. */
public final class CoinRewards {
    private static final Logger LOG = LoggerFactory.getLogger("QiZhangEconomy 钱币奖励");
    private static final DeferredRegister<MapCodec<? extends LootItemCondition>> CONDITIONS=DeferredRegister.create(Registries.LOOT_CONDITION_TYPE,"cozylife_economy");
    private static volatile java.util.Set<Identifier> monsterTables;
    private static volatile Config config = Config.disabled();
    private CoinRewards() {}

    public record Roll(float chance, int min, int max) {}
    public record RewardSet(boolean enabled, Roll copper, Roll silver, Roll gold) {}
    public record Config(boolean enabled, RewardSet chests, RewardSet monsters, boolean allowCreativeKills) {
        static Config disabled() {
            RewardSet none = new RewardSet(false, new Roll(0, 1, 1), new Roll(0, 1, 1), new Roll(0, 1, 1));
            return new Config(false, none, none, false);
        }
    }

    public static void register(IEventBus bus){CONDITIONS.register("real_player_monster_kill",()->RealPlayerMonsterKill.CODEC);CONDITIONS.register(bus);}
    /** Serialized in the loot table; fake players and environmental deaths cannot mint coins. */
    public record RealPlayerMonsterKill(boolean allowCreativeKills) implements LootItemCondition {
        public static final MapCodec<RealPlayerMonsterKill> CODEC=Codec.BOOL.optionalFieldOf("allow_creative_kills",false).xmap(RealPlayerMonsterKill::new,RealPlayerMonsterKill::allowCreativeKills);
        @Override public MapCodec<? extends LootItemCondition> codec(){return CODEC;}
        @Override public java.util.Set<net.minecraft.util.context.ContextKey<?>> getReferencedContextParams(){return java.util.Set.of(LootContextParams.THIS_ENTITY,LootContextParams.ATTACKING_ENTITY);}
        @Override public boolean test(LootContext context){
            var victim=context.getOptional(LootContextParams.THIS_ENTITY);var attacker=context.getOptional(LootContextParams.ATTACKING_ENTITY);
            return victim!=null&&victim.getType().getCategory()==MobCategory.MONSTER&&attacker instanceof ServerPlayer player
                &&!(player instanceof FakePlayer)&&!player.isSpectator()&&(allowCreativeKills||!player.isCreative())
                &&context.getLevel().getGameRules().get(GameRules.MOB_DROPS);
        }
    }

    /** Call once before registering listeners. Invalid configuration disables rewards without overwriting it. */
    public static synchronized boolean initialize(Path file) {
        config = Config.disabled();
        monsterTables=null;
        try {
            Path absolute = file.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            if (!Files.exists(absolute)) {
                String initial = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(defaults()) + "\n";
                try { Files.writeString(absolute, initial, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
                catch (FileAlreadyExistsException ignored) { /* A concurrent initializer won; validate its file below. */ }
            }
            if (!Files.isRegularFile(absolute) || Files.size(absolute) > 65_536L)
                throw new IOException("奖励配置不存在、不是普通文件或超过 64 KiB");
            JsonElement parsed = JsonParser.parseString(Files.readString(absolute, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("奖励配置必须是 JSON 对象");
            config = parse(parsed.getAsJsonObject());
            LOG.info("钱币奖励配置已加载：总开关={}，探索箱={}，怪物={}；修改配置后请重启服务器", config.enabled, config.chests.enabled, config.monsters.enabled);
            return true;
        } catch (IOException | RuntimeException ex) {
            LOG.error("钱币奖励配置加载失败，本次运行已停用奖励；原配置保留。原因：{}", ex.getMessage());
            return false;
        }
    }

    /** Three independent pools in original chest/default MONSTER tables; Lootr uses the original chest table. */
    public static void onLootTableLoad(LootTableLoadEvent event) {
        Config current = config;
        if (event.isCanceled() || !current.enabled) return;
        LootTable table = event.getTable();
        if (table == LootTable.EMPTY || table.isFrozen()) return;
        boolean chest=event.getName().getPath().startsWith("chests/"),monster=monsterTables().contains(event.getName());
        RewardSet rewards=monster?current.monsters:current.chests;
        if(!monster&&!chest||!rewards.enabled)return;
        for(String coin:java.util.List.of("copper","silver","gold"))table.removePool("cozylife_economy_"+coin+"_chest_reward");
        addPool(table,"copper",rewards.copper,monster,current.allowCreativeKills);
        addPool(table,"silver",rewards.silver,monster,current.allowCreativeKills);
        addPool(table,"gold",rewards.gold,monster,current.allowCreativeKills);
    }
    private static java.util.Set<Identifier> monsterTables(){
        java.util.Set<Identifier> found=monsterTables;if(found!=null)return found;
        synchronized(CoinRewards.class){if(monsterTables==null){java.util.Set<Identifier> tables=new java.util.HashSet<>();
            for(EntityType<?> type:BuiltInRegistries.ENTITY_TYPE)if(type.getCategory()==MobCategory.MONSTER)type.getDefaultLootTable().ifPresent(key->tables.add(key.identifier()));
            monsterTables=java.util.Set.copyOf(tables);LOG.info("已按 MONSTER 注册类型及真实 getDefaultLootTable 建立 {} 个怪物战利品表映射",tables.size());
        }return monsterTables;}
    }

    private static void addPool(LootTable table, String denomination, Roll roll,boolean monster,boolean allowCreative) {
        String name = "qizhang_economy_" + denomination + (monster?"_monster_reward":"_chest_reward");
        if (roll.chance <= 0 || table.getPool(name) != null) return;
        Item coin = requireCoin(denomination);
        LootPool.Builder builder=LootPool.lootPool().name(name).setRolls(ContextIntProviders.exactly(1));
        if(monster)builder.when(Holder.direct(new RealPlayerMonsterKill(allowCreative)));
        builder.when(LootItemRandomChanceCondition.randomChance(roll.chance))
            .add(LootItem.lootTableItem(coin).apply(SetItemCountFunction.setCount(ContextIntProviders.between(roll.min, roll.max))));
        table.addPool(builder.build());
    }

    /** Kept only for the existing entrypoint listener. All rewards now belong to loot tables. */
    public static void onLivingDrops(LivingDropsEvent event) {}

    /** Read-only proof from the final loaded server table, including serialized chance conditions. */
    public static JsonObject exportTableProof(MinecraftServer server,String tableId){
        Identifier id=Identifier.parse(tableId);LootTable table=server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,id));
        JsonObject out=new JsonObject();out.addProperty("table",id.toString());out.addProperty("monster_default_table",monsterTables().contains(id));out.addProperty("chest_path",id.getPath().startsWith("chests/"));
        JsonArray pools=new JsonArray();for(String kind:java.util.List.of("chest","monster"))for(String coin:java.util.List.of("copper","silver","gold")){
            String name="qizhang_economy_"+coin+"_"+kind+"_reward";LootPool pool=table.getPool(name);if(pool!=null){JsonObject entry=new JsonObject();entry.addProperty("name",name);entry.add("pool",LootPool.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.reloadableRegistries().lookup()),pool).getOrThrow());pools.add(entry);}
        }out.add("reward_pools",pools);out.addProperty("living_drops_fallback",false);return out;
    }

    private static Item requireCoin(String denomination) {
        Identifier id = Identifier.fromNamespaceAndPath("cozylife_economy", denomination + "_coin");
        return BuiltInRegistries.ITEM.getOptional(id).orElseThrow(() -> new IllegalStateException("钱币尚未注册：" + id));
    }

    public static Config parse(JsonObject json) {
        if (integer(json, "schema", 2, 2) != 2) throw new IllegalArgumentException("奖励配置版本不支持；需要三币独立掉落 schema 2");
        return new Config(bool(json, "enabled"), rewardSet(object(json, "chests")), rewardSet(object(json, "monsters")), bool(json, "allow_creative_kills"));
    }
    private static RewardSet rewardSet(JsonObject json) {
        return new RewardSet(bool(json, "enabled"), roll(object(json, "copper")), roll(object(json, "silver")), roll(object(json, "gold")));
    }
    private static Roll roll(JsonObject json) {
        JsonElement chance = json.get("chance");
        if (chance == null || !chance.isJsonPrimitive() || !chance.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("chance 必须为 0 至 1 的数字");
        BigDecimal exact = chance.getAsBigDecimal();
        if (exact.compareTo(BigDecimal.ZERO) < 0 || exact.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("chance 必须位于 0 至 1");
        int min = integer(json, "min", 1, 64), max = integer(json, "max", 1, 64);
        if (max < min) throw new IllegalArgumentException("奖励最大数量不能小于最小数量");
        return new Roll(exact.floatValue(), min, max);
    }
    private static int integer(JsonObject json, String name, int min, int max) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(name + " 必须为整数");
        int result = value.getAsBigDecimal().intValueExact();
        if (result < min || result > max) throw new IllegalArgumentException(name + " 超出允许范围");
        return result;
    }
    private static boolean bool(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(name + " 必须为 true 或 false");
        return value.getAsBoolean();
    }
    private static JsonObject object(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " 必须为 JSON 对象");
        return value.getAsJsonObject();
    }
    public static JsonObject defaults() {
        return JsonParser.parseString("""
            {
              "schema": 2,
              "enabled": true,
              "allow_creative_kills": false,
              "chests": {
                "enabled": true,
                "copper": {"chance": 0.05, "min": 1, "max": 1},
                "silver": {"chance": 0.005, "min": 1, "max": 1},
                "gold": {"chance": 0.0005, "min": 1, "max": 1}
              },
              "monsters": {
                "enabled": true,
                "copper": {"chance": 0.05, "min": 1, "max": 1},
                "silver": {"chance": 0.005, "min": 1, "max": 1},
                "gold": {"chance": 0.0005, "min": 1, "max": 1}
              }
            }
            """).getAsJsonObject();
    }
}

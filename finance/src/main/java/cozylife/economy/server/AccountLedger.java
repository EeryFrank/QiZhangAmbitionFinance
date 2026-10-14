package cozylife.economy.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable single-writer ledger. The Minecraft adapter supplies a receipt stored in the
 * same player-data save as the changed inventory. A pending transaction is resolved
 * only from that saved receipt, never by guessing from the current item count.
 */
public final class AccountLedger implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    private final Path accounts;
    private final Path audit;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public record Pending(String txid, String priorMarker, String action, String item,
                          int count, long unitPrice, long beforeBalance, long afterBalance,
                          long beforeRevision, long afterRevision) {}
    public record Account(long balance, long revision, String marker, Pending pending) {}

    public AccountLedger(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        accounts = this.root.resolve("accounts");
        audit = this.root.resolve("transactions.jsonl");
        Files.createDirectories(accounts);
        lockChannel = FileChannel.open(this.root.resolve("ledger.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("经济账本已被另一个服务器实例占用");
        } catch (RuntimeException | IOException ex) {
            lockChannel.close();
            throw new IOException("无法独占经济账本", ex);
        }
    }

    public synchronized Account read(UUID player) throws IOException {
        requireOpen();
        Path path = path(player);
        if (!Files.exists(path)) return new Account(0, 0, "", null);
        try {
            if (Files.size(path) > 32_768) throw new IOException("账户文件大小异常");
            JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (number(o, "schema") != 1 || !player.toString().equals(text(o, "uuid")))
                throw new IOException("账户身份或版本不匹配");
            long balance = number(o, "balance"), revision = number(o, "revision");
            if (balance < 0 || revision < 0) throw new IOException("账户余额或序号无效");
            String marker = text(o, "marker");
            if (!marker.isEmpty()) UUID.fromString(marker);
            Pending pending = null;
            if (o.has("pending") && !o.get("pending").isJsonNull()) {
                JsonObject p = o.getAsJsonObject("pending");
                pending = new Pending(text(p, "txid"), text(p, "prior_marker"), text(p, "action"),
                        text(p, "item"), Math.toIntExact(number(p, "count")), number(p, "unit_price"),
                        number(p, "before_balance"), number(p, "after_balance"),
                        number(p, "before_revision"), number(p, "after_revision"));
                UUID.fromString(pending.txid);
                MoneyMath.count(pending.count);
                validatePrice(pending.action, pending.item, pending.unitPrice);
                if (pending.unitPrice < 0 || pending.beforeBalance != balance || pending.afterBalance < 0
                        || pending.beforeRevision != revision || pending.afterRevision != Math.incrementExact(revision)
                        || !pending.priorMarker.equals(marker)) throw new IOException("待处理交易不一致");
                long amount = MoneyMath.total(pending.unitPrice, pending.count);
                long conserved = switch (pending.action) {
                    case "sell", "deposit" -> MoneyMath.credit(balance, amount);
                    case "buy", "withdraw" -> MoneyMath.debit(balance, amount);
                    default -> throw new IOException("待处理交易类型不受支持");
                };
                if (conserved != pending.afterBalance) throw new IOException("待处理交易金额不守恒");
            }
            return new Account(balance, revision, marker, pending);
        } catch (RuntimeException ex) {
            throw new IOException("账户数据损坏；为避免丢币，不会自动清零", ex);
        }
    }

    public synchronized Pending prepare(UUID player, long expectedRevision, String action,
                                        String item, int count, long unitPrice, long afterBalance) throws IOException {
        Account account = read(player);
        if (account.pending != null) throw new IOException("上一笔交易尚未核实");
        if (account.revision != expectedRevision) throw new IOException("账户版本已变化，请重新打开菜单");
        if (afterBalance < 0 || unitPrice < 0) throw new IllegalArgumentException("交易金额无效");
        MoneyMath.count(count);
        if (!action.matches("buy|sell|withdraw|deposit") || item.length() > 160)
            throw new IllegalArgumentException("交易类型或物品无效");
        validatePrice(action, item, unitPrice);
        long expected = switch (action) {
            case "sell", "deposit" -> MoneyMath.credit(account.balance, MoneyMath.total(unitPrice, count));
            default -> MoneyMath.debit(account.balance, MoneyMath.total(unitPrice, count));
        };
        if (expected != afterBalance) throw new IllegalArgumentException("交易收支不守恒");
        Pending pending = new Pending(UUID.randomUUID().toString(), account.marker, action, item, count,
                unitPrice, account.balance, afterBalance, account.revision, Math.incrementExact(account.revision));
        append(player, pending, "PREPARED_INTENT");
        write(player, new Account(account.balance, account.revision, account.marker, pending));
        return pending;
    }

    /** Idempotent finalization; a repeated receipt can never credit the account twice. */
    public synchronized Account commit(UUID player, String txid) throws IOException {
        Account account = read(player);
        if (account.pending == null) {
            if (account.marker.equals(txid)) return account;
            throw new IOException("找不到对应的待处理交易");
        }
        Pending pending = account.pending;
        if (!pending.txid.equals(txid)) throw new IOException("交易凭据不匹配");
        append(player, pending, "COMMIT_DECISION");
        Account committed = new Account(pending.afterBalance, pending.afterRevision, pending.txid, null);
        write(player, committed);
        return committed;
    }

    public synchronized Account recover(UUID player, String savedPlayerMarker) throws IOException {
        Account account = read(player);
        Pending pending = account.pending;
        if (pending == null) {
            if (!account.marker.equals(savedPlayerMarker))
                throw new IOException("玩家存档与账本凭据不一致，请管理员核查存档恢复情况");
            return account;
        }
        if (pending.txid.equals(savedPlayerMarker)) return commit(player, pending.txid);
        if (!pending.priorMarker.equals(savedPlayerMarker))
            throw new IOException("待处理交易的存档凭据异常，账户已保护锁定");
        append(player, pending, "ABORT_DECISION");
        Account aborted = new Account(pending.beforeBalance, pending.beforeRevision, pending.priorMarker, null);
        write(player, aborted);
        return aborted;
    }

    private Path path(UUID player) { return accounts.resolve(player + ".json"); }
    private void requireOpen() throws IOException { if (closed) throw new IOException("经济账本已关闭"); }

    private static void validatePrice(String action, String item, long unitPrice) {
        if (unitPrice <= 0) throw new IllegalArgumentException("交易价格必须为正整数");
        if ("deposit".equals(action) || "withdraw".equals(action)) {
            long denomination = switch (item) {
                case "cozylife_economy:copper_coin" -> 1;
                case "cozylife_economy:silver_coin" -> 100;
                case "cozylife_economy:gold_coin" -> 10000;
                default -> throw new IllegalArgumentException("只有原生铜币、银币、金币才能存取");
            };
            if (unitPrice != denomination) throw new IllegalArgumentException("货币面值不匹配");
        } else if (item.startsWith("cozylife_economy:")) {
            throw new IllegalArgumentException("货币必须按固定面值存取");
        }
    }

    private void write(UUID player, Account account) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("schema", 1);
        o.addProperty("uuid", player.toString());
        o.addProperty("balance", account.balance);
        o.addProperty("revision", account.revision);
        o.addProperty("marker", account.marker);
        o.add("pending", account.pending == null ? null : pendingJson(account.pending));
        atomicWrite(path(player), JSON.toJson(o) + "\n");
    }

    private void append(UUID player, Pending pending, String phase) throws IOException {
        JsonObject o = pendingJson(pending);
        o.addProperty("schema", 1);
        o.addProperty("uuid", player.toString());
        o.addProperty("phase", phase);
        o.addProperty("utc", Instant.now().toString());
        byte[] bytes = (new Gson().toJson(o) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(audit, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
    }

    private static JsonObject pendingJson(Pending p) {
        JsonObject o = new JsonObject();
        o.addProperty("txid", p.txid); o.addProperty("prior_marker", p.priorMarker);
        o.addProperty("action", p.action); o.addProperty("item", p.item);
        o.addProperty("count", p.count); o.addProperty("unit_price", p.unitPrice);
        o.addProperty("before_balance", p.beforeBalance); o.addProperty("after_balance", p.afterBalance);
        o.addProperty("before_revision", p.beforeRevision); o.addProperty("after_revision", p.afterRevision);
        return o;
    }

    static long number(JsonObject object, String key) {
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("数值字段格式不正确：" + key);
        return object.get(key).getAsBigDecimal().longValueExact();
    }
    static String text(JsonObject object, String key) {
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException("文本字段格式不正确：" + key);
        String value = object.get(key).getAsString();
        if (value.length() > 256) throw new IllegalArgumentException("文本字段过长：" + key);
        return value;
    }

    static void atomicWrite(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(channel, text.getBytes(StandardCharsets.UTF_8));
                channel.force(true);
            }
            // Fail closed if the filesystem does not support replacement by atomic rename.
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}

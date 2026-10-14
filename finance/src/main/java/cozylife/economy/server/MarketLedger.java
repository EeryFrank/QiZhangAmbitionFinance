package cozylife.economy.server;

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
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Accounts, market, quota, VIP and mailbox commit in one atomic state replacement. */
public final class MarketLedger implements AutoCloseable {
    public interface ReceiptReader { String read(UUID player) throws IOException; }
    private final Path file, audit;
    private final FileChannel channel;
    private final FileLock lock;
    private JsonObject envelope;
    private boolean closed;
    public MarketLedger(Path root, JsonObject initial) throws IOException {
        Files.createDirectories(root);
        file=root.resolve("state.json"); audit=root.resolve("transactions.jsonl");
        channel=FileChannel.open(root.resolve("state.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try { lock=channel.tryLock(); if(lock==null)throw new IOException("统一经济账本已被占用"); }
        catch(RuntimeException|IOException e){channel.close();throw new IOException("无法独占统一经济账本",e);}
        try {
            if(Files.exists(file)) {
                if(Files.size(file)>32*1024*1024)throw new IOException("统一账本文件过大");
                envelope=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if(AccountLedger.number(envelope,"schema")!=2)throw new IOException("经济账本版本不匹配");
                validate(envelope.getAsJsonObject("state"));
                if(envelope.has("pending")) {
                    JsonObject pending=envelope.getAsJsonObject("pending"),before=envelope.getAsJsonObject("state"),after=pending.getAsJsonObject("after");validate(after);
                    UUID player=UUID.fromString(AccountLedger.text(pending,"uuid"));String tx=AccountLedger.text(pending,"txid");UUID.fromString(tx);
                    JsonObject old=account(before,player),next=account(after,player);
                    if(!AccountLedger.text(old,"marker").equals(AccountLedger.text(pending,"before_marker"))||!tx.equals(AccountLedger.text(next,"marker"))
                        ||AccountLedger.number(next,"revision")!=Math.incrementExact(AccountLedger.number(old,"revision"))
                        ||AccountLedger.number(after,"revision")!=Math.incrementExact(AccountLedger.number(before,"revision")))throw new IOException("待处理统一交易的身份或版本不一致");
                }
            } else {
                envelope=new JsonObject();envelope.addProperty("schema",2);envelope.add("state",initial.deepCopy());
                validate(initial);write(envelope);
            }
        }catch(IOException|RuntimeException e){lock.release();channel.close();throw new IOException("统一经济账本无法读取，拒绝自动清零",e);}
    }
    public synchronized JsonObject state() throws IOException {open();if(envelope.has("pending"))throw new IOException("经济交易待恢复");return envelope.getAsJsonObject("state").deepCopy();}
    public synchronized String prepare(UUID player, JsonObject after, String action, JsonObject detail) throws IOException {
        open(); if(envelope.has("pending"))throw new IOException("经济交易待恢复");
        JsonObject before=envelope.getAsJsonObject("state");
        String tx=UUID.randomUUID().toString();
        JsonObject old=account(before,player), next=account(after,player);
        next.addProperty("marker",tx);next.addProperty("revision",Math.incrementExact(AccountLedger.number(old,"revision")));
        after.addProperty("revision",Math.incrementExact(AccountLedger.number(before,"revision")));
        validate(after);
        JsonObject p=new JsonObject();p.addProperty("txid",tx);p.addProperty("uuid",player.toString());
        p.addProperty("before_marker",AccountLedger.text(old,"marker"));p.addProperty("action",action);
        p.add("detail",detail.deepCopy());p.add("after",after.deepCopy());
        log(p,"PREPARED_INTENT");
        JsonObject nextEnvelope=envelope.deepCopy();nextEnvelope.add("pending",p);write(nextEnvelope);envelope=nextEnvelope;
        return tx;
    }
    public synchronized void commit(String tx) throws IOException {
        open();if(!envelope.has("pending")){
            if(envelope.has("last_commit")&&tx.equals(envelope.get("last_commit").getAsString()))return;
            throw new IOException("没有对应待处理交易");
        }
        JsonObject p=envelope.getAsJsonObject("pending");if(!tx.equals(AccountLedger.text(p,"txid")))throw new IOException("交易凭据不一致");
        log(p,"COMMIT_DECISION");JsonObject next=new JsonObject();next.addProperty("schema",2);next.addProperty("last_commit",tx);next.add("state",p.getAsJsonObject("after").deepCopy());
        write(next);envelope=next;
    }
    public synchronized void recover(ReceiptReader reader) throws IOException {
        open();if(!envelope.has("pending"))return;
        JsonObject p=envelope.getAsJsonObject("pending");String saved=reader.read(UUID.fromString(AccountLedger.text(p,"uuid")));
        if(saved.equals(AccountLedger.text(p,"txid"))){commit(saved);return;}
        if(!saved.equals(AccountLedger.text(p,"before_marker")))throw new IOException("存档收据异常，统一经济账本已锁定");
        log(p,"ABORT_DECISION");JsonObject next=envelope.deepCopy();next.remove("pending");write(next);envelope=next;
    }
    public synchronized void replace(JsonObject after,String action) throws IOException {
        JsonObject before=state();after.addProperty("revision",Math.incrementExact(AccountLedger.number(before,"revision")));validate(after);
        JsonObject p=new JsonObject();p.addProperty("action",action);p.addProperty("before_revision",AccountLedger.number(before,"revision"));p.addProperty("after_revision",AccountLedger.number(after,"revision"));
        log(p,"STATE_CHANGE_INTENT");JsonObject next=envelope.deepCopy();next.add("state",after.deepCopy());write(next);envelope=next;
    }
    public static JsonObject account(JsonObject state,UUID uuid) {
        JsonObject accounts=state.getAsJsonObject("accounts");String key=uuid.toString();
        if(!accounts.has(key)){
            JsonObject a=new JsonObject();a.addProperty("balance",0L);a.addProperty("revision",0L);a.addProperty("marker","");
            a.addProperty("vip_until",0L);a.addProperty("day","");a.add("daily",new JsonObject());a.add("mail",new com.google.gson.JsonArray());accounts.add(key,a);
        }
        return accounts.getAsJsonObject(key);
    }
    public static void validate(JsonObject state) {
        AdminCoinMail.validate(state);KnownEconomyPlayers.validate(state);EconomyMetrics.validate(state);ApiTransactions.validate(state);
        if(AccountLedger.number(state,"revision")<0)throw new IllegalArgumentException("市场版本错误");
        if(AccountLedger.number(state,"market_revision")<0)throw new IllegalArgumentException("市况版本错误");
        if(java.time.LocalDate.ofEpochDay(AccountLedger.number(state,"last_monday")).getDayOfWeek()!=java.time.DayOfWeek.MONDAY)throw new IllegalArgumentException("每周结算日期不是周一");
        MarketCatalog.validate(state.getAsJsonObject("config"));
        JsonObject market=state.getAsJsonObject("market");
        for(var entry:market.entrySet())if(MarketPricing.decimal(entry.getValue().getAsString()).signum()<=0)throw new IllegalArgumentException("市价错误");
        for(var entry:state.getAsJsonObject("config").getAsJsonArray("products")){
            JsonObject p=entry.getAsJsonObject();java.math.BigDecimal current=MarketPricing.decimal(market.get(AccountLedger.text(p,"item")).getAsString()),base=java.math.BigDecimal.valueOf(AccountLedger.number(p,"base"));
            if(current.compareTo(base.multiply(FinanceRules.lower(state.getAsJsonObject("config"))))<0||current.compareTo(base.multiply(FinanceRules.upper(state.getAsJsonObject("config"))))>0)throw new IllegalArgumentException("市价越过官方价格边界");
        }
        for(var entry:state.getAsJsonObject("accounts").entrySet()) {
            UUID.fromString(entry.getKey());JsonObject a=entry.getValue().getAsJsonObject();
            CheckInRules.validateAccount(a);
            if(AccountLedger.number(a,"balance")<0||AccountLedger.number(a,"revision")<0||AccountLedger.number(a,"vip_until")<0)throw new IllegalArgumentException("账户金额错误");
            if(!AccountLedger.text(a,"marker").isEmpty())UUID.fromString(AccountLedger.text(a,"marker"));
            if(a.getAsJsonArray("mail").size()>256)throw new IllegalArgumentException("邮箱条目过多");
            for(var mail:a.getAsJsonArray("mail")) {
                JsonObject m=mail.getAsJsonObject();UUID.fromString(AccountLedger.text(m,"id"));
                if(AccountLedger.number(m,"count")<1||AccountLedger.number(m,"count")>1_000_000)throw new IllegalArgumentException("邮箱数量异常");
            }
            for(var daily:a.getAsJsonObject("daily").entrySet())if(daily.getValue().getAsBigDecimal().longValueExact()<0)throw new IllegalArgumentException("回收计数异常");
        }
    }
    private void log(JsonObject p,String phase)throws IOException{
        JsonObject line=p.deepCopy();line.remove("after");line.addProperty("phase",phase);line.addProperty("utc",Instant.now().toString());
        byte[] bytes=("\n"+line+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileChannel c=FileChannel.open(audit,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);
        }
    }
    private void write(JsonObject object)throws IOException{String encoded=new GsonBuilder().setPrettyPrinting().create().toJson(object)+"\n";if(encoded.getBytes(StandardCharsets.UTF_8).length>32*1024*1024)throw new IOException("统一经济账本已达文件安全上限，请管理员扩容");AccountLedger.atomicWrite(file,encoded);}
    private void open()throws IOException{if(closed)throw new IOException("经济账本已关闭");}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;try{lock.release();}finally{channel.close();}}
}

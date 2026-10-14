package cozylife.economy.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;

/** Pure state operation: mail and durable job receipt must be saved in the same MarketLedger.replace. */
public final class AdminCoinMail {
    public static final int MAX_PER_GRANT=100_000,MAX_MAIL_ITEM=1_000_000,MAX_MAIL_ENTRIES=256,MAX_JOBS=10_000;
    private AdminCoinMail(){}
    public record Result(JsonObject response,boolean changed){}
    public static UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException("UUID 必须使用完整的标准格式");return id;}
    public static String coinItem(String coin){if(!List.of("copper","silver","gold").contains(coin))throw new IllegalArgumentException("币种必须是 copper、silver 或 gold");return "cozylife_economy:"+coin+"_coin";}
    public static Result apply(JsonObject state,UUID player,String coin,int count,UUID job,long now) {
        String item=coinItem(coin);if(count<1||count>MAX_PER_GRANT)throw new IllegalArgumentException("每次发放数量必须在 1—100000 枚之间");
        if(now<0)throw new IllegalArgumentException("服务器时间异常");
        JsonObject jobs=state.has("admin_coin_jobs")?state.getAsJsonObject("admin_coin_jobs"):new JsonObject();
        if(jobs.has(job.toString())){
            JsonObject previous=jobs.getAsJsonObject(job.toString());
            if(!player.toString().equals(text(previous,"target_uuid"))||!coin.equals(text(previous,"coin"))||count!=num(previous,"count"))
                throw new IllegalArgumentException("jobUUID 已用于另一笔发放；重试必须保留相同收件人、币种与数量");
            JsonObject response=previous.deepCopy();response.addProperty("replay",true);return new Result(response,false);
        }
        if(jobs.size()>=MAX_JOBS)throw new IllegalArgumentException("发放凭据已达安全上限，请管理员扩容归档；旧 jobUUID 不会自动清除");
        JsonObject a=MarketLedger.account(state,player);JsonArray mail=a.getAsJsonArray("mail");JsonObject match=null;
        for(var entry:mail){JsonObject m=entry.getAsJsonObject();if(item.equals(text(m,"item"))){match=m;break;}}
        long next=match==null?count:Math.addExact(num(match,"count"),count);
        if(next>MAX_MAIL_ITEM)throw new IllegalArgumentException("收件人的该币种邮箱存量将超过1000000枚，请先领取");
        if(match==null&&mail.size()>=MAX_MAIL_ENTRIES)throw new IllegalArgumentException("收件人邮箱已满，本次未发放");
        long revision=Math.incrementExact(num(a,"revision"));
        String mailId=match==null?UUID.randomUUID().toString():text(match,"id");
        JsonObject receipt=new JsonObject();receipt.addProperty("job_id",job.toString());receipt.addProperty("target_uuid",player.toString());receipt.addProperty("coin",coin);
        receipt.addProperty("item",item);receipt.addProperty("count",count);receipt.addProperty("mail_id",mailId);receipt.addProperty("created_at",now);
        receipt.addProperty("account_revision",revision);receipt.addProperty("delivery","mailbox");receipt.addProperty("balance_changed",false);receipt.addProperty("replay",false);
        if(match==null){match=new JsonObject();match.addProperty("id",mailId);match.addProperty("item",item);mail.add(match);}
        match.addProperty("count",next);a.addProperty("revision",revision);jobs.add(job.toString(),receipt.deepCopy());state.add("admin_coin_jobs",jobs);
        return new Result(receipt,true);
    }
    public static void validate(JsonObject state){
        if(!state.has("admin_coin_jobs"))return;JsonObject jobs=state.getAsJsonObject("admin_coin_jobs");
        if(jobs.size()>MAX_JOBS)throw new IllegalArgumentException("管理员发放凭据数量过多");
        for(var entry:jobs.entrySet()){
            UUID job=uuid(entry.getKey());JsonObject r=entry.getValue().getAsJsonObject();uuid(text(r,"target_uuid"));uuid(text(r,"mail_id"));
            if(!job.toString().equals(text(r,"job_id"))||!coinItem(text(r,"coin")).equals(text(r,"item"))||num(r,"count")<1||num(r,"count")>MAX_PER_GRANT
                ||num(r,"created_at")<0||num(r,"account_revision")<1||!"mailbox".equals(text(r,"delivery"))||r.get("balance_changed").getAsBoolean())throw new IllegalArgumentException("管理员发放凭据损坏");
        }
    }
    private static long num(JsonObject o,String key){return AccountLedger.number(o,key);}
    private static String text(JsonObject o,String key){return AccountLedger.text(o,key);}
}

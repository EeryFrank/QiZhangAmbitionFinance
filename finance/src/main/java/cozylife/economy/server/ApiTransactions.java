package cozylife.economy.server;

import com.google.gson.*;
import java.util.UUID;

/** Persistent idempotency for trusted server-side integrations, committed with the account state. */
public final class ApiTransactions {
    public static final int MAX_RECEIPTS=50000;
    private ApiTransactions(){}
    public static JsonObject apply(JsonObject state,UUID request,UUID player,UUID other,long amount,String operation,String reason,long now){
        if(request==null||player==null||amount<1||reason==null||reason.isBlank()||reason.length()>160||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("接口请求编号、金额或说明无效");
        if(!java.util.Set.of("credit","debit","transfer").contains(operation)||("transfer".equals(operation)!=(other!=null))||player.equals(other))throw new IllegalArgumentException("余额操作或收款账户无效");
        JsonObject records=state.has("api_receipts")?state.getAsJsonObject("api_receipts"):new JsonObject();
        String key=request.toString(),target=other==null?"":other.toString();
        if(records.has(key)){JsonObject old=records.getAsJsonObject(key);if(!old.get("player").getAsString().equals(player.toString())||!old.get("other").getAsString().equals(target)||old.get("amount").getAsLong()!=amount||!old.get("operation").getAsString().equals(operation)||!old.get("reason").getAsString().equals(reason))throw new IllegalArgumentException("同一请求编号不能用于不同余额操作");JsonObject replay=old.deepCopy();replay.addProperty("replay",true);return replay;}
        if(records.size()>=MAX_RECEIPTS)throw new IllegalArgumentException("持久化接口收据已达上限，请扩容迁移；不会清除旧收据后允许重复扣款");
        JsonObject a=MarketLedger.account(state,player);long before=AccountLedger.number(a,"balance"),after="credit".equals(operation)?MoneyMath.credit(before,amount):MoneyMath.debit(before,amount);
        long otherAfter=0;JsonObject b=other==null?null:MarketLedger.account(state,other);if(b!=null)otherAfter=MoneyMath.credit(AccountLedger.number(b,"balance"),amount);
        a.addProperty("balance",after);a.addProperty("revision",Math.incrementExact(AccountLedger.number(a,"revision")));
        if(b!=null){b.addProperty("balance",otherAfter);b.addProperty("revision",Math.incrementExact(AccountLedger.number(b,"revision")));}
        JsonObject receipt=new JsonObject();receipt.addProperty("request_id",key);receipt.addProperty("player",player.toString());receipt.addProperty("other",target);receipt.addProperty("amount",amount);receipt.addProperty("operation",operation);receipt.addProperty("reason",reason);receipt.addProperty("balance",after);receipt.addProperty("other_balance",otherAfter);receipt.addProperty("at",now);receipt.addProperty("replay",false);
        records.add(key,receipt.deepCopy());state.add("api_receipts",records);return receipt;
    }
    public static void validate(JsonObject state){
        if(!state.has("api_receipts"))return;JsonObject all=state.getAsJsonObject("api_receipts");if(all.size()>MAX_RECEIPTS)throw new IllegalArgumentException("接口收据数量错误");
        for(var e:all.entrySet()){UUID.fromString(e.getKey());JsonObject r=e.getValue().getAsJsonObject();if(!e.getKey().equals(AccountLedger.text(r,"request_id")))throw new IllegalArgumentException("接口收据编号不匹配");UUID.fromString(AccountLedger.text(r,"player"));String other=AccountLedger.text(r,"other");if(!other.isEmpty())UUID.fromString(other);if(AccountLedger.number(r,"amount")<1||AccountLedger.number(r,"balance")<0||AccountLedger.number(r,"other_balance")<0||AccountLedger.number(r,"at")<0)throw new IllegalArgumentException("接口收据金额错误");}
    }
}

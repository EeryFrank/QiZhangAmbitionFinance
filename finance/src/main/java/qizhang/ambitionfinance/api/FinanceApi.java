package qizhang.ambitionfinance.api;

import com.google.gson.JsonObject;
import cozylife.economy.CozyEconomy;
import cozylife.economy.server.EconomyService;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-only API v1. Every call must run on the owning Minecraft server thread. */
public final class FinanceApi {
    public static final int API_VERSION=1;
    private final EconomyService service;
    private FinanceApi(EconomyService service){this.service=service;}
    public static FinanceApi forServer(MinecraftServer server){EconomyService current=CozyEconomy.service();if(current==null||!current.belongsTo(Objects.requireNonNull(server)))throw new IllegalStateException("七章的野心金融尚未在该服务器启动");return new FinanceApi(current);}
    public long balance(UUID player)throws IOException{return service.apiBalance(player);}
    public boolean hasAccount(UUID player)throws IOException{return service.apiHasAccount(player);}
    /** Reuse the same request UUID when retrying the same operation, including after a restart. */
    public Mutation credit(UUID request,UUID player,long amount,String reason)throws IOException{return mutate(request,player,null,amount,"credit",reason);}
    public Mutation debit(UUID request,UUID player,long amount,String reason)throws IOException{return mutate(request,player,null,amount,"debit",reason);}
    /** Both balances and the idempotency receipt commit atomically in one ledger. */
    public Mutation transfer(UUID request,UUID from,UUID to,long amount,String reason)throws IOException{return mutate(request,from,to,amount,"transfer",reason);}
    private Mutation mutate(UUID request,UUID player,UUID other,long amount,String operation,String reason)throws IOException{JsonObject r=service.apiMutation(request,player,other,amount,operation,reason);return new Mutation(request,r.get("replay").getAsBoolean(),r.get("balance").getAsLong(),r.get("other_balance").getAsLong());}
    public record Mutation(UUID requestId,boolean replay,long balance,long recipientBalance){}
    public enum Coin{COPPER("copper",1),SILVER("silver",100),GOLD("gold",10000);private final String id;private final long value;Coin(String id,long value){this.id=id;this.value=value;}public String id(){return id;}public long value(){return value;}}
    /** Registered/joined players only. Delivers item mail, never directly increases the wallet. */
    public JsonObject sendCoins(UUID request,UUID player,Coin coin,int count)throws IOException{return service.adminGrantCoins(player.toString(),coin.id(),count,request.toString());}
    public JsonObject officialPrice(String item)throws IOException{return service.adminEstimate(item);}
    public JsonObject statistics()throws IOException{return service.adminStats();}
    public JsonObject snapshot(ServerPlayer player){return service.snapshot(player,"");}
    /** Returns server-issued one-use quote tokens; integrations must display the returned total. */
    public JsonObject quote(ServerPlayer player,String product){return service.handle(player,"quote",product,1);}
    public JsonObject transactQuote(ServerPlayer player,String quoteToken){return service.handle(player,"trade",quoteToken,1);}
    public void checkTravelBalance(ServerPlayer player,long fee)throws IOException{service.checkTeleportFunds(player,fee);}
    /** Synchronous callback: true only after the actual teleport succeeds; false must leave position unchanged. */
    public boolean paidTeleport(ServerPlayer player,long fee,String reason,BooleanSupplier teleport)throws IOException{return service.paidTeleport(player,fee,reason,teleport);}
}

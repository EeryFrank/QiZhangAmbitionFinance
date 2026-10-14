package cozylife.economy.server;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Main-thread authority for prices, balances, market, quotas, VIP and persistent mail. */
public final class EconomyService implements AutoCloseable {
    private static final Logger LOG=LogUtils.getLogger();
    private static final String MARKER="cozylife_economy_last_tx",PROBE="cozylife_economy_save_probe";
    private static final long VIP_COST=VipMembership.COST;
    private static final int[] COUNTS={1,16,32,64};
    private final MinecraftServer server;
    private MarketLedger ledger;
    private OfficialPriceBook officialPrices;
    private final Set<UUID> serviceBusy=new HashSet<>();
    private JsonObject prepaidBalanceView;
    private final Map<String,RepairOffer> repairOffers=new HashMap<>();
    private final CheckInOffers<Object> checkInOffers=new CheckInOffers<>();
    private record RepairOffer(UUID player,Object connection,ItemStack held,int slot,long fee,long accountRevision,long expires){}

    private String unavailable;
    private long nextWeeklyAt=Long.MAX_VALUE;
    private final Set<UUID> locked=new HashSet<>();
    private final Map<UUID,Integer> lastTrade=new HashMap<>();
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<String,Offer> offers=new HashMap<>();
    private record Session(Object connection,long sequence){}
    private record Offer(UUID player,Object connection,String group,String product,String side,int count,long total,
                         String afterPrice,long marketRevision,long accountRevision,long expires,boolean vip){}
    private record Delivery(int[] delta,int remainder){}

    public EconomyService(MinecraftServer server){
        this.server=server;
        try{
            officialPrices=OfficialPriceBook.load(server);
            JsonObject initial=MarketCatalog.initial(System.currentTimeMillis());
            JsonArray available=new JsonArray();JsonObject initialPrices=new JsonObject();
            for(var e:initial.getAsJsonObject("config").getAsJsonArray("products")){JsonObject p=e.getAsJsonObject();String id=text(p,"item");if(!BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)))continue;JsonObject estimate=officialPrices.estimate(id);long sell=Math.min(num(p,"base")-1,num(estimate,"default_sell_price"));p.addProperty("sell_base",sell);if(sell<=0)p.addProperty("sell_enabled",false);available.add(p);initialPrices.addProperty(id,num(p,"base"));}
            initial.getAsJsonObject("config").add("products",available);initial.add("market",initialPrices);
            Path seed=server.getServerDirectory().resolve("config/cozylife-economy-market.json");
            if(Files.exists(seed)){
                if(Files.size(seed)>262144)throw new IOException("市场初始配置过大");
                JsonObject config=JsonParser.parseString(Files.readString(seed)).getAsJsonObject();MarketCatalog.validate(config);
                initial.add("config",config);JsonObject prices=new JsonObject();
                for(var e:config.getAsJsonArray("products")){JsonObject p=e.getAsJsonObject();prices.addProperty(text(p,"item"),Long.toString(num(p,"base")));}initial.add("market",prices);
            }else AccountLedger.atomicWrite(seed,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(initial.get("config"))+"\n");
            ledger=new MarketLedger(server.getWorldPath(LevelResource.ROOT).resolve("cozylife-economy-v2"),initial);
            ledger.recover(uuid->savedReceipt(uuid,false).getStringOr(MARKER,""));
            migrateIndependentPrices();
            JsonObject withCheckIn=ledger.state();if(!withCheckIn.getAsJsonObject("config").has("checkin")){withCheckIn.getAsJsonObject("config").add("checkin",CheckInRules.defaults());ledger.replace(withCheckIn,"initialize_checkin_rules_without_registering_accounts");}
            JsonObject state=ledger.state();for(var e:state.getAsJsonObject("config").getAsJsonArray("products"))item(text(e.getAsJsonObject(),"item"));
            for(String name:List.of("copper","silver","gold"))item(coinId(name));
            JsonObject joined=ledger.state();if(KnownEconomyPlayers.importLocal(joined,server.getPlayerList().getPlayerIo().getPlayerDir().toPath(),server.getServerDirectory().resolve("usercache.json")))ledger.replace(joined,"import_known_joined_players");
            weekly();LOG.info("[QiZhangEconomy] 统一账本就绪，动态商品 {} 件，VIP 与邮箱已启用。",state.getAsJsonObject("config").getAsJsonArray("products").size());
        }catch(IOException|RuntimeException e){unavailable="经济存储或配置异常，交易已保护停用，请管理员核查后重启";LOG.error("[QiZhangEconomy] 初始化失败。",e);}
    }
    public void onLogin(ServerPlayer p){thread();locked.remove(p.getUUID());sessions.remove(p.getUUID());clearOffers(p);lastTrade.remove(p.getUUID());try{JsonObject s=stateFor(p);long now=System.currentTimeMillis();JsonObject known=KnownEconomyPlayers.entries(s);boolean changed=CheckInRules.register(account(s,p),known.has(p.getUUID().toString())?known.getAsJsonObject(p.getUUID().toString()):null,now);changed|=KnownEconomyPlayers.observe(s,p.getUUID(),p.getName().getString(),now);if(changed)adminCommit(s,"observe_joined_player_and_checkin_registration");}catch(IOException|RuntimeException e){protect(p,e);}}
    public void onLogout(ServerPlayer p){thread();sessions.remove(p.getUUID());clearOffers(p);lastTrade.remove(p.getUUID());locked.remove(p.getUUID());}
    public void tick(){thread();if(ledger==null||unavailable!=null||System.currentTimeMillis()<nextWeeklyAt)return;try{weekly();}catch(IOException|RuntimeException e){unavailable="每周经济结算未完成，交易已保护停用，请管理员核查存储";offers.clear();LOG.error("[QiZhangEconomy] 周结算失败。",e);}}
    public JsonObject handleRequest(ServerPlayer p,String action,String id,int count,long sequence){
        thread();Session s=sessions.get(p.getUUID());if(sequence<=0||(s!=null&&s.connection==p.connection&&sequence<=s.sequence))return minimal(p,"重复或无效请求已忽略");
        sessions.put(p.getUUID(),new Session(p.connection,sequence));return handle(p,action,id,count);
    }
    public JsonObject handle(ServerPlayer p,String action,String id,int count){
        thread();String refreshProduct=null;boolean refreshVip=false;
        try{
            if(action==null||id==null||action.length()>16||id.length()>96)throw new IllegalArgumentException("请求格式无效");
            if("open".equals(action)||"mail".equals(action))return snapshot(p,"");
            if(Set.of("checkin_calendar","checkin_prepare","checkin_confirm").contains(action))return checkIn(p,action,id);
            if("service_quote".equals(action)){if(!"repair".equals(id))throw new IllegalArgumentException("不支持的生活服务");return repairQuote(p,"");}
            if("service_buy".equals(action))return buyRepair(p,id,count);
            if("quote".equals(action))return quote(p,id,"");
            if("vip_quote".equals(action))return vipQuote(p,"");
            if("trade".equals(action)){
                Offer offer=offers.get(id);if(offer==null)throw new IllegalArgumentException("报价已失效，请重新打开商品查看价格");
                if(!offer.player.equals(p.getUUID())||offer.connection!=p.connection)throw new IllegalArgumentException("报价不属于当前登录连接");
                refreshProduct=offer.product;refreshVip="vip".equals(offer.side);consumeGroup(offer.group);
                trading(p);JsonObject state=stateFor(p),a=account(state,p);long now=System.currentTimeMillis();
                if(now>offer.expires||num(state,"market_revision")!=offer.marketRevision||num(a,"revision")!=offer.accountRevision||isVip(a,now)!=offer.vip)throw new IllegalArgumentException("价格或账户状态已变化，请查看新报价后再点击");
                if(refreshVip){
                    state.getAsJsonObject("accounts").add(p.getUUID().toString(),VipMembership.purchase(a,now));
                    transact(p,state,"vip",details("vip",1,VIP_COST),()->{});return vipQuote(p,"VIP 已延长 7 天，并获得 1 次永久补签机会；剩余时长最多 28 天");
                }
                JsonObject product=enabledProduct(state,offer.product);String realItem=text(product,"item");Item goods=item(realItem);
                if(offer.count>goods.getDefaultInstance().getMaxStackSize())throw new IllegalArgumentException("数量超过该物品实际堆叠上限");
                boolean buying="buy".equals(offer.side);MarketPricing.Total total=price(state,a,product,offer.count,buying,now);
                if(total.total()!=offer.total||!total.after().toPlainString().equals(offer.afterPrice))throw new IllegalArgumentException("报价已变更，请查看新价格");
                int[] delta;
                if(buying){a.addProperty("balance",MoneyMath.debit(num(a,"balance"),total.total()));Delivery d=delivery(p,goods,offer.count);delta=d.delta;mailAdd(a,realItem,d.remainder);}
                else{
                    requireSell(state.getAsJsonObject("config"),product,a,realItem,offer.count,now);delta=InventoryPlanner.remove(slots(p),realItem,offer.count);
                    a.addProperty("balance",MoneyMath.credit(num(a,"balance"),total.total()));long used=dailyUsed(a,realItem,now);a.addProperty("day",MarketPricing.day(now));a.getAsJsonObject("daily").addProperty(realItem,Math.addExact(used,offer.count));
                }
                state.getAsJsonObject("market").addProperty(realItem,total.after().toPlainString());state.addProperty("market_revision",Math.incrementExact(num(state,"market_revision")));
                transact(p,state,buying?"buy":"sell",details(realItem,offer.count,total.total()),()->apply(p,delta,goods));
                return quote(p,offer.product,(buying?"购买成功，背包放不下的物品已存入邮箱":"回收成功")+"，本批实际"+(buying?"支付 ":"获得 ")+total.total()+" 余额");
            }
            if("withdraw".equals(action)){
                trading(p);allowedCount(count);JsonObject state=stateFor(p),a=account(state,p);long total=MoneyMath.total(MoneyMath.coinValue(id),count);Item coin=item(coinId(id));
                Delivery d=delivery(p,coin,count);if(d.remainder>0)throw new IllegalArgumentException("背包空间不足，请整理后再兑换实物币");
                a.addProperty("balance",MoneyMath.debit(num(a,"balance"),total));transact(p,state,"withdraw",details(coinId(id),count,total),()->apply(p,d.delta,coin));clearOffers(p);
                return snapshot(p,"已兑换 "+count+" 枚"+coinName(id)+"，右键整叠可存入余额");
            }
            if("claim".equals(action)){
                trading(p);allowedCount(count);JsonObject state=stateFor(p),a=account(state,p);JsonArray mail=a.getAsJsonArray("mail");int index=-1;
                for(int i=0;i<mail.size();i++)if(text(mail.get(i).getAsJsonObject(),"id").equals(id)){index=i;break;}
                if(index<0)throw new IllegalArgumentException("这封物品邮件不存在或已领取");
                JsonObject m=mail.get(index).getAsJsonObject();String realItem=text(m,"item");Item goods=item(realItem);int requested=(int)Math.min(count,num(m,"count"));Delivery d=delivery(p,goods,requested);int delivered=requested-d.remainder;
                if(delivered==0)throw new IllegalArgumentException("背包已满，邮件已保留，请整理后领取");
                long remaining=num(m,"count")-delivered;if(remaining==0)mail.remove(index);else m.addProperty("count",remaining);
                transact(p,state,"mail_claim",details(realItem,delivered,0),()->apply(p,d.delta,goods));clearOffers(p);return snapshot(p,"已领取 "+delivered+" 件物品，其余仍保存在邮箱");
            }
            throw new IllegalArgumentException("不支持的经济操作");
        }catch(ArithmeticException e){return refresh(p,refreshProduct,refreshVip,"数值超出安全范围，交易未执行");}
        catch(IllegalArgumentException e){return refresh(p,refreshProduct,refreshVip,e.getMessage());}
        catch(IOException|RuntimeException e){protect(p,e);return minimal(p,"经济账户已保护锁定，请联系管理员核查");}
    }
    public boolean deposit(ServerPlayer p,InteractionHand hand){
        thread();ItemStack held=p.getItemInHand(hand);String id=itemId(held);String coin=switch(id){case "cozylife_economy:copper_coin"->"copper";case "cozylife_economy:silver_coin"->"silver";case "cozylife_economy:gold_coin"->"gold";default->null;};if(coin==null)return false;
        try{trading(p);if(!plain(held))throw new IllegalArgumentException("自定义命名或带附加数据的硬币不能自动存入");int count=MoneyMath.count(held.getCount());long total=MoneyMath.total(MoneyMath.coinValue(coin),count);
            JsonObject state=stateFor(p),a=account(state,p);a.addProperty("balance",MoneyMath.credit(num(a,"balance"),total));transact(p,state,"deposit",details(id,count,total),()->p.setItemInHand(hand,ItemStack.EMPTY));clearOffers(p);
            p.sendSystemMessage(Component.literal("已存入整叠 "+count+" 枚"+coinName(coin)+"，余额增加 "+total));
        }catch(IllegalArgumentException|ArithmeticException e){p.sendSystemMessage(Component.literal(e.getMessage()));}catch(IOException|RuntimeException e){protect(p,e);}return true;
    }
    public JsonObject snapshot(ServerPlayer p,String message){thread();if(!serviceBusy.isEmpty())return minimal(p,"经济服务正在结算，请稍后重试");try{
        JsonObject state=stateFor(p),a=account(state,p),out=base(p,message);long now=System.currentTimeMillis();
        out.addProperty("available",true);out.addProperty("balance",num(a,"balance"));out.addProperty("revision",num(a,"revision"));out.addProperty("market_revision",num(state,"market_revision"));
        out.addProperty("vip_active",isVip(a,now));out.addProperty("vip_expires_at",num(a,"vip_until"));out.addProperty("vip_remaining_seconds",Math.max(0,(num(a,"vip_until")-now+999)/1000));out.addProperty("vip_cost",VIP_COST);
        out.add("checkin",CheckInRules.summary(a,now));
        JsonArray products=new JsonArray();for(var el:state.getAsJsonObject("config").getAsJsonArray("products")){JsonObject product=el.getAsJsonObject();if(product.get("enabled").getAsBoolean())products.add(productInfo(state,a,product,now));}out.add("products",products);
        JsonArray mail=new JsonArray();for(var el:a.getAsJsonArray("mail")){JsonObject m=el.getAsJsonObject().deepCopy();m.addProperty("name",nameFor(state,text(m,"item")));mail.add(m);}out.add("mail_items",mail);out.addProperty("mail_count",mail.size());return out;
    }catch(IOException|RuntimeException e){protect(p,e);return minimal(p,"经济系统暂不可交易，请联系管理员");}}
    private JsonObject quote(ServerPlayer p,String id,String message)throws IOException{
        JsonObject state=stateFor(p),a=account(state,p),product=enabledProduct(state,id);long now=System.currentTimeMillis();clearOffers(p);String group=UUID.randomUUID().toString();JsonObject q=productInfo(state,a,product,now);q.addProperty("expires_at",now+30000);
        for(String side:List.of("buy","sell")){JsonArray variants=new JsonArray();for(int count:COUNTS){JsonObject option=new JsonObject();option.addProperty("count",count);option.addProperty("total",0L);option.addProperty("token","");
            try{Item goods=item(text(product,"item"));if(count>goods.getDefaultInstance().getMaxStackSize())throw new IllegalArgumentException("超过堆叠上限");boolean buying="buy".equals(side);MarketPricing.Total price=price(state,a,product,count,buying,now);option.addProperty("total",price.total());
                if(buying){MoneyMath.debit(num(a,"balance"),price.total());Delivery d=delivery(p,goods,count);mailAdd(a.deepCopy(),text(product,"item"),d.remainder);}
                else{requireSell(state.getAsJsonObject("config"),product,a,text(product,"item"),count,now);InventoryPlanner.remove(slots(p),text(product,"item"),count);}
                String token=UUID.randomUUID().toString();offers.put(token,new Offer(p.getUUID(),p.connection,group,id,side,count,price.total(),price.after().toPlainString(),num(state,"market_revision"),num(a,"revision"),now+30000,isVip(a,now)));option.addProperty("token",token);option.addProperty("enabled",true);option.addProperty("reason","");
            }catch(IllegalArgumentException|ArithmeticException e){option.addProperty("enabled",false);option.addProperty("reason",e.getMessage());}variants.add(option);
        }q.add(side,variants);}JsonObject out=snapshot(p,message);out.add("quote",q);return out;
    }
    private JsonObject vipQuote(ServerPlayer p,String message)throws IOException{
        JsonObject state=stateFor(p),a=account(state,p);long now=System.currentTimeMillis();clearOffers(p);JsonObject offer=new JsonObject();offer.addProperty("cost",VIP_COST);offer.addProperty("token","");boolean active=isVip(a,now),enough=num(a,"balance")>=VIP_COST,renew=VipMembership.canRenew(a,now);
        offer.addProperty("enabled",renew&&enough);offer.addProperty("can_renew",renew);offer.addProperty("remaining_days",VipMembership.remaining(a,now)/(double)VipMembership.DAY);offer.addProperty("makeup_credit_grant",1);offer.addProperty("expires_at",now+30000);offer.addProperty("reason",!renew?"VIP 剩余超过 21 天，暂不能续期；最多累计 28 天":enough?"":"余额不足");
        if(renew&&enough){String token=UUID.randomUUID().toString();offers.put(token,new Offer(p.getUUID(),p.connection,UUID.randomUUID().toString(),"","vip",1,VIP_COST,"",num(state,"market_revision"),num(a,"revision"),now+30000,active));offer.addProperty("token",token);}
        JsonObject out=snapshot(p,message);out.add("vip_offer",offer);return out;
    }
    private JsonObject checkIn(ServerPlayer p,String action,String id)throws IOException {
        java.time.YearMonth month=java.time.YearMonth.from(CheckInRules.today(System.currentTimeMillis()));
        try {
            if("checkin_calendar".equals(action)) {
                if(!id.isEmpty())month=CheckInRules.month(id);
                checkInOffers.clear(p.getUUID());return checkInCalendar(p,month,"");
            }
            if("checkin_prepare".equals(action)) {
                java.time.LocalDate date=CheckInRules.date(id);month=java.time.YearMonth.from(date);
                clearOffers(p);JsonObject state=stateFor(p),a=account(state,p);long now=System.currentTimeMillis();
                CheckInRules.Settlement preview=CheckInRules.settle(a,state.getAsJsonObject("config"),date,now);
                CheckInOffers.Offer<Object> ticket=checkInOffers.issue(p.getUUID(),p.connection,date,a,state.getAsJsonObject("config"),now);
                JsonObject out=checkInCalendar(p,month,"");out.add("checkin_quote",checkInQuote(ticket,preview));return out;
            }
            CheckInOffers.Offer<Object> ticket=checkInOffers.consume(p.getUUID(),p.connection,id);month=java.time.YearMonth.from(ticket.date());
            trading(p);JsonObject state=stateFor(p),a=account(state,p);long now=System.currentTimeMillis();
            checkInOffers.validate(ticket,a,state.getAsJsonObject("config"),now);
            CheckInRules.Settlement result=CheckInRules.settle(a,state.getAsJsonObject("config"),ticket.date(),now);
            state.getAsJsonObject("accounts").add(p.getUUID().toString(),result.account());
            JsonObject detail=details(ticket.date().toString(),1,result.reward());detail.addProperty("makeup",result.makeup());detail.addProperty("vip_bonus_applied",result.vip());detail.addProperty("streak_for_date",result.streak());detail.add("milestones",result.milestones().deepCopy());
            long coins=0;for(var award:result.milestones())coins=Math.addExact(coins,num(award.getAsJsonObject(),"count"));detail.addProperty("milestone_coin_value",Math.multiplyExact(coins,100L));
            transact(p,state,"checkin",detail,()->{});clearOffers(p);
            String message=(result.makeup()?"补签":"签到")+"成功，已获得 "+result.reward()+" 余额"+(result.makeup()?"，消耗 1 次永久补签机会":"")+(coins>0?"；连续签到奖励 "+coins+" 枚银币已存入邮箱":"");
            return checkInCalendar(p,month,message);
        }catch(IllegalArgumentException e){return checkInCalendar(p,month,e.getMessage());}
        catch(ArithmeticException e){return checkInCalendar(p,month,"签到数值超出安全范围，未领取奖励或扣除补签次数，请联系管理员");}
    }
    private JsonObject checkInCalendar(ServerPlayer p,java.time.YearMonth month,String message)throws IOException {
        JsonObject state=stateFor(p),out=snapshot(p,message);out.add("checkin_calendar",CheckInRules.calendar(account(state,p),state.getAsJsonObject("config"),month,System.currentTimeMillis()));
        JsonObject empty=new JsonObject();empty.addProperty("enabled",false);empty.addProperty("token","");empty.addProperty("reason",message==null?"":message);out.add("checkin_quote",empty);return out;
    }
    private JsonObject checkInQuote(CheckInOffers.Offer<Object> ticket,CheckInRules.Settlement result){
        JsonObject q=new JsonObject();q.addProperty("token",ticket.token());q.addProperty("date",ticket.date().toString());q.addProperty("kind",result.makeup()?"makeup":"today");q.addProperty("enabled",true);q.addProperty("reason","");q.addProperty("balance_reward",result.reward());q.addProperty("uses_makeup_credit",result.makeup());q.addProperty("streak_for_date",result.streak());q.addProperty("longest_streak_after",result.longest());q.add("milestones",result.milestones().deepCopy());q.addProperty("vip_bonus_applied",result.vip());q.addProperty("expires_at",ticket.expires());return q;
    }
    private JsonObject refresh(ServerPlayer p,String id,boolean vip,String message){try{if(vip)return vipQuote(p,message);if(id!=null&&!id.isEmpty())return quote(p,id,message);}catch(IOException|RuntimeException ignored){}return snapshot(p,message);}
    private JsonObject productInfo(JsonObject state,JsonObject a,JsonObject p,long now){JsonObject x=new JsonObject();for(String f:List.of("id","item","name","category"))x.add(f,p.get(f));long buy=MarketPricing.buy(current(state,p),MarketCatalog.multiplier(state.getAsJsonObject("config"),p,isVip(a,now)));
        x.addProperty("buy",buy);x.addProperty("sell",p.get("sell_enabled").getAsBoolean()?MarketPricing.independentSell(num(p,"base"),num(p,"sell_base"),current(state,p),buy):0);x.addProperty("base_price",num(p,"base"));x.addProperty("market_price",current(state,p).toPlainString());x.addProperty("stack_max",item(text(p,"item")).getDefaultInstance().getMaxStackSize());x.addProperty("sell_enabled",FinanceRules.enabled(state.getAsJsonObject("config"),"recycle")&&p.get("sell_enabled").getAsBoolean());x.addProperty("daily_sell_remaining",FinanceRules.enabled(state.getAsJsonObject("config"),"daily_recycle_limit")?Math.max(0,num(p,"daily_sell_limit")-dailyUsed(a,text(p,"item"),now)):1000000000L);return x;}
    private MarketPricing.Total price(JsonObject state,JsonObject a,JsonObject p,int count,boolean buy,long now){return FinanceRules.total(state.getAsJsonObject("config"),num(p,"base"),num(p,"sell_base"),current(state,p),MarketCatalog.multiplier(state.getAsJsonObject("config"),p,isVip(a,now)),count,buy);}
    private static BigDecimal current(JsonObject state,JsonObject p){return MarketPricing.decimal(state.getAsJsonObject("market").get(text(p,"item")).getAsString());}
    private static long dailyUsed(JsonObject a,String item,long now){if(!MarketPricing.day(now).equals(text(a,"day")))return 0;return a.getAsJsonObject("daily").has(item)?a.getAsJsonObject("daily").get(item).getAsLong():0;}
    private static void requireSell(JsonObject config,JsonObject p,JsonObject a,String item,int count,long now){if(!FinanceRules.enabled(config,"recycle")||!p.get("sell_enabled").getAsBoolean())throw new IllegalArgumentException("该商品仅出售，不回收");if(FinanceRules.enabled(config,"daily_recycle_limit")&&count>num(p,"daily_sell_limit")-dailyUsed(a,item,now))throw new IllegalArgumentException("今日该物品的回收额度不足，北京时间零点刷新");if(!MarketPricing.day(now).equals(text(a,"day"))){a.addProperty("day",MarketPricing.day(now));a.add("daily",new JsonObject());}}
    private static JsonObject enabledProduct(JsonObject s,String id){JsonObject p=MarketCatalog.product(s.getAsJsonObject("config"),id);if(!p.get("enabled").getAsBoolean())throw new IllegalArgumentException("商品已下架");return p;}
    private static void mailAdd(JsonObject a,String item,int count){if(count==0)return;JsonArray mail=a.getAsJsonArray("mail");for(var e:mail){JsonObject m=e.getAsJsonObject();if(text(m,"item").equals(item)){long next=Math.addExact(num(m,"count"),count);if(next>1000000)throw new IllegalArgumentException("该物品邮箱存量已达上限，请先领取");m.addProperty("count",next);return;}}if(mail.size()>=256)throw new IllegalArgumentException("邮箱已满，请先领取");JsonObject m=new JsonObject();m.addProperty("id",UUID.randomUUID().toString());m.addProperty("item",item);m.addProperty("count",count);mail.add(m);}
    private JsonObject stateFor(ServerPlayer p)throws IOException{if(!serviceBusy.isEmpty())throw new IllegalArgumentException("经济服务正在结算，请稍后重试");if(unavailable!=null||ledger==null)throw new IOException("经济系统不可用");if(locked.contains(p.getUUID()))throw new IOException("账户锁定");if(serviceBusy.contains(p.getUUID()))throw new IllegalArgumentException("正在结算服务，请稍候再操作");if(p.hasDisconnected()||server.getPlayerList().getPlayer(p.getUUID())!=p)throw new IllegalArgumentException("玩家不属于当前在线连接");weekly();JsonObject s=ledger.state(),a=account(s,p);if(!text(a,"marker").equals(liveMarker(p)))throw new IOException("玩家存档与账户收据不一致");return s;}
    private void weekly()throws IOException{JsonObject s=ledger.state();long now=System.currentTimeMillis(),weeks=MarketPricing.weeksSince(num(s,"last_monday"),now);if(weeks>0){Set<String> processed=new HashSet<>();for(var el:s.getAsJsonObject("config").getAsJsonArray("products")){JsonObject p=el.getAsJsonObject();if(processed.add(text(p,"item")))s.getAsJsonObject("market").addProperty(text(p,"item"),FinanceRules.weekly(s.getAsJsonObject("config"),num(p,"base"),current(s,p),weeks).toPlainString());}s.addProperty("last_monday",MarketPricing.monday(now));s.addProperty("market_revision",Math.incrementExact(num(s,"market_revision")));ledger.replace(s,"weekly_policy_settlement");offers.clear();}nextWeeklyAt=java.time.LocalDate.ofEpochDay(num(s,"last_monday")).plusDays(7).atStartOfDay(MarketPricing.ZONE).toInstant().toEpochMilli();}
    private void trading(ServerPlayer p)throws IOException{JsonObject state=stateFor(p);if(!p.isAlive()||p.isCreative()||p.isSpectator())throw new IllegalArgumentException("请在生存或冒险模式下交易");if(p.containerMenu!=p.inventoryMenu||!p.inventoryMenu.getCarried().isEmpty())throw new IllegalArgumentException("请先关闭容器，并放回鼠标上的物品");int now=server.getTickCount();Integer last=lastTrade.get(p.getUUID());if(last!=null&&now-last>=0&&now-last<num(state.getAsJsonObject("config"),"transaction_cooldown_ticks"))throw new IllegalArgumentException("操作有些快，请稍候再试");}
    private void transact(ServerPlayer p,JsonObject after,String action,JsonObject detail,Runnable change)throws IOException{
        if(!serviceBusy.isEmpty())throw new IllegalArgumentException("经济服务正在结算，请稍后重试");
        serviceBusy.add(p.getUUID());try{transactLocked(p,after,action,detail,change);}finally{serviceBusy.remove(p.getUUID());}
    }
    private void transactLocked(ServerPlayer p,JsonObject after,String action,JsonObject detail,Runnable change)throws IOException{
        String previous=text(account(ledger.state(),p),"marker");saveAndVerify(p,previous);List<ItemStack> before=new ArrayList<>();for(int i=0;i<p.getInventory().getContainerSize();i++)before.add(p.getInventory().getItem(i).copy());EconomyMetrics.record(after,p.getUUID(),action,detail,System.currentTimeMillis());String tx=ledger.prepare(p.getUUID(),after,action,detail);boolean applied=false;lastTrade.put(p.getUUID(),server.getTickCount());
        try{setMarker(p,"inflight:"+tx);change.run();applied=true;setMarker(p,tx);p.getInventory().setChanged();saveAndVerify(p,tx);ledger.commit(tx);p.inventoryMenu.broadcastFullState();}
        catch(IOException|RuntimeException e){if(!applied){try{for(int i=0;i<before.size();i++)p.getInventory().setItem(i,before.get(i));setMarker(p,previous);saveAndVerify(p,previous);ledger.recover(uuid->savedReceipt(uuid,false).getStringOr(MARKER,""));}catch(IOException|RuntimeException rollback){setMarker(p,"inflight:"+tx);e.addSuppressed(rollback);}}
            unavailable="经济交易存储未核实，请管理员保留存档和日志后重启恢复";LOG.error("[QiZhangEconomy] 待恢复交易 {} 玩家 {}。",tx,p.getUUID(),e);p.connection.disconnect(Component.literal("经济交易存储尚待核实，为保护物品和余额已断开连接，请联系管理员。"));throw new IOException(unavailable,e);}
    }
    private void saveAndVerify(ServerPlayer p,String marker)throws IOException{String probe=UUID.randomUUID().toString();retained(p).putString(PROBE,probe);server.getPlayerList().getPlayerIo().save(p);CompoundTag saved=savedReceipt(p.getUUID(),true);if(!marker.equals(saved.getStringOr(MARKER,""))||!probe.equals(saved.getStringOr(PROBE,"")))throw new IOException("玩家存档保存探针未确认");}
    private CompoundTag savedReceipt(UUID id,boolean force)throws IOException{Path file=server.getPlayerList().getPlayerIo().getPlayerDir().toPath().resolve(id+".dat");if(!Files.exists(file)){if(force)throw new IOException("玩家存档不存在");return new CompoundTag();}if(force)try(FileChannel c=FileChannel.open(file,StandardOpenOption.WRITE)){c.force(true);}return NbtIo.readCompressed(file,NbtAccounter.create(67108864)).getCompoundOrEmpty("NeoForgeData").getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);}
    private static CompoundTag retained(ServerPlayer p){CompoundTag parent=p.getPersistentData(),child=parent.getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);parent.put(Player.PERSISTED_NBT_TAG,child);return child;}
    private static String liveMarker(ServerPlayer p){return retained(p).getStringOr(MARKER,"");}private static void setMarker(ServerPlayer p,String value){retained(p).putString(MARKER,value);}
    private static JsonObject account(JsonObject s,ServerPlayer p){return MarketLedger.account(s,p.getUUID());}private static boolean isVip(JsonObject a,long now){return num(a,"vip_until")>now;}
    private static long num(JsonObject o,String key){return AccountLedger.number(o,key);}private static String text(JsonObject o,String key){return AccountLedger.text(o,key);}
    private static Item item(String id){return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElseThrow(()->new IllegalArgumentException("物品未注册："+id));}
    private static String itemId(ItemStack s){return s.isEmpty()?"minecraft:air":BuiltInRegistries.ITEM.getKey(s.getItem()).toString();}private static String coinId(String s){return "cozylife_economy:"+s+"_coin";}
    private static String coinName(String id){return switch(id){case "copper"->"铜币";case "silver"->"银币";case "gold"->"金币";default->"货币";};}
    private static void allowedCount(int count){for(int n:COUNTS)if(count==n)return;throw new IllegalArgumentException("可选数量为 1、16、32、64");}
    private static String nameFor(JsonObject s,String id){for(String coin:List.of("copper","silver","gold"))if(coinId(coin).equals(id))return coinName(coin);for(var e:s.getAsJsonObject("config").getAsJsonArray("products")){JsonObject p=e.getAsJsonObject();if(text(p,"item").equals(id))return text(p,"name");}return id;}
    private static boolean plain(ItemStack s){return !s.isEmpty()&&s.isComponentsPatchEmpty()&&!s.isDamageableItem()&&s.get(DataComponents.CONTAINER)==null&&s.get(DataComponents.BUNDLE_CONTENTS)==null&&s.get(DataComponents.CONTAINER_LOOT)==null&&s.get(DataComponents.BLOCK_ENTITY_DATA)==null&&s.get(DataComponents.ENTITY_DATA)==null&&s.get(DataComponents.BUCKET_ENTITY_DATA)==null&&s.get(DataComponents.CUSTOM_DATA)==null&&s.get(DataComponents.CUSTOM_NAME)==null;}
    private static List<InventoryPlanner.Slot> slots(ServerPlayer p){List<InventoryPlanner.Slot> out=new ArrayList<>();for(int i=0;i<36;i++){ItemStack s=p.getInventory().getItem(i);out.add(s.isEmpty()?new InventoryPlanner.Slot("",0,64,true):new InventoryPlanner.Slot(itemId(s),s.getCount(),s.getMaxStackSize(),plain(s)));}return out;}
    private static Delivery delivery(ServerPlayer p,Item item,int count){List<InventoryPlanner.Slot> slots=slots(p);String id=BuiltInRegistries.ITEM.getKey(item).toString();int max=item.getDefaultInstance().getMaxStackSize(),left=count;int[] d=new int[36];for(int i=0;i<36&&left>0;i++){var s=slots.get(i);if(!s.empty()&&s.plain()&&s.item().equals(id)){int n=Math.min(left,Math.max(0,Math.min(max,s.max())-s.count()));d[i]=n;left-=n;}}for(int i=0;i<36&&left>0;i++)if(slots.get(i).empty()){int n=Math.min(max,left);d[i]=n;left-=n;}return new Delivery(d,left);}
    private static void apply(ServerPlayer p,int[] d,Item item){for(int i=0;i<d.length;i++)if(d[i]!=0){ItemStack s=p.getInventory().getItem(i);if(s.isEmpty())p.getInventory().setItem(i,new ItemStack(item,d[i]));else{int n=s.getCount()+d[i];p.getInventory().setItem(i,n==0?ItemStack.EMPTY:s.copyWithCount(n));}}}
    private static JsonObject details(String id,int count,long total){JsonObject d=new JsonObject();d.addProperty("item",id);d.addProperty("count",count);d.addProperty("total",total);return d;}
    private void clearOffers(ServerPlayer p){checkInOffers.clear(p.getUUID());repairOffers.entrySet().removeIf(e->e.getValue().player.equals(p.getUUID())||e.getValue().expires<System.currentTimeMillis());offers.entrySet().removeIf(e->e.getValue().player.equals(p.getUUID())||e.getValue().expires<System.currentTimeMillis());}private void consumeGroup(String group){offers.entrySet().removeIf(e->e.getValue().group.equals(group));}
    private JsonObject base(ServerPlayer p,String message){JsonObject o=new JsonObject();o.addProperty("player_name",p.getName().getString());o.addProperty("message",message==null?"":message);o.addProperty("server_time",System.currentTimeMillis());return o;}
    private JsonObject minimal(ServerPlayer p,String message){JsonObject o=base(p,unavailable==null?message:unavailable);o.addProperty("available",false);o.addProperty("balance",0L);o.addProperty("revision",0L);o.add("products",new JsonArray());o.add("mail_items",new JsonArray());o.addProperty("mail_count",0);o.addProperty("vip_active",false);o.addProperty("vip_remaining_seconds",0L);return o;}
    private void protect(ServerPlayer p,Exception e){if(locked.add(p.getUUID())){LOG.error("[QiZhangEconomy] 玩家 {} 已保护锁定。",p.getUUID(),e);p.sendSystemMessage(Component.literal(unavailable==null?"经济账户已保护锁定，请联系管理员核查":unavailable));}}
    private void thread(){if(!server.isSameThread())throw new IllegalStateException("经济操作必须在服务器主线程");}
    public JsonObject adminState()throws IOException{thread();if(!serviceBusy.isEmpty())throw new IllegalArgumentException("经济服务正在结算，请稍后重试");if(unavailable!=null||ledger==null)throw new IOException(unavailable==null?"经济系统尚未就绪":unavailable);try{weekly();return ledger.state();}catch(IOException e){adminProtect(e);throw e;}}
    public void adminReplace(JsonObject s,String reason)throws IOException{thread();if(unavailable!=null||ledger==null)throw new IOException("经济系统已保护停用");if(num(s.getAsJsonObject("config"),"schema")!=3)throw new IllegalArgumentException("管理配置只接受版本3，旧版仅可在启动时迁移");MarketCatalog.validate(s.getAsJsonObject("config"));for(var e:s.getAsJsonObject("config").getAsJsonArray("products")){String id=text(e.getAsJsonObject(),"item");item(id);officialPrices.checkListing(id,false);}s.addProperty("market_revision",Math.incrementExact(num(s,"market_revision")));
        s.addProperty("admin_config_revision",Math.incrementExact(s.has("admin_config_revision")?num(s,"admin_config_revision"):0L));
        for(var e:s.getAsJsonObject("config").getAsJsonArray("products")){JsonObject p=e.getAsJsonObject();BigDecimal base=BigDecimal.valueOf(num(p,"base"));s.getAsJsonObject("market").addProperty(text(p,"item"),current(s,p).max(base.multiply(FinanceRules.lower(s.getAsJsonObject("config")))).min(base.multiply(FinanceRules.upper(s.getAsJsonObject("config")))).toPlainString());}
        adminCommit(s,"admin_"+reason);offers.clear();repairOffers.clear();}
    private void adminCommit(JsonObject state,String reason)throws IOException{try{ledger.replace(state,reason);}catch(IOException e){adminProtect(e);throw e;}}
    private void adminProtect(IOException e){unavailable="管理员经济写入结果尚待核实，已保护停用；请保留相同 jobUUID 并在核查重启后重试";offers.clear();LOG.error("[QiZhangEconomy] 管理存储失败，拒绝在结果不明时继续写入。",e);}
    public String reloadPermissions()throws IOException {thread();FinancePermissions.of(server).reload();for(ServerPlayer p:server.getPlayerList().getPlayers())server.getCommands().sendCommands(p);return "权限文件校验通过并已重载，在线玩家指令权限已刷新。";}
    public String configFile(String operation)throws IOException {
        JsonObject state=adminState();Path file=server.getServerDirectory().resolve("config/qizhang-finance/managed-market.json");
        long revision=state.has("admin_config_revision")?num(state,"admin_config_revision"):0L;
        if("export".equals(operation)){
            JsonObject out=new JsonObject();out.addProperty("schema",1);out.addProperty("expected_config_revision",revision);out.add("config",state.get("config").deepCopy());
            if(Files.exists(file))Files.copy(file,file.resolveSibling("managed-market-before-export-"+System.currentTimeMillis()+".json"));
            AccountLedger.atomicWrite(file,new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out)+"\n");
            return "已导出 config/qizhang-finance/managed-market.json；修改后先执行 config validate，再执行 config apply。";
        }
        if(!Set.of("validate","apply").contains(operation))throw new IllegalArgumentException("用法：config export、config validate 或 config apply");
        if(!Files.isRegularFile(file)||Files.size(file)>1048576)throw new IllegalArgumentException("配置文件不存在或超过1MB，请先导出");
        JsonObject document=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if(num(document,"schema")!=1||num(document,"expected_config_revision")!=revision)throw new IllegalArgumentException("导出配置已过期，管理配置期间有其他修改；请重新导出后合并，不会覆盖现有配置");
        JsonObject candidate=state.deepCopy(),config=document.getAsJsonObject("config").deepCopy();if(num(config,"schema")!=3)throw new IllegalArgumentException("文件配置必须为版本3，禁止运行中降级");MarketCatalog.validate(config);candidate.add("config",config);
        for(var e:config.getAsJsonArray("products")){JsonObject p=e.getAsJsonObject();String id=text(p,"item");item(id);officialPrices.checkListing(id,false);BigDecimal base=BigDecimal.valueOf(num(p,"base"));BigDecimal old=candidate.getAsJsonObject("market").has(id)?MarketPricing.decimal(candidate.getAsJsonObject("market").get(id).getAsString()):base;candidate.getAsJsonObject("market").addProperty(id,old.max(base.multiply(FinanceRules.lower(config))).min(base.multiply(FinanceRules.upper(config))).toPlainString());}
        MarketLedger.validate(candidate);
        if("validate".equals(operation))return "文件配置校验通过，尚未应用；余额、VIP和已用回收额度保持原值。";
        adminReplace(candidate,"file_config_apply");return "文件配置已原子应用；旧报价已失效，玩家余额、VIP和已用额度已保留。";
    }
    public JsonObject adminExport()throws IOException{
        JsonObject state=adminState(),out=new JsonObject();out.addProperty("revision",num(state,"revision"));out.addProperty("market_revision",num(state,"market_revision"));out.addProperty("server_time",System.currentTimeMillis());
        out.add("config",state.get("config").deepCopy());out.add("market",state.get("market").deepCopy());out.add("official_prices",officialPrices.summary());
        JsonObject limits=new JsonObject();limits.addProperty("grant_max_count",AdminCoinMail.MAX_PER_GRANT);limits.addProperty("mail_max_per_item",AdminCoinMail.MAX_MAIL_ITEM);limits.addProperty("mail_max_entries",AdminCoinMail.MAX_MAIL_ENTRIES);limits.addProperty("grant_jobs_max",AdminCoinMail.MAX_JOBS);out.add("limits",limits);
        out.addProperty("grant_jobs_used",state.has("admin_coin_jobs")?state.getAsJsonObject("admin_coin_jobs").size():0);return out;
    }
    public JsonObject adminPlayers()throws IOException{
        JsonObject state=adminState(),out=new JsonObject();JsonArray players=new JsonArray();
        for(var entry:KnownEconomyPlayers.entries(state).entrySet()){
            UUID uuid=AdminCoinMail.uuid(entry.getKey());JsonObject p=entry.getValue().getAsJsonObject().deepCopy();p.addProperty("uuid",uuid.toString());p.addProperty("online",server.getPlayerList().getPlayer(uuid)!=null);
            JsonObject accounts=state.getAsJsonObject("accounts");JsonArray mail=accounts.has(uuid.toString())?accounts.getAsJsonObject(uuid.toString()).getAsJsonArray("mail"):new JsonArray();
            p.addProperty("mail_count",mail.size());JsonObject coins=new JsonObject();for(String coin:List.of("copper","silver","gold")){long n=0;for(var e:mail){JsonObject m=e.getAsJsonObject();if(coinId(coin).equals(text(m,"item")))n=Math.addExact(n,num(m,"count"));}coins.addProperty(coin,n);}p.add("coin_mail",coins);players.add(p);
        }
        out.add("players",players);out.addProperty("revision",num(state,"revision"));out.addProperty("server_time",System.currentTimeMillis());return out;
    }
    public JsonObject adminGrantCoins(String target,String coin,int count,String jobId)throws IOException{
        JsonObject state=adminState();UUID uuid=KnownEconomyPlayers.resolve(state,target),job=AdminCoinMail.uuid(jobId);item(AdminCoinMail.coinItem(coin));
        AdminCoinMail.Result result=AdminCoinMail.apply(state,uuid,coin,count,job,System.currentTimeMillis());
        if(result.changed()){EconomyMetrics.record(state,uuid,"admin_coin_mail",details(coinId(coin),count,Math.multiplyExact(MoneyMath.coinValue(coin),(long)count)),System.currentTimeMillis());adminCommit(state,"admin_grantcoins:"+job+":"+uuid+":"+coin+":"+count);offers.entrySet().removeIf(e->e.getValue().player.equals(uuid));}
        return result.response();
    }
    public JsonObject adminCoinJob(String jobId)throws IOException{
        UUID job=AdminCoinMail.uuid(jobId);JsonObject state=adminState(),out=new JsonObject();JsonObject jobs=state.has("admin_coin_jobs")?state.getAsJsonObject("admin_coin_jobs"):new JsonObject();
        out.addProperty("job_id",job.toString());out.addProperty("found",jobs.has(job.toString()));out.add("receipt",jobs.has(job.toString())?jobs.get(job.toString()).deepCopy():JsonNull.INSTANCE);return out;
    }
    private void migrateIndependentPrices()throws IOException {
        JsonObject state=ledger.state(),config=state.getAsJsonObject("config");
        if(num(config,"schema")==3)return;
        JsonObject original=config.deepCopy();
        for(var element:config.getAsJsonArray("products")) {
            JsonObject product=element.getAsJsonObject(),estimate=officialPrices.estimate(text(product,"item"));
            long value=Math.min(num(product,"base")-1,num(estimate,"default_sell_price"));
            product.addProperty("sell_base",Math.max(0,value));
            if(value==0)product.addProperty("sell_enabled",false);
        }
        config.addProperty("schema",3);config.addProperty("pricing_library_revision",officialPrices.summary().get("revision").getAsString());
        Path backup=server.getWorldPath(LevelResource.ROOT).resolve("cozylife-economy-v2/market-before-independent-recycle.json");
        if(!Files.exists(backup))AccountLedger.atomicWrite(backup,new GsonBuilder().setPrettyPrinting().create().toJson(original)+"\n");
        state.addProperty("market_revision",Math.incrementExact(num(state,"market_revision")));
        ledger.replace(state,"owner_requested_independent_recycle_base_v3");
        LOG.info("[QiZhangEconomy] 已切换逐物品独立回收基准，保留购买价、余额、每日额度及已有邮件。");
    }
    public JsonObject adminStats()throws IOException {JsonObject state=adminState();JsonObject result=EconomyMetrics.report(state,server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet()),System.currentTimeMillis());FinanceProfiles.describe(state.getAsJsonObject("config"),result);return result;}
    public boolean belongsTo(MinecraftServer expected){return expected==server;}
    public boolean apiHasAccount(UUID player)throws IOException {return adminState().getAsJsonObject("accounts").has(Objects.requireNonNull(player).toString());}
    public long apiBalance(UUID player)throws IOException {thread();Objects.requireNonNull(player);if(prepaidBalanceView!=null){JsonObject accounts=prepaidBalanceView.getAsJsonObject("accounts");return accounts.has(player.toString())?num(accounts.getAsJsonObject(player.toString()),"balance"):0;}return num(MarketLedger.account(adminState(),player),"balance");}
    public JsonObject apiMutation(UUID request,UUID player,UUID other,long amount,String operation,String reason)throws IOException {
        JsonObject state=adminState();if(serviceBusy.contains(player)||(other!=null&&serviceBusy.contains(other)))throw new IllegalArgumentException("账户正在结算其他服务");
        JsonObject receipt=ApiTransactions.apply(state,request,player,other,amount,operation,reason,System.currentTimeMillis());
        if(!receipt.get("replay").getAsBoolean()) {EconomyMetrics.record(state,player,"api_"+operation,details("api",1,amount),System.currentTimeMillis());adminCommit(state,"api_"+operation+":"+request+":"+player);offers.clear();repairOffers.clear();}
        return receipt;
    }
    public JsonObject adminEstimate(String id)throws IOException {
        JsonObject state=adminState(),out=new JsonObject();out.add("estimate",officialPrices.estimate(id));
        out.addProperty("revision",num(state,"revision"));out.addProperty("market_revision",num(state,"market_revision"));return out;
    }
    public long resolveOfficialPrice(String id){return officialPrices.automaticPrice(id);}
    public JsonObject officialEstimate(String id){return officialPrices.estimate(id);}
    public void checkOfficialListing(String id,boolean automatic){officialPrices.checkListing(id,automatic);}
    private JsonObject repairQuote(ServerPlayer p,String message)throws IOException {
        JsonObject state=stateFor(p),a=account(state,p);clearOffers(p);
        JsonObject out=snapshot(p,message),q=new JsonObject();q.addProperty("service","repair");q.addProperty("name","装备保养");q.addProperty("id","");q.addProperty("enabled",false);
        try {
            ItemStack held=p.getMainHandItem();if(held.isEmpty()||!held.isDamageableItem()||held.getDamageValue()<1||held.getCount()!=1)throw new IllegalArgumentException("请主手持有一件已损坏的可修理装备");
            int missing=held.getDamageValue(),maximum=held.getMaxDamage();long fee=officialPrices.repairPrice(itemId(held),missing,maximum),expires=System.currentTimeMillis()+30000;
            q.addProperty("item",itemId(held));q.addProperty("item_name",held.get(DataComponents.CUSTOM_NAME)!=null?held.getHoverName().getString():officialPrices.estimate(itemId(held)).get("name").getAsString());q.addProperty("fee",fee);q.addProperty("missing_durability",missing);q.addProperty("max_durability",maximum);q.addProperty("expires_at",expires);
            if(num(a,"balance")<fee)throw new IllegalArgumentException("余额不足");
            String token=UUID.randomUUID().toString();repairOffers.put(token,new RepairOffer(p.getUUID(),p.connection,held.copy(),p.getInventory().getSelectedSlot(),fee,num(a,"revision"),expires));
            q.addProperty("id",token);q.addProperty("enabled",true);q.addProperty("reason","");
        }catch(IllegalArgumentException ex){q.addProperty("reason",ex.getMessage());}
        out.add("service_quote",q);return out;
    }
    private JsonObject buyRepair(ServerPlayer p,String token,int count)throws IOException {
        RepairOffer quote=repairOffers.remove(token);
        if(quote==null||count!=1)throw new IllegalArgumentException("服务报价已失效，请重新查看费用");
        if(!quote.player.equals(p.getUUID())||quote.connection!=p.connection)throw new IllegalArgumentException("服务报价不属于当前登录连接");
        clearOffers(p);trading(p);JsonObject state=stateFor(p),a=account(state,p);
        if(System.currentTimeMillis()>quote.expires||num(a,"revision")!=quote.accountRevision||p.getInventory().getSelectedSlot()!=quote.slot||!ItemStack.matches(p.getMainHandItem(),quote.held))throw new IllegalArgumentException("余额或手持装备已变化，请重新查看修理费用");
        long fee=officialPrices.repairPrice(itemId(quote.held),quote.held.getDamageValue(),quote.held.getMaxDamage());if(fee!=quote.fee)throw new IllegalArgumentException("修理价格发生变化，请重新报价");
        a.addProperty("balance",MoneyMath.debit(num(a,"balance"),fee));
        transact(p,state,"service_repair",details(itemId(quote.held),1,fee),()->{ItemStack repaired=quote.held.copy();repaired.setDamageValue(0);p.setItemInHand(InteractionHand.MAIN_HAND,repaired);});
        return repairQuote(p,"装备保养完成，已支付 "+fee+" 余额；附魔与其他物品数据已保留");
    }
    public void checkTeleportFunds(ServerPlayer p,long fee)throws IOException {
        thread();if(fee<0||fee>1_000_000_000L)throw new IllegalArgumentException("传送费用超出安全范围");
        JsonObject a=account(stateFor(p),p);if(!p.isAlive()||p.isSpectator())throw new IllegalArgumentException("当前状态不能付费传送");
        MoneyMath.debit(num(a,"balance"),fee);
    }
    /** The destination and ledger receipt are saved together before the debit is committed. */
    public boolean paidTeleport(ServerPlayer p,long fee,String kind,java.util.function.BooleanSupplier transfer)throws IOException {
        if(kind==null||kind.isBlank()||kind.length()>128||transfer==null)throw new IllegalArgumentException("传送结算说明或回调无效");
        thread();checkTeleportFunds(p,fee);JsonObject after=stateFor(p),a=account(after,p);
        a.addProperty("balance",MoneyMath.debit(num(a,"balance"),fee));
        serviceBusy.add(p.getUUID());try{return paidTeleportLocked(p,fee,kind,transfer,after);}finally{prepaidBalanceView=null;serviceBusy.remove(p.getUUID());}
    }
    private boolean paidTeleportLocked(ServerPlayer p,long fee,String kind,java.util.function.BooleanSupplier transfer,JsonObject after)throws IOException {
        String previous=liveMarker(p),dimension=p.level().dimension().identifier().toString();
        net.minecraft.world.phys.Vec3 origin=p.position();
        saveAndVerify(p,previous);JsonObject detail=details(kind,1,fee);detail.addProperty("from_dimension",dimension);detail.addProperty("from_x",origin.x);detail.addProperty("from_y",origin.y);detail.addProperty("from_z",origin.z);
        detail.addProperty("settlement_policy","prepay_then_refund_on_failure");
        EconomyMetrics.record(after,p.getUUID(),"paid_teleport",detail,System.currentTimeMillis());String tx;try{tx=ledger.prepare(p.getUUID(),after,"paid_teleport",detail);}catch(IOException ex){adminProtect(ex);throw ex;}
        // The durable pending record contains the debit before native teleport runs. No second spend is possible.
        prepaidBalanceView=after.deepCopy();
        try {
            setMarker(p,"inflight:"+tx);
            boolean success;
            try{success=transfer.getAsBoolean();}catch(RuntimeException failure){
                if(dimension.equals(p.level().dimension().identifier().toString())&&p.position().distanceToSqr(origin)<=0.000001){LOG.warn("[QiZhangAmbitionFinance] 原生传送未移动玩家，退回预扣费用。",failure);success=false;}else throw failure;
            }
            if(!success) {
                if(!dimension.equals(p.level().dimension().identifier().toString())||p.position().distanceToSqr(origin)>0.000001)throw new IOException("传送返回失败但玩家位置已改变，保留待核实收据");
                setMarker(p,previous);saveAndVerify(p,previous);ledger.recover(uuid->savedReceipt(uuid,false).getStringOr(MARKER,""));prepaidBalanceView=null;p.sendSystemMessage(Component.literal("传送未成功，已退回本次预扣的 "+fee+" 余额。"));return false;
            }
            setMarker(p,tx);saveAndVerify(p,tx);verifySavedPosition(p);ledger.commit(tx);clearOffers(p);return true;
        }catch(IOException|RuntimeException e) {
            unavailable="传送结算结果尚待核实，已保护停用，请管理员保留日志后检查";
            LOG.error("[QiZhangEconomy] 付费传送待恢复 {} 玩家 {}",tx,p.getUUID(),e);
            p.connection.disconnect(Component.literal(unavailable));throw new IOException(unavailable,e);
        }
    }
    private void verifySavedPosition(ServerPlayer p)throws IOException {
        Path file=server.getPlayerList().getPlayerIo().getPlayerDir().toPath().resolve(p.getUUID()+".dat");
        CompoundTag saved=NbtIo.readCompressed(file,NbtAccounter.create(67108864));ListTag pos=saved.getListOrEmpty("Pos");
        if(!saved.getStringOr("Dimension","").equals(p.level().dimension().identifier().toString())||pos.size()!=3
            ||Math.abs(pos.getDoubleOr(0,Double.NaN)-p.getX())>0.000001||Math.abs(pos.getDoubleOr(1,Double.NaN)-p.getY())>0.000001||Math.abs(pos.getDoubleOr(2,Double.NaN)-p.getZ())>0.000001
            ||!Double.isFinite(pos.getDoubleOr(0,Double.NaN))||!Double.isFinite(pos.getDoubleOr(1,Double.NaN))||!Double.isFinite(pos.getDoubleOr(2,Double.NaN)))throw new IOException("传送后存档位置核验失败");
    }
    @Override public void close(){if(ledger!=null)try{ledger.close();}catch(IOException e){LOG.error("[QiZhangEconomy] 关闭账本失败。",e);}ledger=null;offers.clear();repairOffers.clear();checkInOffers.clear();serviceBusy.clear();sessions.clear();locked.clear();lastTrade.clear();}
}

package cozylife.economy.server;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Greedy command tail deliberately accepts unquoted #001 product numbers. */
public final class EconomyCommands {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("QiZhangEconomy");
    private static final java.util.Set<String> API_COMMANDS=java.util.Set.of("list","show","enable","category","price","step","recycle","limit","activity","add","addlive","sellprice","estimate","profile","rule");
    private EconomyCommands(){}
    public static void register(RegisterCommandsEvent event){
        var node=event.getDispatcher().register(Commands.literal("qzeconomy").requires(FinancePermissions::canUseRoot)
            .executes(c->{c.getSource().sendSuccess(()->Component.literal(help()),false);return 1;})
            .then(Commands.argument("operation",StringArgumentType.greedyString()).executes(c->{
                String input=StringArgumentType.getString(c,"operation");
                try{FinancePermissions.require(c.getSource(),input);}catch(IllegalArgumentException denied){c.getSource().sendFailure(Component.literal(denied.getMessage()));return 0;}
                if(input.startsWith("api ")){JsonObject reply=api(input);String wire="QZECONOMY_JSON "+reply;LOG.info("{}",wire);if(c.getSource().getEntity()!=null)c.getSource().sendSuccess(()->Component.literal(wire),false);return reply.get("ok").getAsBoolean()?1:0;}
                try{EconomyService service=service();if(service==null)throw new IllegalArgumentException("经济系统尚未就绪");String reply=executeHuman(service,input);c.getSource().sendSuccess(()->Component.literal(reply),true);return 1;}
                catch(Exception ex){c.getSource().sendFailure(Component.literal("经济管理操作未完成："+friendlyError(ex)));return 0;}
            })));
        event.getDispatcher().register(Commands.literal("qzfinance").requires(FinancePermissions::canUseRoot).redirect(node));
    }
    private static EconomyService service()throws ReflectiveOperationException{
        return (EconomyService)Class.forName("cozylife.economy.CozyEconomy").getMethod("service").invoke(null);
    }
    private static String help(){return "QiZhangEconomy 商城管理（商品编号示例：#001）：\n"
        +"list：查看商品目录；show #001：查看商品详情。\n"
        +"enable #001 true/false：上架或下架；recycle #001 true/false：开启或关闭回收。\n"
        +"category #001 分类：设置分类。building=建筑，ores=矿物，farming=农渔，equipment=装备，modded=模组，rare=珍品。\n"
        +"price #001 官方整数价：调整基准价格；step 0.0001：设置逐件成交的浮动比例。\n"
        +"config export/validate/apply：导出、校验、应用配置文件；permissions reload：重载权限文件。\n"
        +"rule 调控项 true/false或数值：设置调控开关与回调比例、市价上下限。\n"
        +"profile small/medium/large：切换金融档位（推荐依据近7天活跃人数），不重置余额或额度。\n"
        +"limit #001 每日回收件数：设置每位玩家的日回收额度。\n"
        +"activity global true/false 折扣：全场活动；activity #001 true/false 折扣：单品活动。\n"
        +"add/addlive 物品ID 买价|auto 分类 每日额度|auto 回收基准|auto 中文名：新增草稿/直接上架，默认不回收。\n"
        +"estimate 物品ID：查询完整官方物价库；price/sellprice #001 auto：分别恢复官方买价/回收基准。\n"
        +"grantcoins 玩家名或UUID copper/silver/gold 数量 jobUUID：发实物币邮件，不增加余额；copper=铜币，silver=银币，gold=金币。\n"
        +"开关输入 true 表示开启，false 表示关闭；折扣必须大于 0 且不超过 1，0.9 表示九折。\n"
        +"api requestUUID export/players/stats/estimate/command/grantcoins/job/rewards：面板接口，参数和回执字段保持原格式。";}
    private static String friendlyError(Exception ex){
        if(ex instanceof NumberFormatException)return "数字格式无效或数值超出范围；价格、数量、额度填写整数，折扣和浮动比例填写小数";
        if(ex instanceof ArithmeticException)return "计算结果超出安全范围，或该参数必须填写整数";
        if(ex instanceof net.minecraft.IdentifierException)return "物品或资源标识格式无效，请使用命名空间:路径，例如 minecraft:diamond";
        String message=ex.getMessage();
        if(message!=null&&(message.startsWith("Invalid UUID")||message.startsWith("UUID string too large")))return "UUID 格式无效，请填写完整的标准 UUID";
        if(message!=null&&!message.isEmpty()&&(Character.UnicodeScript.of(message.codePointAt(0))==Character.UnicodeScript.HAN||message.startsWith("VIP")||message.startsWith("UUID ")||message.startsWith("jobUUID ")))return message;
        LOG.warn("QiZhangEconomy 管理操作失败",ex);
        return "参数格式错误或服务暂不可用，请检查命令帮助；详细原因已记录到服务器日志";
    }
    private static String categoryName(String id){return switch(id){case "building"->"建筑";case "ores"->"矿物";case "farming"->"农渔";case "equipment"->"装备";case "modded"->"模组";case "rare"->"珍品";default->id;};}
    private static String productSummary(JsonObject p){return p.get("id").getAsString()+" · "+p.get("name").getAsString()
        +"\n物品标识："+p.get("item").getAsString()+"\n分类："+categoryName(p.get("category").getAsString())
        +"\n官方买价："+p.get("base").getAsLong()+" 点；回收基准："+(p.has("sell_base")?p.get("sell_base").getAsLong():0)+" 点；每日回收额度："+p.get("daily_sell_limit").getAsLong()+" 件"
        +"\n状态："+(p.get("enabled").getAsBoolean()?"已上架":"已下架")+"；回收："+(p.get("sell_enabled").getAsBoolean()?"开启":"关闭")
        +"\n单品活动："+(p.get("activity_enabled").getAsBoolean()?"开启":"关闭")+"；活动价格乘数："+p.get("activity_discount").getAsString();}
    private static String executeHuman(EconomyService service,String input)throws Exception{
        // Parsing/authorization/transaction paths stay in the original execute() implementation.
        String reply=execute(service,input);String op=input.trim().split("\\s+",2)[0];
        if("show".equals(op))return productSummary(com.google.gson.JsonParser.parseString(reply).getAsJsonObject());
        if("grantcoins".equals(op)){
            JsonObject r=com.google.gson.JsonParser.parseString(reply).getAsJsonObject();
            String coin=switch(r.get("coin").getAsString()){case "copper"->"铜币";case "silver"->"银币";case "gold"->"金币";default->"货币";};
            return (r.get("replay").getAsBoolean()?"此任务已完成，本次没有重复投递。":"已投递实物币邮件。")
                +"\n玩家 UUID："+r.get("target_uuid").getAsString()+"；数量："+r.get("count").getAsInt()+" 枚"+coin
                +"\n余额未改变；任务编号："+r.get("job_id").getAsString()+"；邮件编号："+r.get("mail_id").getAsString();
        }
        return reply;
    }
    private static JsonObject api(String input){
        JsonObject reply=new JsonObject();reply.addProperty("schema",1);reply.addProperty("request_id","");reply.addProperty("operation","");reply.addProperty("ok",false);reply.add("data",com.google.gson.JsonNull.INSTANCE);reply.add("error",com.google.gson.JsonNull.INSTANCE);
        try{
            if(input.length()>4096||input.indexOf('\n')>=0||input.indexOf('\r')>=0)throw new IllegalArgumentException("管理请求过长或包含换行");
            String[] head=input.trim().split("\\s+",4);if(head.length<3)throw new IllegalArgumentException("用法：api requestUUID export/players/command/grantcoins 参数");
            reply.addProperty("request_id",AdminCoinMail.uuid(head[1]).toString());String op=head[2],tail=head.length==4?head[3]:"";reply.addProperty("operation",op);
            EconomyService service=service();if(service==null)throw new java.io.IOException("经济系统尚未就绪");JsonObject data;
            switch(op){
                case "export"->{if(!tail.isEmpty())throw new IllegalArgumentException("export 不需要其他参数");data=service.adminExport();}
                case "stats"->{if(!tail.isEmpty())throw new IllegalArgumentException("stats 不需要其他参数");data=service.adminStats();}
                case "estimate"->{String[] args=tail.split("\\s+");length(args,1);data=service.adminEstimate(args[0]);}
                case "players"->{if(!tail.isEmpty())throw new IllegalArgumentException("players 不需要其他参数");data=service.adminPlayers();}
                case "job"->{String[] args=tail.split("\\s+");length(args,1);data=service.adminCoinJob(args[0]);}
                case "rewards"->{String[] args=tail.split("\\s+");length(args,1);data=CoinRewards.exportTableProof(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer(),args[0]);}
                case "grantcoins"->{String[] args=tail.split("\\s+");length(args,4);data=service.adminGrantCoins(args[0],args[1],Integer.parseInt(args[2]),args[3]);}
                case "command"->{String operation=tail.split("\\s+",2)[0];if(!API_COMMANDS.contains(operation))throw new IllegalArgumentException("面板只能执行白名单商城管理操作");String message=execute(service,tail);data=new JsonObject();data.addProperty("message",message);JsonObject state=service.adminState();data.addProperty("revision",state.get("revision").getAsLong());data.addProperty("market_revision",state.get("market_revision").getAsLong());}
                default->throw new IllegalArgumentException("不支持的管理接口操作");
            }
            reply.addProperty("ok",true);reply.add("data",data);
        }catch(Exception e){JsonObject error=new JsonObject();error.addProperty("code",e instanceof java.io.IOException?"STORAGE_UNAVAILABLE":"REJECTED");error.addProperty("message",friendlyError(e));reply.add("error",error);}
        return reply;
    }
    public static String execute(EconomyService service,String input)throws Exception{
        if(input==null||input.length()>4096||input.indexOf('\n')>=0||input.indexOf('\r')>=0)throw new IllegalArgumentException("管理命令格式错误");
        String[] args=input.trim().split("\\s+");if(args.length==0)return help();String op=args[0];
        if("grantcoins".equals(op)){length(args,5);return service.adminGrantCoins(args[1],args[2],Integer.parseInt(args[3]),args[4]).toString();}
        JsonObject state=service.adminState(),config=state.getAsJsonObject("config");
        if("list".equals(op)){StringBuilder out=new StringBuilder("QiZhangEconomy 商品：");for(var e:config.getAsJsonArray("products")){JsonObject p=e.getAsJsonObject();out.append('\n').append(p.get("id").getAsString()).append(' ').append(p.get("name").getAsString()).append(" · ").append(p.get("enabled").getAsBoolean()?"上架":"下架");}return out.toString();}
        if("show".equals(op)){length(args,2);return MarketCatalog.product(config,args[1]).toString();}
        if("estimate".equals(op)){length(args,2);JsonObject e=service.officialEstimate(args[1]);return e.get("name").getAsString()+"\n物品："+args[1]+"\n本服官方买价："+e.get("official_price").getAsLong()+"；回收基准："+e.get("default_sell_price").getAsLong()+"；每日建议额度："+e.get("default_daily_sell_limit").getAsLong()+"\n依据："+e.get("source").getAsString()+"\n上架说明："+e.get("reason").getAsString();}
        if("config".equals(op)){length(args,2);return service.configFile(args[1]);}
        if("permissions".equals(op)){length(args,2);if(!"reload".equals(args[1]))throw new IllegalArgumentException("用法：permissions reload");return service.reloadPermissions();}
        if("rule".equals(op)){length(args,3);FinanceRules.set(config,args[1],args[2]);}
        else if("profile".equals(op)){length(args,2);FinanceProfiles.apply(config,args[1]);}
        else if("step".equals(op)){length(args,2);config.addProperty("step",MarketPricing.decimal(args[1]).toPlainString());}
        else if("activity".equals(op)){length(args,4);boolean enabled=bool(args[2]);String discount=MarketPricing.decimal(args[3]).toPlainString();
            if("global".equals(args[1])){config.addProperty("activity_enabled",enabled);config.addProperty("activity_discount",discount);}
            else{JsonObject p=MarketCatalog.product(config,args[1]);for(var alias:aliases(config,p)){alias.addProperty("activity_enabled",enabled);alias.addProperty("activity_discount",discount);}}
        }
        else if("add".equals(op)||"addlive".equals(op)){
            if(args.length<6)throw new IllegalArgumentException("用法：add/addlive 物品ID 买价|auto 分类 每日额度|auto 回收基准|auto 中文名");
            var parsed=net.minecraft.resources.Identifier.parse(args[1]);if(!BuiltInRegistries.ITEM.containsKey(parsed))throw new IllegalArgumentException("真实物品ID不存在");
            boolean automatic="auto".equals(args[2]);service.checkOfficialListing(args[1],automatic);JsonObject estimate=service.officialEstimate(args[1]);
            boolean currentSyntax=args.length>=7&&(args[5].matches("[0-9]+")||"auto".equals(args[5]));
            long price=automatic?service.resolveOfficialPrice(args[1]):Long.parseLong(args[2]);
            long cap="auto".equals(args[4])?estimate.get("default_daily_sell_limit").getAsLong():Long.parseLong(args[4]);
            long sell=currentSyntax&&!"auto".equals(args[5])?Long.parseLong(args[5]):estimate.get("default_sell_price").getAsLong();
            for(var e:config.getAsJsonArray("products"))if(e.getAsJsonObject().get("item").getAsString().equals(args[1]))throw new IllegalArgumentException("已有该物品，请使用原商品编号管理");
            int max=0;for(var e:config.getAsJsonArray("products"))max=Math.max(max,Integer.parseInt(e.getAsJsonObject().get("id").getAsString().substring(1)));
            JsonObject product=new JsonObject();product.addProperty("id",String.format("#%03d",max+1));product.addProperty("item",args[1]);product.addProperty("base",price);product.addProperty("category",args[3]);product.addProperty("daily_sell_limit",cap);product.addProperty("sell_base",sell);
            product.addProperty("name",String.join(" ",java.util.Arrays.copyOfRange(args,currentSyntax?6:5,args.length)));product.addProperty("enabled","addlive".equals(op));product.addProperty("sell_enabled",false);product.addProperty("activity_enabled",false);product.addProperty("activity_discount","1");
            product.addProperty("pricing_source",automatic?"official":"manual");product.addProperty("pricing_library_revision",estimate.get("library_revision").getAsString());
            config.getAsJsonArray("products").add(product);state.getAsJsonObject("market").addProperty(args[1],Long.toString(price));
        }
        else{
            length(args,3);JsonObject p=MarketCatalog.product(config,args[1]);
            switch(op){
                case "enable"->p.addProperty("enabled",bool(args[2]));
                case "category"->p.addProperty("category",args[2]);
                case "price"->{long price="auto".equals(args[2])?service.resolveOfficialPrice(p.get("item").getAsString()):Long.parseLong(args[2]);for(var alias:aliases(config,p))alias.addProperty("base",price);state.getAsJsonObject("market").addProperty(p.get("item").getAsString(),Long.toString(price));}
                case "sellprice"->{long value="auto".equals(args[2])?service.officialEstimate(p.get("item").getAsString()).get("default_sell_price").getAsLong():Long.parseLong(args[2]);for(var alias:aliases(config,p)){alias.addProperty("sell_base",value);if(value==0)alias.addProperty("sell_enabled",false);}}
                case "recycle"->{boolean enabled=bool(args[2]);for(var alias:aliases(config,p))alias.addProperty("sell_enabled",enabled);}
                case "limit"->{long cap=Long.parseLong(args[2]);for(var alias:aliases(config,p))alias.addProperty("daily_sell_limit",cap);}
                default->throw new IllegalArgumentException(help());
            }
        }
        service.adminReplace(state,op);return "已原子保存并生效，现有报价已更新；回收基准独立设置，正常生产加工利润允许。";
    }
    private static java.util.List<JsonObject> aliases(JsonObject config,JsonObject p){var list=new java.util.ArrayList<JsonObject>();for(var e:config.getAsJsonArray("products")){JsonObject x=e.getAsJsonObject();if(x.get("item").equals(p.get("item")))list.add(x);}return list;}
    private static boolean bool(String s){if("true".equals(s))return true;if("false".equals(s))return false;throw new IllegalArgumentException("开关必须是 true 或 false");}
    private static void length(String[] args,int n){if(args.length!=n)throw new IllegalArgumentException("参数数量错误；输入 /qzeconomy 查看帮助");}
}

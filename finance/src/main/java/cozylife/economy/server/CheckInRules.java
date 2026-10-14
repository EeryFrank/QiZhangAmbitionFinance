package cozylife.economy.server;

import com.google.gson.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Pure check-in rules. No clock, player, disk or network is consulted here. */
public final class CheckInRules {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static final String SILVER="cozylife_economy:silver_coin";
    private static final BigDecimal MAX=BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal PAYOUT_LIMIT=MAX.add(BigDecimal.ONE);
    private CheckInRules(){}
    public record Settlement(JsonObject account,long reward,boolean makeup,int streak,int longest,JsonArray milestones,boolean vip){}
    public static JsonObject defaults(){
        JsonObject c=new JsonObject();c.addProperty("enabled",true);c.addProperty("base_reward",100L);c.addProperty("growth_factor","1.01");c.addProperty("vip_multiplier","1.05");
        JsonObject milestones=new JsonObject();int[] days={7,14,30,60,100},coins={1,3,10,20,50};for(int i=0;i<days.length;i++)milestones.addProperty(Integer.toString(days[i]),coins[i]);c.add("milestones",milestones);return c;
    }
    public static JsonObject settings(JsonObject config){return config.has("checkin")?config.getAsJsonObject("checkin"):defaults();}
    public static void validateConfig(JsonObject config){
        JsonObject c=settings(config);if(!c.has("enabled")||!c.get("enabled").isJsonPrimitive()||!c.getAsJsonPrimitive("enabled").isBoolean())throw new IllegalArgumentException("签到开关必须为布尔值");
        if(n(c,"base_reward")<1)throw new IllegalArgumentException("签到基础奖励必须为正整数");
        factor(c,"growth_factor");factor(c,"vip_multiplier");JsonObject milestones=c.getAsJsonObject("milestones");if(milestones==null||milestones.size()>32)throw new IllegalArgumentException("签到里程碑最多 32 档");
        for(var e:milestones.entrySet()){threshold(e.getKey());long count=e.getValue().getAsBigDecimal().longValueExact();if(count<1||count>1_000_000)throw new IllegalArgumentException("里程碑银币数量必须在 1 至 1000000 之间");}
    }
    private static BigDecimal factor(JsonObject c,String field){
        String s=c.get(field).getAsString();if(!s.matches("[0-9]{1,19}(\\.[0-9]{1,12})?"))throw new IllegalArgumentException("签到倍率必须为最多 12 位小数的普通十进制数");
        BigDecimal d=new BigDecimal(s);if(d.compareTo(BigDecimal.ONE)<0||d.compareTo(MAX)>0)throw new IllegalArgumentException("签到增长及 VIP 倍率不得小于 1 或越过整数安全范围");return d;
    }
    public static LocalDate date(String value){
        try{if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();LocalDate d=LocalDate.parse(value);if(d.getYear()<1||!d.toString().equals(value))throw new IllegalArgumentException();return d;}
        catch(DateTimeParseException|IllegalArgumentException e){throw new IllegalArgumentException("日期格式无效，请使用真实的 年-月-日，例如 2026-10-14");}
    }
    public static YearMonth month(String value){
        try{if(value==null||!value.matches("[0-9]{4}-[0-9]{2}"))throw new IllegalArgumentException();YearMonth m=YearMonth.parse(value);if(m.getYear()<1||!m.toString().equals(value))throw new IllegalArgumentException();return m;}
        catch(DateTimeParseException|IllegalArgumentException e){throw new IllegalArgumentException("月份格式无效，请使用真实的 年-月，例如 2026-10");}
    }
    public static LocalDate today(long now){return Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();}
    /** Only real login calls this; imported accounts or API credits must not establish registration. */
    public static boolean register(JsonObject account,JsonObject known,long now){
        if(account.has("checkin"))return false;
        long registered=now;String source="first_login_this_version";
        if(known!=null)for(String field:List.of("first_join_at","first_seen"))if(known.has(field)){
            try{long value=known.get(field).getAsBigDecimal().longValueExact();if(value>0&&value<=now&&today(value).getYear()>=1){registered=value;source="verified_"+field;break;}}catch(RuntimeException ignored){}
        }
        JsonObject c=new JsonObject();c.addProperty("schema",1);c.addProperty("registered_date",today(registered).toString());c.addProperty("registered_at",registered);c.addProperty("registration_source",source);c.addProperty("makeup_credits",0L);c.add("signed",new JsonObject());c.add("claimed_milestones",new JsonObject());account.add("checkin",c);return true;
    }
    public static JsonObject requireRegistered(JsonObject account){if(!account.has("checkin"))throw new IllegalArgumentException("签到账户尚未完成首次登录登记，请重新登录");return account.getAsJsonObject("checkin");}
    public static void validateAccount(JsonObject account){
        if(!account.has("checkin"))return;JsonObject c=requireRegistered(account);if(n(c,"schema")!=1||n(c,"makeup_credits")<0||n(c,"registered_at")<0)throw new IllegalArgumentException("签到账户版本或补签次数无效");
        LocalDate start=date(t(c,"registered_date"));if(!today(n(c,"registered_at")).equals(start))throw new IllegalArgumentException("签到注册时间与日期不一致");
        if(!Set.of("first_login_this_version","verified_first_join_at","verified_first_seen").contains(t(c,"registration_source")))throw new IllegalArgumentException("签到注册来源无效");
        for(var e:c.getAsJsonObject("signed").entrySet()){
            LocalDate d=date(e.getKey());JsonObject record=e.getValue().getAsJsonObject();if(d.isBefore(start)||n(record,"reward")<0||n(record,"settled_at")<n(c,"registered_at")||n(record,"streak_at_settlement")<1)throw new IllegalArgumentException("已签到日期记录无效");
            LocalDate settlement=today(n(record,"settled_at"));if(d.isAfter(settlement)||record.get("makeup").getAsBoolean()!=d.isBefore(settlement))throw new IllegalArgumentException("签到与补签结算日期不一致");
            if(!record.getAsJsonPrimitive("vip_bonus_applied").isBoolean())throw new IllegalArgumentException("签到 VIP 加成记录无效");
        }
        for(var e:c.getAsJsonObject("claimed_milestones").entrySet()){threshold(e.getKey());if(e.getValue().getAsBigDecimal().longValueExact()<n(c,"registered_at"))throw new IllegalArgumentException("签到里程碑时间无效");}
    }
    private static int threshold(String value){try{if(!value.matches("[1-9][0-9]{0,6}"))throw new IllegalArgumentException();int n=Integer.parseInt(value);if(n>3_652_058)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new IllegalArgumentException("签到里程碑天数无效");}}
    /** Directed intervals prove the same integer floor without truncating daily compound interest. */
    public static long reward(JsonObject config,int streak,boolean vip){
        if(streak<1)throw new IllegalArgumentException("连续签到天数无效");JsonObject c=settings(config);BigDecimal growth=factor(c,"growth_factor"),base=BigDecimal.valueOf(n(c,"base_reward")),bonus=vip?factor(c,"vip_multiplier"):BigDecimal.ONE;
        for(int precision=64;precision<=1024;precision*=2){
            MathContext down=new MathContext(precision,RoundingMode.FLOOR),up=new MathContext(precision,RoundingMode.CEILING);
            BigDecimal lo=base.multiply(bonus,down),hi=base.multiply(bonus,up),powerLo=growth,powerHi=growth;int exponent=streak-1;
            while(exponent>0){if((exponent&1)!=0){lo=lo.multiply(powerLo,down);hi=hi.multiply(powerHi,up);if(lo.compareTo(PAYOUT_LIMIT)>=0)throw overflow();}exponent>>>=1;if(exponent>0){powerLo=powerLo.multiply(powerLo,down);powerHi=powerHi.multiply(powerHi,up);if(powerLo.compareTo(PAYOUT_LIMIT)>=0)throw overflow();}}
            if(lo.compareTo(PAYOUT_LIMIT)>=0)throw overflow();BigDecimal lowFloor=lo.setScale(0,RoundingMode.FLOOR),highFloor=hi.setScale(0,RoundingMode.FLOOR);if(lowFloor.equals(highFloor))return lowFloor.longValueExact();
        }
        throw new IllegalArgumentException("签到奖励的小数精度暂无法安全结算，请管理员调整奖励配置；次数未扣除");
    }
    private static IllegalArgumentException overflow(){return new IllegalArgumentException("签到奖励超出余额整数安全范围，未发放奖励或扣除补签次数，请联系管理员");}
    public static Settlement settle(JsonObject original,JsonObject config,LocalDate target,long now){
        JsonObject rules=settings(config);if(!rules.get("enabled").getAsBoolean())throw new IllegalArgumentException("服务器暂未开启签到");
        JsonObject next=original.deepCopy(),c=requireRegistered(next),signed=c.getAsJsonObject("signed");LocalDate current=today(now),start=date(t(c,"registered_date"));
        if(target.isAfter(current))throw new IllegalArgumentException("不能提前签到未来日期");if(target.isBefore(start))throw new IllegalArgumentException("不能补签注册前的日期");if(signed.has(target.toString()))throw new IllegalArgumentException("这一天已经领取过签到奖励，不能重复领取");
        boolean makeup=target.isBefore(current),vip=n(next,"vip_until")>now;if(makeup&&n(c,"makeup_credits")<1)throw new IllegalArgumentException("永久补签次数不足；每次成功购买或续期 VIP 可获得 1 次");
        NavigableSet<LocalDate> dates=dates(c);dates.add(target);int streak=ending(dates,target),longest=longest(dates);long amount=reward(config,streak,vip);
        try{next.addProperty("balance",MoneyMath.credit(n(next,"balance"),amount));}catch(ArithmeticException e){throw overflow();}
        if(makeup)c.addProperty("makeup_credits",n(c,"makeup_credits")-1);
        JsonObject entry=new JsonObject();entry.addProperty("reward",amount);entry.addProperty("makeup",makeup);entry.addProperty("vip_bonus_applied",vip);entry.addProperty("settled_at",now);entry.addProperty("streak_at_settlement",streak);signed.add(target.toString(),entry);
        JsonArray milestones=new JsonArray();JsonObject claimed=c.getAsJsonObject("claimed_milestones");TreeMap<Integer,Integer> ordered=new TreeMap<>();for(var e:rules.getAsJsonObject("milestones").entrySet())ordered.put(threshold(e.getKey()),e.getValue().getAsInt());
        for(var e:ordered.entrySet())if(longest>=e.getKey()&&!claimed.has(e.getKey().toString())){
            mailAdd(next,SILVER,e.getValue());claimed.addProperty(e.getKey().toString(),now);JsonObject award=new JsonObject();award.addProperty("days",e.getKey());award.addProperty("item",SILVER);award.addProperty("name","银币");award.addProperty("count",e.getValue());milestones.add(award);
        }
        return new Settlement(next,amount,makeup,streak,longest,milestones,vip);
    }
    private static void mailAdd(JsonObject account,String item,int count){
        JsonArray mail=account.getAsJsonArray("mail");for(var element:mail){JsonObject entry=element.getAsJsonObject();if(item.equals(t(entry,"item"))){long total=Math.addExact(n(entry,"count"),count);if(total>1_000_000)throw new IllegalArgumentException("邮箱银币已达上限，请领取后再签到；补签次数未扣除");entry.addProperty("count",total);return;}}
        if(mail.size()>=256)throw new IllegalArgumentException("邮箱已满，请领取部分邮件后再签到；补签次数未扣除");JsonObject entry=new JsonObject();entry.addProperty("id",UUID.randomUUID().toString());entry.addProperty("item",item);entry.addProperty("count",count);mail.add(entry);
    }
    public static JsonObject summary(JsonObject account,long now){
        JsonObject out=new JsonObject();LocalDate current=today(now);out.addProperty("today",current.toString());out.addProperty("vip_bonus_active",n(account,"vip_until")>now);
        if(!account.has("checkin")){out.addProperty("registered_date","");out.addProperty("signed_today",false);out.addProperty("current_streak",0);out.addProperty("longest_streak",0);out.addProperty("makeup_credits",0L);return out;}
        JsonObject c=requireRegistered(account);NavigableSet<LocalDate> dates=dates(c);out.addProperty("registered_date",t(c,"registered_date"));out.addProperty("registration_source",t(c,"registration_source"));out.addProperty("signed_today",dates.contains(current));out.addProperty("current_streak",ending(dates,dates.contains(current)?current:current.minusDays(1)));out.addProperty("longest_streak",longest(dates));out.addProperty("makeup_credits",n(c,"makeup_credits"));return out;
    }
    public static JsonObject calendar(JsonObject account,JsonObject config,YearMonth month,long now){
        JsonObject c=requireRegistered(account),out=new JsonObject();LocalDate current=today(now),start=date(t(c,"registered_date"));JsonObject signed=c.getAsJsonObject("signed");out.addProperty("month",month.toString());out.addProperty("today",current.toString());out.addProperty("registered_date",start.toString());
        out.addProperty("previous_month",month.getYear()==1&&month.getMonthValue()==1?"":month.minusMonths(1).toString());out.addProperty("next_month",month.getYear()==9999&&month.getMonthValue()==12?"":month.plusMonths(1).toString());JsonArray days=new JsonArray();boolean enabled=settings(config).get("enabled").getAsBoolean();
        for(int day=1;day<=month.lengthOfMonth();day++){LocalDate date=month.atDay(day);JsonObject entry=new JsonObject();entry.addProperty("date",date.toString());boolean exists=signed.has(date.toString());String status=exists?"signed":date.isBefore(start)?"before_registration":date.isAfter(current)?"future":date.equals(current)?"today":"missed";entry.addProperty("status",status);entry.addProperty("can_prepare",enabled&&!exists&&!date.isBefore(start)&&!date.isAfter(current)&&(date.equals(current)||n(c,"makeup_credits")>0));entry.addProperty("reward",exists?n(signed.getAsJsonObject(date.toString()),"reward"):0L);entry.addProperty("makeup",exists&&signed.getAsJsonObject(date.toString()).get("makeup").getAsBoolean());entry.addProperty("vip_bonus_applied",exists&&signed.getAsJsonObject(date.toString()).get("vip_bonus_applied").getAsBoolean());days.add(entry);}
        out.add("days",days);return out;
    }
    private static NavigableSet<LocalDate> dates(JsonObject checkin){NavigableSet<LocalDate> dates=new TreeSet<>();for(String date:checkin.getAsJsonObject("signed").keySet())dates.add(date(date));return dates;}
    private static int ending(Set<LocalDate> dates,LocalDate end){int count=0;for(LocalDate d=end;dates.contains(d);d=d.minusDays(1))count=Math.incrementExact(count);return count;}
    private static int longest(NavigableSet<LocalDate> dates){int count=0,best=0;LocalDate previous=null;for(LocalDate d:dates){count=previous!=null&&previous.plusDays(1).equals(d)?count+1:1;best=Math.max(best,count);previous=d;}return best;}
    private static long n(JsonObject o,String field){return AccountLedger.number(o,field);}
    private static String t(JsonObject o,String field){return AccountLedger.text(o,field);}
}

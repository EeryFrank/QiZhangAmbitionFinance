package cozylife.economy.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Uses only this world's joined-player files and observed logins; never resolves names on the network. */
public final class KnownEconomyPlayers {
    public static final int MAX_PLAYERS=20_000;
    private KnownEconomyPlayers(){}
    public static JsonObject entries(JsonObject state){if(!state.has("known_players"))state.add("known_players",new JsonObject());return state.getAsJsonObject("known_players");}
    public static boolean observe(JsonObject state,UUID uuid,String name,long now){
        JsonObject entries=entries(state);String key=uuid.toString();
        if(!entries.has(key)&&entries.size()>=MAX_PLAYERS)throw new IllegalArgumentException("已知玩家数量达到安全上限");
        String safeName=name!=null&&name.matches("[A-Za-z0-9_]{1,16}")?name:"";
        JsonObject record=entries.has(key)?entries.getAsJsonObject(key):new JsonObject();
        if(safeName.isEmpty()&&record.has("name"))safeName=record.get("name").getAsString();
        boolean changed=!entries.has(key)||!record.get("name").getAsString().equals(safeName)||now>record.get("last_seen").getAsLong();
        record.addProperty("name",safeName);record.addProperty("last_seen",Math.max(now,record.has("last_seen")?record.get("last_seen").getAsLong():0));entries.add(key,record);return changed;
    }
    public static boolean importLocal(JsonObject state,Path playerData,Path usercache)throws IOException{
        JsonObject names=new JsonObject();
        // Name cache is optional display metadata. Its corruption must not fabricate players or block UUID delivery.
        try{if(Files.isRegularFile(usercache)&&Files.size(usercache)<=4*1024*1024){
            for(var entry:JsonParser.parseString(Files.readString(usercache)).getAsJsonArray())try{
                JsonObject r=entry.getAsJsonObject();String name=r.get("name").getAsString();UUID id=AdminCoinMail.uuid(r.get("uuid").getAsString());
                if(name.matches("[A-Za-z0-9_]{1,16}"))names.addProperty(id.toString(),name);
            }catch(RuntimeException invalidEntry){/* A name alone is never evidence that a player joined. */}
        }}catch(IOException|RuntimeException ignored){/* Keep UUID-only entries when the optional name cache is unavailable. */}
        boolean changed=false;
        if(Files.isDirectory(playerData))try(var paths=Files.newDirectoryStream(playerData,"*.dat")){
            int scanned=0;for(Path path:paths){if(++scanned>MAX_PLAYERS)throw new IOException("玩家存档数量达到安全上限");
                String filename=path.getFileName().toString();UUID id;try{id=AdminCoinMail.uuid(filename.substring(0,filename.length()-4));}catch(IllegalArgumentException ignored){continue;}
                if(!Files.isRegularFile(path))continue;
                changed|=observe(state,id,names.has(id.toString())?names.get(id.toString()).getAsString():"",0);
            }
        }
        return changed;
    }
    public static UUID resolve(JsonObject state,String target){
        JsonObject players=entries(state);
        if(target.indexOf('-')>=0){UUID id=AdminCoinMail.uuid(target);if(players.has(id.toString()))return id;throw new IllegalArgumentException("该 UUID 尚未加入过本世界");}
        if(!target.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("请输入玩家名或完整 UUID");
        UUID found=null;for(var e:players.entrySet())if(target.equalsIgnoreCase(e.getValue().getAsJsonObject().get("name").getAsString())){
            if(found!=null)throw new IllegalArgumentException("该历史名称对应多个 UUID，请选择明确的 UUID");found=AdminCoinMail.uuid(e.getKey());
        }
        if(found==null)throw new IllegalArgumentException("没有已加入玩家使用此名称，请改用已知 UUID");return found;
    }
    public static void validate(JsonObject state){if(!state.has("known_players"))return;JsonObject players=state.getAsJsonObject("known_players");if(players.size()>MAX_PLAYERS)throw new IllegalArgumentException("已知玩家数量过多");
        for(var e:players.entrySet()){AdminCoinMail.uuid(e.getKey());JsonObject p=e.getValue().getAsJsonObject();String name=p.get("name").getAsString();if(!name.isEmpty()&&!name.matches("[A-Za-z0-9_]{1,16}")||AccountLedger.number(p,"last_seen")<0)throw new IllegalArgumentException("已知玩家信息损坏");}
    }
}

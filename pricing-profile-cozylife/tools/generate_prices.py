"""GPL-3.0-only. Traceable reference prices; no production writes or auto-listing."""
from __future__ import annotations
from pathlib import Path
from fractions import Fraction
from collections import Counter, defaultdict
import argparse, csv, hashlib, json, math, os, re, sqlite3, zipfile

HERE=Path(__file__).resolve().parent
PROFILE=HERE.parent
ROOT=PROFILE.parent
DATABASE=PROFILE
DUMP_REPORT=PROFILE/'inputs/runtime-result.json'
CLIENT=PROFILE/'inputs/client-candidate'
SERVER_MODS=PROFILE/'inputs/server-mods'
MAX_PRICE=1_000_000_000
CATEGORIES={'building','ores','farming','equipment','modded','rare'}
LOW_BLOCKS={'dirt','cobblestone','stone','deepslate','cobbled_deepslate','netherrack','sand','gravel'}
OWNER_COMPRESSED={f'minecraft:{x}' for x in ('copper_block iron_block gold_block netherite_block coal_block diamond_block emerald_block redstone_block lapis_block raw_copper_block raw_iron_block raw_gold_block hay_block bone_block slime_block honey_block dried_kelp_block bamboo_block stripped_bamboo_block packed_mud').split()}|{
 'ae2:fluix_block','ae2:quartz_block','ae2:certus_quartz_block','farmersdelight:rice_bag','farmersdelight:rice_bale','farmersdelight:straw_bale'}
WOOD_WORDS=('dark_oak','pale_oak','mangrove','cherry','spruce','birch','jungle','acacia','crimson','warped','bamboo','poplar','walnut','azalea','coconut','maple','oak')
TECHNICAL_NAMES=set(('air barrier light bedrock structure_block structure_void command_block chain_command_block repeating_command_block command_block_minecart jigsaw debug_stick knowledge_book spawner trial_spawner vault end_portal_frame reinforced_deepslate').split())
COMPONENT_NAMES=set(('enchanted_book potion splash_potion lingering_potion tipped_arrow filled_map written_book firework_star firework_rocket suspicious_stew player_head decorated_pot').split())
TECHNICAL_NAMES.update(('budding_amethyst small_amethyst_bud medium_amethyst_bud large_amethyst_bud farmland dirt_path frogspawn petrified_oak_slab').split())
NATURAL_EVIDENCE={}
MOD_NATURAL={}
SHAPED={'minecraft:crafting_shaped','sophisticatedbackpacks:basic_backpack'}
COMPLEX_SHAPED={'sophisticatedcore:upgrade_next_tier','sophisticatedbackpacks:backpack_upgrade','sophisticatedstorage:storage_tier_upgrade','sophisticatedstorage:shulker_box_from_chest','sophisticatedstorage:double_chest_tier_upgrade','sophisticatedstorage:generic_wood_storage'}
COOKING={'minecraft:smelting','minecraft:blasting','minecraft:smoking','minecraft:campfire_cooking'}

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def ceil(value):return -(-value.numerator//value.denominator) if isinstance(value,Fraction) else math.ceil(value)
def clamp(value):return max(1,min(MAX_PRICE,int(value)))
def read_jsonl(path):return [json.loads(line) for line in path.read_text('utf8').splitlines() if line]
def has_chinese(text):return bool(re.search(r'[\u3400-\u9fff]',text))


NAME_CORRECTIONS={
 'block.minecraft.tnt':'TNT 炸药',
 'block.farmersdelight.debug_pumpkin_pie':'调试南瓜派',
 'item.sophisticatedstorage.inaccessible_slot':'不可访问的储物槽位',
 **{'item.gofish.'+key+'_crate':value+'宝箱' for key,value in {
  'astral':'星界','diamond':'钻石','end':'末地','fiery':'烈焰','frosted':'霜冻','gilded_blackstone':'镶金黑石',
  'golden':'黄金','iron':'铁制','slimey':'黏液','soul':'灵魂','supply':'补给','wooden':'木制'}.items()}}

def language_table(assets):
    values={};sources={};evidence=[]
    def apply(data,origin):
        for key,value in data.items():
            if isinstance(value,str):values[key]=value;sources[key]=origin
    index=json.loads((assets/'indexes/34.json').read_text('utf8'))
    entry=index['objects']['minecraft/lang/zh_cn.json'];file=assets/'objects'/entry['hash'][:2]/entry['hash']
    if hashlib.sha1(file.read_bytes()).hexdigest()!=entry['hash']:raise RuntimeError('Original Chinese asset SHA1 mismatch')
    apply(json.loads(file.read_text('utf8')),'原版简体中文资源');evidence.append({'resource':'minecraft/lang/zh_cn.json','asset_index':'34','sha1':entry['hash'],'sha256':sha(file)})
    # Namespaces normally belong to one mod; explicit Chinese overlays always win last.
    mods=sorted((CLIENT/'mods').glob('*.jar'))
    for path in mods:
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if re.fullmatch(r'assets/[^/]+/lang/zh_cn.json',name):
                    apply(json.loads(archive.read(name)),'模组内置简体中文：'+path.name)
    overlay=CLIENT/'resourcepacks/QiZhangChinese.zip'
    with zipfile.ZipFile(overlay) as archive:
        for name in sorted(archive.namelist()):
            if re.fullmatch(r'assets/[^/]+/lang/zh_cn.json',name):apply(json.loads(archive.read(name)),'QiZhangChinese 汉化覆盖包')
    evidence.append({'resource':overlay.name,'sha256':sha(overlay)})
    evidence.append({'client_mod_sha256':{p.name:sha(p) for p in mods}})
    apply(NAME_CORRECTIONS,'物价库显式中文名补全（不改动客户端资源）')
    return values,sources,evidence


def ingredient_choices(raw,tags,items):
    """Keep OR semantics; unsupported component predicates never become unrestricted tags."""
    if isinstance(raw,str):return set(tags.get(raw[1:],())) if raw.startswith('#') else ({raw} if raw in items else set())
    if isinstance(raw,list):return set().union(*(ingredient_choices(x,tags,items) for x in raw)) if raw else set()
    if not isinstance(raw,dict):return set()
    kind=raw.get('neoforge:ingredient_type',raw.get('type'))
    if kind=='neoforge:compound':return set().union(*(ingredient_choices(x,tags,items) for x in raw.get('children',[])))
    if kind=='neoforge:difference':return ingredient_choices(raw.get('base'),tags,items)-ingredient_choices(raw.get('subtracted'),tags,items)
    if kind=='neoforge:intersection':
        children=[ingredient_choices(x,tags,items) for x in raw.get('children',[])];return set.intersection(*children) if children else set()
    if kind or any(k in raw for k in ('components','predicate','potion_contents')):return set()
    if 'item' in raw:return ingredient_choices(raw['item'],tags,items)
    if 'tag' in raw:return set(tags.get(raw['tag'],()))
    return set()


def output_stack(raw):
    if isinstance(raw,str):return {'item':raw,'count':1,'chance':'1','components':{}}
    if not isinstance(raw,dict):return None
    item=raw.get('id',raw.get('item'))
    if not isinstance(item,str):return None
    return {'item':item,'count':int(raw.get('count',1)),'chance':'1','components':raw.get('components',{})}


def normalize_recipes(recipes,items,tags):
    routes=[];unhandled=[];by_output=defaultdict(list)
    def add(row,inputs,outputs,mode='craft',tools=(),uncertain=False,note=''):
        if not outputs:return
        for out in outputs:
            if out and out['item'] in items and out['count']>0:
                route={'id':row['id'],'serializer':row['serializer'],'output':out,'all_outputs':[o for o in outputs if o],
                  'inputs':[{'raw':x,'choices':sorted(ingredient_choices(x,tags,items))} for x in inputs],
                  'tools':[{'raw':x,'choices':sorted(ingredient_choices(x,tags,items)),'kind':kind} for x,kind in tools],
                  'mode':mode,'uncertain':uncertain or bool(out.get('components')),'note':note,
                  'source_pack':(row.get('source_resource') or {}).get('effective_source_pack'),
                  'processing_conditions':{k:raw[k] for k in ('circumstance','cookingtime','experience','mode') if k in raw}}
                routes.append(route);by_output[out['item']].append(route)
    for row in recipes:
        raw=row['raw'];kind=row['serializer'];result=output_stack(raw.get('result')) if isinstance(raw,dict) else None
        if kind in SHAPED|COMPLEX_SHAPED and result:
            key=raw.get('key',{});inputs=[key[ch] for line in raw.get('pattern',[]) for ch in line if ch!=' ']
            add(row,inputs,[result],uncertain=kind in COMPLEX_SHAPED,note='按实际图案逐槽计数；组件继承升级需人工核价' if kind in COMPLEX_SHAPED else '按实际图案逐槽计数')
        elif kind=='minecraft:crafting_shapeless' and result:add(row,raw['ingredients'],[result])
        elif kind in COOKING and result:add(row,[raw['ingredient']],[result],mode='campfire' if kind.endswith('campfire_cooking') else 'furnace',note='燃料按煤炭每8次加工摊销；烟熏/高炉同时缩短燃烧时间')
        elif kind=='minecraft:stonecutting' and result:add(row,[raw['ingredient']],[result],mode='stonecutting')
        elif kind=='minecraft:smithing_transform' and result:add(row,[raw[k] for k in ('template','base','addition') if k in raw],[result],mode='smithing',note='锻造模板按该次消耗计入，未当成无限复用')
        elif kind=='ae2:charger' and result:add(row,[raw['ingredient']],[result],mode='machine',note='服务费含充能设备摊销；不是测得的FE电价')
        elif kind=='ae2:inscriber' and result:
            ingredients=raw['ingredients'];consume=[ingredients['middle']];tools=[]
            for side in ('top','bottom'):
                if side in ingredients:
                    if raw.get('mode')=='inscribe':tools.append((ingredients[side],'mold'))
                    else:consume.append(ingredients[side])
            add(row,consume,[result],mode='machine',tools=tools,note='inscribe上下模具复用并摊销1024次；press模式上下材料正常消耗')
        elif kind=='ae2:transform' and result:add(row,raw['ingredients'],[result],mode='machine',note='保留水中/爆炸等转化条件；设备和操作费为本服参考假设')
        elif kind=='ae2:quartz_cutting' and result:
            inputs=[];tools=[]
            for value in raw['ingredients']:
                if 'knife' in str(value):tools.append((value,'durability'))
                else:inputs.append(value)
            add(row,inputs,[result],mode='cutting',tools=tools,note='切割刀按一次耐久摊销，不整把消耗')
        elif kind=='farmersdelight:cooking' and result:
            inputs=list(raw['ingredients']);container=raw.get('container')
            if container:
                c=output_stack(container)
                if c:inputs += [c['item']]*(c['count']*result['count'])
            else:
                remainder=items[result['item']].get('crafting_remainder',{})
                if remainder.get('id'):inputs += [remainder['id']]*result['count']
            add(row,inputs,[result],mode='pot',note='按实际配料和批量输出计价；上菜容器另计，不虚增经验价值')
        elif kind=='farmersdelight:cutting':
            outputs=[]
            for value in raw.get('result',[]):
                o=output_stack(value.get('item'))
                if o:o['chance']=str(value.get('chance',1));outputs.append(o)
            uncertain=len(outputs)>1 or any(Fraction(o['chance'])!=1 for o in outputs)
            add(row,raw.get('ingredients',[]),outputs,mode='cutting',tools=[(raw['tool'],'durability')],uncertain=uncertain,
                note='每个产物单位独立概率；时运基础0，正式时运加成为每级0.1。多产物参考不扣副产物收入，需人工审核')
        else:
            # Retain evidence for unresolved recipes; never derive a low-price root from them.
            outputs=[]
            if result:outputs=[result]
            if kind=='ae2:storage_cell_disassembly':outputs=[output_stack(x) for x in raw.get('cell_disassembly_items',[])]
            if kind=='ae2:storage_cell_upgrade':outputs=[output_stack(raw.get('result_cell')),output_stack(raw.get('result_component'))]
            if kind=='ae2:crafting_unit_transform':outputs=[output_stack(raw.get('upgraded_block'))]
            if kind=='ae2:entropy':outputs=[output_stack(x) for x in raw.get('output',{}).get('drops',[])]
            unhandled.append({'id':row['id'],'serializer':kind,'outputs':[x['item'] for x in outputs if x],
                              'reason':'特殊组件、交易、世界交互或序列化器未建立可靠消耗适配；不自动计算可上架价格'})
    return routes,by_output,unhandled


def technical_reason(item,row):
    namespace,path=item.split(':',1)
    if namespace in {'cozylife_economy','lootr'}:return '实体货币或个人战利品技术载体，禁止进入普通商店'
    if path in TECHNICAL_NAMES or path.endswith('_spawn_egg') or 'SpawnEggItem' in row.get('java_class',''):return '空气、管理员方块、创造刷怪或技术物品，禁止普通上架'
    if path in COMPONENT_NAMES or path=='inaccessible_slot' or path.endswith('_map') or path.startswith('infested_') or any(x in path for x in ('creative_','debug_','infinite_')):return '创造能力、不可正常获取物品或组件决定价值，普通ID价格不足以安全上架'
    if namespace=='ae2' and path in {'encoded_pattern','crafting_pattern','processing_pattern','facade','name_press','quantum_entangled_singularity'}:return '编码样板、命名模具、关联奇点或外观组件由自定义数据决定，禁止按通用空白ID上架'
    if namespace=='ae2' and path in {'cable_bus','paint','missing_content','mysterious_cube'}:return '模组内部占位、线缆总线或渲染载体，禁止普通上架'
    if namespace=='guideme':return '指南物品的内容标识由组件决定，需要专用规则'
    if not row.get('enabled_by_world_features',True):return '当前世界功能未启用此注册物品'
    return None


def owner_reason(item,row,tags,compressed):
    namespace,path=item.split(':',1)
    # Explicit natural planting stock; wood-word matching must not ban living plants.
    if item in PLANTING_STOCK_EXCEPTIONS:return None
    if item in {f'minecraft:{x}' for x in LOW_BLOCKS}:return '服主禁售：随处可挖的低值方块'
    if path.endswith('_ingot') or any(t=='c:ingots' or t.startswith('c:ingots/') for t in tags):return '服主禁售：矿锭'
    if item in OWNER_COMPRESSED or item in compressed or any(t=='c:storage_blocks' or t.startswith('c:storage_blocks/') for t in tags):return '服主禁售：矿物块、基础材料压缩块或可逆打包材料'
    if namespace=='farmersdelight' and path.endswith('_crate'):return '服主禁售：作物压缩箱'
    if namespace=='minecraft':
        if path.startswith('stripped_') or path.endswith(('_planks','_wood','_hyphae')):return '服主禁售：木材仅保留未去皮原木'
        for wood in WOOD_WORDS:
            if path.startswith(wood+'_') and path not in (wood+'_log',wood+'_stem') and not path.endswith(('_leaves','_sapling','_propagule')):return '服主禁售：原木以外木制建材'
        if path in {'stick','ladder','chest','trapped_chest','barrel','crafting_table','bookshelf'}:return '服主禁售：原木以外木制建材'
    if row.get('is_block_item'):
        wooden=any(re.search(r'(^|_)'+re.escape(wood)+r'(_|$)',path) for wood in WOOD_WORDS)
        if wooden and not path.endswith(('_log','_stem','_leaves','_sapling','_propagule')):return '服主禁售：模组木制建材也仅允许未去皮原木'
    return None


# Explicit natural planting stock reviewed against registered BlockItems and sapling code.
PLANTING_STOCK_EXCEPTIONS={
 'minecraft:bamboo','minecraft:azalea','minecraft:flowering_azalea',
 'minecraft:crimson_fungus','minecraft:warped_fungus',
 'ecologics:coconut_seedling','ecologics:azalea_flower',
}
PLANTING_STOCK_REFERENCES={'ecologics:coconut_seedling':16,'ecologics:azalea_flower':12}

# Explicit acquisition reference assumptions, not claims of vanilla currency prices.
RAW_PRICES={
 'dirt':1,'cobblestone':1,'stone':2,'deepslate':2,'cobbled_deepslate':2,'netherrack':1,'sand':2,'red_sand':3,'gravel':2,
 'granite':3,'diorite':3,'andesite':3,'tuff':4,'calcite':5,'basalt':4,'blackstone':4,'end_stone':12,'obsidian':120,'crying_obsidian':180,
 'clay_ball':3,'clay':12,'flint':4,'mud':2,'snowball':2,'snow_block':8,'ice':6,'packed_ice':48,'blue_ice':350,
 'apple':12,'melon_slice':3,'pumpkin':14,'melon':18,'cactus':6,'bamboo':4,'kelp':3,'cocoa_beans':6,'glow_berries':8,
 'red_mushroom':8,'brown_mushroom':8,'nether_wart':14,'chorus_fruit':16,'sweet_berries':8,'poisonous_potato':6,
 'beef':28,'porkchop':24,'chicken':20,'mutton':24,'rabbit':32,'rabbit_hide':14,'egg':6,'feather':8,'ink_sac':12,'glow_ink_sac':26,
 'rotten_flesh':4,'gunpowder':20,'spider_eye':16,'slime_ball':22,'honeycomb':12,'magma_cream':40,'blaze_rod':80,'ghast_tear':180,
 'phantom_membrane':100,'shulker_shell':400,'scute':80,'turtle_scute':80,'armadillo_scute':40,'prismarine_shard':24,'prismarine_crystals':36,
 'glowstone_dust':20,'soul_sand':6,'soul_soil':6,'nether_sprouts':4,'crimson_fungus':12,'warped_fungus':12,
 'ancient_debris':3000,'netherite_scrap':3600,'nether_star':12000,'dragon_breath':240,'dragon_egg':100000,
 'heart_of_the_sea':2000,'sponge':400,'wet_sponge':420,'totem_of_undying':5000,'elytra':18000,
 'breeze_rod':220,'heavy_core':18000,'echo_shard':600,'disc_fragment_5':120,'trial_key':400,'ominous_trial_key':1000,
 'netherite_upgrade_smithing_template':8000,'experience_bottle':80,'name_tag':120,'saddle':220,
 'wither_skeleton_skull':3000,'skeleton_skull':1800,'zombie_head':1800,'creeper_head':2200,'dragon_head':6000,'piglin_head':2200,
 'moss_block':6,'moss_carpet':2,'pale_moss_block':8,'azalea':12,'flowering_azalea':16,'vine':4,'glow_lichen':8,
 'seagrass':3,'sea_pickle':8,'lily_pad':8,'hanging_roots':6,'rooted_dirt':4,'big_dripleaf':14,'small_dripleaf':10,
 'brown_egg':6,'blue_egg':6,'resin_clump':16,'pitcher_pod':120,'torchflower_seeds':120,
 'sulfur':6,'cinnabar':10,'dripstone_block':6,'pointed_dripstone':4,'cobweb':20,'grass_block':4,'mycelium':10,'podzol':8,
 'mangrove_roots':4,'brown_mushroom_block':6,'red_mushroom_block':6,'mushroom_stem':4,'amethyst_cluster':40,
 'sculk':12,'sculk_sensor':120,'sculk_shrieker':180,'sculk_catalyst':400,'bee_nest':100,'bell':600,
 'tall_grass':4,'large_fern':4,'chorus_plant':12,'crimson_nylium':10,'warped_nylium':10,
}
RARE_PATHS={'diamond','emerald','nether_star','dragon_egg','heart_of_the_sea','totem_of_undying','elytra','heavy_core','echo_shard','netherite_scrap','ancient_debris','shulker_shell','nautilus_shell','ender_pearl'}


def natural_seed(item,row,tags,anchors):
    namespace,path=item.split(':',1)
    if item in anchors:return anchors[item]['base'],'服主既有51项商品官方买价锚点','high','anchor'
    if item in PLANTING_STOCK_REFERENCES:return PLANTING_STOCK_REFERENCES[item],'实际可繁殖树苗；按种植难度和可批量繁殖能力人工设定本服参考价','medium','natural'
    if item in MOD_NATURAL:return MOD_NATURAL[item],'模组真实鱼获/生物掉落资源加获取条件的本服参考假设；不是实测全局掉率','medium','natural'
    if namespace=='minecraft' and path in RAW_PRICES:return RAW_PRICES[path],'原料获取难度、生态来源与自动化能力参考假设','medium','natural'
    if technical_reason(item,row):return None
    if path.endswith('_log') and not path.startswith('stripped_'):return (20 if namespace!='minecraft' else 16),'可种植树木原木；按批量林场成本参考','medium','natural'
    if path in {'crimson_stem','warped_stem'}:return 22,'可再生下界菌柄，按种植采集成本参考','medium','natural'
    if path.endswith(('_leaves','_sapling','_propagule')):return (10 if path.endswith(('_sapling','_propagule')) else 3),'植物繁殖与剪采参考；未计入稀有结构奖励','medium','natural'
    if namespace=='minecraft' and any(t in tags for t in ('minecraft:small_flowers','minecraft:tall_flowers','minecraft:flowers')):return 8,'可栽培花卉，按批量种植参考','medium','natural'
    if namespace=='minecraft' and path.endswith('_coral'):return 24,'海洋生态采集材料参考','medium','natural'
    if namespace=='minecraft' and path.endswith('_coral_fan'):return 18,'海洋生态采集材料参考','medium','natural'
    if namespace=='minecraft' and path.endswith('_coral_block'):return 36,'海洋生态完整方块采集参考','medium','natural'
    if namespace=='minecraft' and path in {'crimson_roots','warped_roots','weeping_vines','twisting_vines','short_grass','fern','dead_bush','bush','firefly_bush','leaf_litter','wildflowers','pink_petals'}:return 4,'常见植物采集参考','medium','natural'
    if namespace=='minecraft' and path.endswith('_wool'):return 12,'可剪羊毛，按羊场批量供给参考','medium','natural'
    if namespace=='minecraft' and path.endswith('_ore'):
        material=path.removeprefix('deepslate_').removesuffix('_ore');known={'coal':28,'iron':80,'copper':32,'gold':380,'redstone':50,'lapis':60,'diamond':2100,'emerald':1500,'nether_gold':180,'nether_quartz':50}
        if material in known:return known[material],'带原矿形态的采掘参考；包含精准采集服务','medium','natural'
    if namespace=='ae2' and path in {'sky_stone_block','certus_quartz_crystal','charged_certus_quartz_crystal','flawless_budding_quartz','flawed_budding_quartz','chipped_budding_quartz','damaged_budding_quartz','calculation_processor_press','engineering_processor_press','logic_processor_press','silicon_press'}:
        values={'sky_stone_block':60,'certus_quartz_crystal':120,'charged_certus_quartz_crystal':150,'flawless_budding_quartz':8000,'flawed_budding_quartz':4000,'chipped_budding_quartz':2000,'damaged_budding_quartz':1000}
        return values.get(path,1200),'AE2天然陨石/晶体/压印模具参考；模具不是每次加工消耗','medium','natural'
    if namespace=='farmersdelight' and path in {'wild_cabbages','wild_onions','wild_tomatoes','wild_carrots','wild_potatoes','wild_rice','rice_panicle','rotten_tomato','brown_mushroom_colony','red_mushroom_colony'}:
        return (6 if path=='rotten_tomato' else 20),'农夫乐事野生作物或繁殖产物的采集参考','medium','natural'
    if namespace=='ecologics' and path in {'coconut','coconut_husk','seashell','crab_claw','prickly_pear','penguin_feather','ice_shard'}:
        return {'coconut':16,'coconut_husk':6,'seashell':20,'crab_claw':28,'prickly_pear':10,'penguin_feather':24,'ice_shard':8}[path],'生态模组自然采集/动物产物参考','medium','natural'
    return None


def mod_acquisition_references(items):
    """No global drop probability claims: raw tables and inspected code qualify reference seeds."""
    MOD_NATURAL.clear();NATURAL_EVIDENCE.clear()
    fish_jars=list(SERVER_MODS.glob('gofish*.jar'))
    if len(fish_jars)==1:
        path=fish_jars[0]
        with zipfile.ZipFile(path) as archive:
            for dimension,base in [('nether',40),('end',80)]:
                resource=f'data/gofish/loot_table/gameplay/fishing/{dimension}/fish.json'
                content=archive.read(resource);table=json.loads(content)
                entries=[e for p in table['pools'] for e in p['entries'] if e.get('type')=='minecraft:item']
                largest=max(e.get('weight',1) for e in entries)
                for entry in entries:
                    item=entry['name']
                    if item not in items:continue
                    condition=entry.get('conditions',entry.get('condition'))
                    MOD_NATURAL[item]=ceil(Fraction(base*largest,entry.get('weight',1)))+(20 if condition else 0)
                    NATURAL_EVIDENCE[item]={'resource':resource,'resource_sha256':hashlib.sha256(content).hexdigest(),
                        'jar_sha256':sha(path),'raw_entry':entry,'dimension':dimension,
                        'reference_model':'维度基础采集价×该表最高权重/本项权重，文本有限制条件另加20；不等同实际每竿概率，旧字段兼容不确定性不隐藏'}
            classfile='net/anvian/gofish/registry/GoFishLootHandler.class'
            classhash=hashlib.sha256(archive.read(classfile)).hexdigest()
            if classhash!='cb11b8cd0080153d202c1c14b4e05913e752b90b9c1fe33f360c35406f03e3f6':
                raise RuntimeError('GoFish injected fishing conditions changed; re-audit before assigning acquisition references')
            groups=[(('icicle_fish','snowball_fish'),40,'寒冷群系'),(('lilyfish','slimefish'),36,'沼泽群系'),
                    (('seaweed_eel',),40,'海洋群系'),(('terrafish',),44,'恶地群系'),(('carrot_carp','oakfish'),32,'平原或森林'),
                    (('lunarfish','starry_salmon'),80,'满月，表内权重50'),(('galaxy_starfish','nebula_swordfish'),160,'满月，表内权重25'),
                    (('rainy_bass',),48,'下雨'),(('thundering_bass',),80,'雷雨'),(('cloudy_crab',),72,'高度至少150'),
                    (('blizzard_bass',),88,'暴雪天气')]
            for group,price,condition in groups:
                for name in group:
                    item='gofish:'+name
                    if item in items:
                        MOD_NATURAL[item]=price;NATURAL_EVIDENCE[item]={'class':classfile,'class_sha256':classhash,
                          'condition_reference':condition,'evidence':'本次JAR字节码GoFishLootHandler.addFishEntries人工抽取条件；参考定价不是全局掉率计算'}
    naturalist_jars=list(SERVER_MODS.glob('naturalist*.jar'))
    if len(naturalist_jars)==1:
        raw_prices={'anglerfish':44,'bass':24,'blobfish':40,'bushmeat':28,'catfish':30,'clam_meat':20,'crab_meat':28,'drumstick':20,'duck':24,
          'lizard_tail':24,'mammoth_meat':48,'morsel':8,'piranha':32,'venison':28,'snail_shell':16,'shellstone':8,
          'duck_egg':6,'tortoise_scute':48,'antler':64,'butterfly':24}
        with zipfile.ZipFile(naturalist_jars[0]) as archive:
            tables=[(n,archive.read(n)) for n in archive.namelist() if n.startswith('data/naturalist/loot_table/') and n.endswith('.json')]
            for name,price in raw_prices.items():
                item='naturalist:'+name;found=[(n,c) for n,c in tables if ('"'+item+'"').encode() in c]
                if item in items and found:
                    MOD_NATURAL[item]=price;NATURAL_EVIDENCE[item]={'resources':[{'resource':n,'sha256':hashlib.sha256(c).hexdigest()} for n,c in found],
                        'reference_model':'真实模组掉落资源确认来源存在，再按生物难度与批量供给给参考价；没有宣称单位时间产能'}


def world_processing_routes(items):
    """Explicit world interactions are policy assumptions, never masqueraded as loaded RecipeManager recipes."""
    routes=[]
    def add(output,source,fee,reason):
        if output not in items or source not in items:return
        out={'item':output,'count':1,'chance':'1','components':{}}
        routes.append({'id':'qizhang_reference:world/'+output.replace(':','/'),'serializer':'qizhang_reference:world_interaction',
          'output':out,'all_outputs':[out],'inputs':[{'raw':source,'choices':[source]}],'tools':[],'mode':'world',
          'uncertain':False,'note':reason+'；属于显式参考假设，不是RecipeManager已加载配方',
          'source_pack':None,'processing_conditions':{'reference_only':True,'service_extra':fee}})
    for item in items:
        namespace,path=item.split(':',1)
        if path.startswith('stripped_') and path.endswith(('_log','_wood','_stem','_hyphae')):
            add(item,namespace+':'+path.removeprefix('stripped_'),2,'斧去皮：1个原材转1个去皮材，按操作摊销')
        if namespace=='minecraft' and path.endswith('_concrete'):
            add(item,item+'_powder',1,'混凝土粉接触水凝固1:1，水循环复用，操作服务费另计')
    for output,source,fee,reason in [
      ('water_bucket','bucket',2,'水源取水，保留整只桶价值'),('milk_bucket','bucket',8,'挤奶，保留整只桶价值'),
      ('lava_bucket','bucket',20,'熔岩采集，保留整只桶价值'),('powder_snow_bucket','bucket',12,'细雪采集，保留整只桶价值'),
      ('honey_bottle','glass_bottle',12,'蜂蜜采集，保留玻璃瓶价值'),('carved_pumpkin','pumpkin',2,'南瓜雕刻1:1，不扣南瓜籽副产物收入')]:
        add('minecraft:'+output,'minecraft:'+source,fee,reason)
    return routes


def category(item,row,tags,anchors):
    if item in PLANTING_STOCK_EXCEPTIONS:return 'farming'
    if item in anchors:return anchors[item]['category']
    path=item.split(':',1)[1]
    if path in RARE_PATHS or path.endswith(('_armor_trim_smithing_template','_pottery_sherd','_music_disc')) or path.startswith('music_disc_') or row.get('rarity') in ('RARE','EPIC'):return 'rare'
    if row.get('damageable') or any('/tools/' in t or t.startswith('c:armors') for t in tags):return 'equipment'
    if row.get('food') or any(t.startswith(('c:crops','c:seeds','c:foods')) or t in {'minecraft:flowers','minecraft:saplings'} for t in tags):return 'farming'
    if any(t.startswith(('c:ores','c:gems','c:dusts','c:raw_materials','c:ingots','c:nuggets')) for t in tags):return 'ores'
    if row.get('is_block_item'):return 'building'
    return 'modded' if not item.startswith('minecraft:') else 'farming'


def compressed_items(routes,items):
    """Only exact repeated ingredient compression with an actual inverse count recipe."""
    simple=[]
    for route in routes:
        if route['uncertain'] or route['tools'] or not route['inputs']:continue
        choices=[i['choices'] for i in route['inputs']]
        if all(len(c)==1 for c in choices) and len({c[0] for c in choices})==1:
            simple.append((choices[0][0],len(choices),route['output']['item'],route['output']['count']))
    inverse=set(simple);result=set()
    for source,count,out,n in simple:
        if count>=4 and n==1 and items[out].get('is_block_item') and (out,1,source,count) in inverse:result.add(out)
    return result


def evaluate(route,prices,items,allow_uncertain=False):
    if route['uncertain'] and not allow_uncertain:return None
    out=route['output']['item'];total=Fraction(0);ancestors=set();inputs=[];tools=[];remainders=[]
    for slot in route['inputs']:
        choices=[x for x in slot['choices'] if x in prices and x!=out and out not in prices[x]['ancestors']]
        if not choices:return None
        selected=min(choices,key=lambda x:(prices[x]['price'],x));cost=Fraction(prices[selected]['price'])
        ancestors|=prices[selected]['ancestors']|{selected}
        # Bucket/other retained container is returned after ordinary crafting.
        remainder=items[selected].get('crafting_remainder',{})
        if route['mode']=='craft' and remainder.get('id') in prices:
            returned=remainder['id'];credit=min(cost,Fraction(prices[returned]['price'])*int(remainder.get('count',1)))
            cost-=credit;remainders.append({'item':returned,'count':int(remainder.get('count',1)),'credited_price':float(credit)})
        total+=cost;inputs.append({'item':selected,'count':1,'unit_reference_price':prices[selected]['price'],'net_consumed_reference_cost':float(cost)})
    for tool in route['tools']:
        choices=[x for x in tool['choices'] if x in prices and x!=out and out not in prices[x]['ancestors']]
        if not choices:return None
        def wear(x):return Fraction(prices[x]['price'],1024 if tool['kind']=='mold' else max(1,items[x].get('max_damage',0)))
        selected=min(choices,key=lambda x:(wear(x),x));amount=max(Fraction(1,4),wear(selected))
        total+=amount;ancestors|=prices[selected]['ancestors']|{selected};tools.append({'item':selected,'reusable':True,'amortized_uses':1024 if tool['kind']=='mold' else max(1,items[selected].get('max_damage',0)),'cost_per_operation':float(amount)})
    fuel=Fraction(prices.get('minecraft:coal',{'price':24})['price'],8) if route['mode']=='furnace' else Fraction(0)
    total+=fuel
    rates={'craft':Fraction(112,100),'stonecutting':Fraction(108,100),'furnace':Fraction(115,100),'campfire':Fraction(112,100),'pot':Fraction(118,100),'cutting':Fraction(112,100),'machine':Fraction(125,100),'smithing':Fraction(118,100),'world':Fraction(1)}
    fees={'craft':2,'stonecutting':1,'furnace':2,'campfire':2,'pot':4,'cutting':2,'machine':8,'smithing':12,'world':route['processing_conditions'].get('service_extra',1)}
    quantity=Fraction(route['output']['count'])*Fraction(route['output']['chance'])
    if quantity<=0:return None
    price=clamp(ceil((total*rates[route['mode']]+fees[route['mode']])/quantity))
    return {'price':price,'ancestors':ancestors,'method':'uncertain_recipe' if route['uncertain'] else 'world_reference' if route['mode']=='world' else 'recipe','confidence':'low' if route['uncertain'] else 'medium',
      'source':'概率/多产出或组件配方的保守参考，需人工审核' if route['uncertain'] else '本服显式世界交互参考假设，不是已加载合成配方' if route['mode']=='world' else '真实已加载配方的数量与加工成本推导',
      'evidence':{'recipe_id':route['id'],'serializer':route['serializer'],'output_count':route['output']['count'],'output_chance':route['output']['chance'],
          'expected_output_at_fortune_0':float(quantity),'consumed_inputs':inputs,'reusable_tools':tools,'returned_containers':remainders,
          'fuel_cost_per_batch':float(fuel),'service_multiplier':float(rates[route['mode']]),'service_fee_per_batch':fees[route['mode']],
          'input_cost_per_batch':float(total),'per_unit_formula':'向上取整((净材料+燃料+工具摊销)*加工系数+每批服务费)/本产物期望数量',
          'all_outputs':route['all_outputs'],'multiple_outputs_no_byproduct_credit':len(route['all_outputs'])>1,'processing_conditions':route['processing_conditions'],'note':route['note']}}


def solve(items,tags_by_item,routes,anchors):
    prices={};seeded=set()
    for item,row in items.items():
        seed=natural_seed(item,row,tags_by_item[item],anchors)
        if seed:
            price,source,confidence,method=seed
            prices[item]={'price':price,'source':source,'confidence':confidence,'method':method,'ancestors':set(),'evidence':None};seeded.add(item)
    changes=[]
    for iteration in range(64):
        changed=0
        for route in routes:
            target=route['output']['item']
            if target in seeded:continue
            result=evaluate(route,prices,items)
            if result and (target not in prices or result['price']<prices[target]['price']):prices[target]=result;changed+=1
        changes.append(changed)
        if changed==0:break
    else:raise RuntimeError('Cost graph did not converge within bounded passes')
    confident=dict(prices)
    # Uncertain routes may receive references, but cannot become cheap inputs for another item.
    for route in routes:
        target=route['output']['item']
        if target in confident:continue
        result=evaluate(route,confident,items,allow_uncertain=True)
        if result and (target not in prices or result['price']<prices[target]['price']):prices[target]=result
    return prices,{'seeded_count':len(seeded),'deterministic_resolved_count':len(confident),'iteration_changes':changes,
      'uncertain_references_count':len(prices)-len(confident),'unknown_prices_never_used_as_cost_roots':True,'production_arbitrage_hard_rejection':False}


def fallback(item,row,recipe_count):
    namespace,path=item.split(':',1)
    rarity={'COMMON':1,'UNCOMMON':3,'RARE':8,'EPIC':25}.get(row.get('rarity'),3)
    techfactor=4 if namespace in {'ae2','ae2wtlib','sophisticatedbackpacks','sophisticatedstorage','hydrofarm'} else 2 if namespace!='minecraft' else 1
    quantity_match=re.search(r'(\d+)k_',path);capacity=int(quantity_match.group(1)) if quantity_match else 0
    base=2500*techfactor+min(200000,max(0,row.get('max_damage',0))*3)+min(100000,capacity*120)+min(1000,recipe_count*40)
    if row.get('max_stack_size')==1:base+=1500
    return {'price':clamp(ceil(Fraction(base*rarity,10))*10),'method':'unresolved','confidence':'low','source':'未适配复杂获取路径：按稀有度、耐久、科技类别与容量给保守高参考，必须人工核价','ancestors':set(),'evidence':None}


def main():
    global CLIENT,SERVER_MODS,DATABASE,DUMP_REPORT
    parser=argparse.ArgumentParser(description='Generate this server snapshot reference prices; no production writes or automatic listing.')
    parser.add_argument('--assets',type=Path,required=True,help='Local Minecraft assets directory containing index 34 and its objects')
    parser.add_argument('--dump-report',type=Path,default=DUMP_REPORT)
    parser.add_argument('--client',type=Path,default=CLIENT)
    parser.add_argument('--server-mods',type=Path,default=SERVER_MODS)
    parser.add_argument('--anchors',type=Path,default=PROFILE/'inputs/market-config.json')
    parser.add_argument('--output',type=Path,default=DATABASE)
    parser.add_argument('--resource-output',type=Path,default=ROOT/'finance/src/main/resources/data/cozylife_economy/QiZhangOfficialPrices.json')
    args=parser.parse_args()
    CLIENT=args.client;SERVER_MODS=args.server_mods;DATABASE=args.output;DUMP_REPORT=args.dump_report
    run=json.loads(DUMP_REPORT.read_text('utf8'))
    if run['status']!='PASS_COMPLETE_EXPORT_AND_NORMAL_EXIT' or run['exit_code']!=0 or run['error_count']!=0:raise RuntimeError('Complete real registry dump with normal exit is required')
    manifest_path=Path(run['dump_manifest']);manifest=json.loads(manifest_path.read_text('utf8'))
    if sha(manifest_path)!=run['dump_manifest_sha256']:raise RuntimeError('Dump manifest changed')
    for file,meta in manifest['files'].items():
        if sha(manifest_path.parent/file)!=meta['sha256']:raise RuntimeError('Dump bytes changed')
    items={x['id']:x for x in read_jsonl(manifest_path.parent/'items.jsonl')}
    tags={x['id']:x['members'] for x in read_jsonl(manifest_path.parent/'item-tags.jsonl')}
    recipes=read_jsonl(manifest_path.parent/'recipes.jsonl')
    by_item=defaultdict(set)
    for tag,members in tags.items():
        for item in members:by_item[item].add(tag)
    config_path=args.anchors
    anchors={p['item']:p for p in json.loads(config_path.read_text('utf8'))['products']}
    if len(anchors)!=51:raise RuntimeError('Expected all 51 current official anchors')
    langs,lang_sources,lang_evidence=language_table(args.assets)
    routes,by_output,unhandled=normalize_recipes(recipes,items,tags)
    world_routes=world_processing_routes(items)
    routes.extend(world_routes)
    for route in world_routes:by_output[route['output']['item']].append(route)
    mod_acquisition_references(items)
    other_evidence=defaultdict(list)
    for row in unhandled:
        for item in row['outputs']:other_evidence[item].append(row['id'])
    compressed=compressed_items(routes,items)
    prices,graph_report=solve(items,by_item,routes,anchors)
    records={};missing=[]
    for item,row in sorted(items.items()):
        refs=sorted({r['id'] for r in by_output[item]}|set(other_evidence[item]))
        price_data=prices.get(item) or fallback(item,row,len(refs))
        price=price_data['price'];tech=technical_reason(item,row);owner=owner_reason(item,row,by_item[item],compressed)
        if item=='minecraft:air':price=0
        key=row.get('name_translation_key',row.get('description_id',''))
        name=langs.get(key)
        if item in anchors:name=anchors[item]['name']
        if not name or not has_chinese(name):missing.append({'item':item,'key':key,'existing':name});name='待核对译名（'+item+'）'
        name=re.sub(r'§.','',name)
        eligible=not tech and not owner and price_data['confidence']!='low' and price>0 and not name.startswith('待核对译名')
        cat=category(item,row,by_item[item],anchors)
        automation='易量产' if (row.get('food') or any(t.startswith(('c:crops','c:seeds')) or t=='minecraft:logs' for t in by_item[item])) else '可加工量产' if price_data['method']=='recipe' else '取决于获取路径'
        ratio=Fraction(1,5) if cat=='ores' or any('fish' in t for t in by_item[item]) else Fraction(3,20) if automation!='取决于获取路径' else Fraction(1,10)
        sell=0 if tech or price<=1 else max(1,min(price-1,(price*ratio.numerator)//ratio.denominator))
        limit=0 if not eligible or cat=='rare' else 128 if cat=='farming' else 64 if cat=='ores' else 8 if cat=='equipment' else 128 if cat=='building' else 32
        if item in anchors and eligible and cat!='rare':limit=int(anchors[item]['daily_sell_limit'])
        reason=tech or owner or ('缺少完整获取或组件规则，低可信参考价；必须由服主人工核价后上架' if price_data['confidence']=='low' else '真实注册物品与已适配成本路径；仅提供参考，不自动上架')
        if name.startswith('待核对译名'):reason+='；中文名待核对，自动上架关闭'
        records[item]={'item':item,'name':name,'official_price':price,'official_sell_price':sell,'default_sell_price':sell,
          'category':cat,'eligible':eligible,'technical':bool(tech),'owner_excluded':bool(owner),'reason':reason,'confidence':price_data['confidence'],
          'default_daily_sell_limit':limit,'default_sell_enabled':False,'default_listed':False,
          'source':price_data['source'],'name_source':lang_sources.get(key,'既有商品名' if item in anchors else '缺少完整中文键'),
          'description_id':row.get('description_id'),'translation_key':key,
          'dimensions':{'rarity':row.get('rarity'),'automation':automation,'stack_size':row.get('max_stack_size'),'durability':row.get('max_damage',0),
              'food':row.get('food'),'is_block_item':row.get('is_block_item'),'namespace':item.split(':')[0],
              'independent_sell_ratio':str(float(ratio)),'quantity_and_batching_used':bool(price_data['evidence']),
              'requires_default_components':True,'full_manual_review_completed':False,'natural_acquisition_evidence':NATURAL_EVIDENCE.get(item),'repair_formula':'向上取整(官方买价×25%×缺损耐久/最大耐久)+20' if row.get('damageable') else None},
          'cost_method':price_data['method'],'selected_recipe':price_data['evidence'],'recipe_ids':refs,'tags':sorted(by_item[item])}
    revision='20261014.'+hashlib.sha256(canonical(records).encode()).hexdigest()[:20]
    document={'schema':1,'revision':revision,'label':'QiZhang 本服官方参考物价库','currency':'余额点，1铜币=1点',
      'policy':{'generated_reference_not_human_appraisal':True,'auto_listing':False,'default_sell_enabled':False,
        'independent_sell_base':True,'sell_quote_rule':'floor(max(1, sell_base×共享市价/官方买价))，上限为本次实际买价-1；实际买价为1时暂停回收',
        'production_processing_arbitrage_allowed':True,'rare_daily_sell_limit':0,'unknowns_never_seed_cost_graph':True},
      'scope':'仅适用于本服104模组及本次真实注册表/数据包快照，不是通用Minecraft数据库；全部价格为生成参考，未逐项人工审价',
      'provenance':{'dump_manifest_sha256':sha(manifest_path),'actual_registered_items':len(items),'actual_loaded_recipes':len(recipes),
        'actual_loaded_item_tags':len(tags),'anchor_config_sha256':sha(config_path),'anchor_count':len(anchors),'language_sources':lang_evidence},'items':records}
    DATABASE.mkdir(parents=True,exist_ok=True)
    data=(json.dumps(document,ensure_ascii=False,indent=2)+'\n').encode('utf8')
    if len(data)>64*1024*1024:raise RuntimeError('PriceBook JSON safety limit exceeded')
    (DATABASE/'QiZhangOfficialPrices.json').write_bytes(data)
    resource=args.resource_output;resource.parent.mkdir(parents=True,exist_ok=True);resource.write_bytes(data)
    with (DATABASE/'QiZhang官方物价.csv').open('w',encoding='utf-8-sig',newline='') as file:
        writer=csv.writer(file);writer.writerow(['物品ID','中文名','官方参考买价','独立参考回收价','分类','可自动核价上架','技术特殊物品','服主禁售','可信度','默认每日回收限额','默认开启回收','价格来源','限制与原因'])
        for r in records.values():writer.writerow([r['item'],r['name'],r['official_price'],r['official_sell_price'],{'building':'建筑','ores':'矿物','farming':'农牧渔业','equipment':'装备工具','modded':'模组物品','rare':'稀有物品'}[r['category']],'是' if r['eligible'] else '否','是' if r['technical'] else '否','是' if r['owner_excluded'] else '否',{'high':'高','medium':'中','low':'低'}[r['confidence']],r['default_daily_sell_limit'],'否',r['source'],r['reason']])
    db_path=DATABASE/'QiZhangOfficialPrices.sqlite';temporary=DATABASE/'QiZhangOfficialPrices.building.sqlite'
    if temporary.exists():raise RuntimeError('Previous incomplete DB build exists; inspect before replacing')
    with sqlite3.connect(temporary) as database:
        database.execute('PRAGMA user_version=1')
        database.execute('CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
        database.execute('CREATE TABLE items (item TEXT PRIMARY KEY,name TEXT NOT NULL,official_price INTEGER NOT NULL,official_sell_price INTEGER NOT NULL,default_sell_price INTEGER NOT NULL,category TEXT NOT NULL,eligible INTEGER NOT NULL,technical INTEGER NOT NULL,owner_excluded INTEGER NOT NULL,confidence TEXT NOT NULL,default_daily_sell_limit INTEGER NOT NULL,record_json TEXT NOT NULL)')
        database.execute('CREATE TABLE loaded_recipes (recipe_id TEXT PRIMARY KEY, serializer TEXT NOT NULL,evidence_json TEXT NOT NULL)')
        database.executemany('INSERT INTO items VALUES (?,?,?,?,?,?,?,?,?,?,?,?)',[(r['item'],r['name'],r['official_price'],r['official_sell_price'],r['default_sell_price'],r['category'],int(r['eligible']),int(r['technical']),int(r['owner_excluded']),r['confidence'],r['default_daily_sell_limit'],canonical(r)) for r in records.values()])
        routes_by_recipe=defaultdict(list)
        for route in routes:
            if not route['id'].startswith('qizhang_reference:'):routes_by_recipe[route['id']].append(route)
        database.executemany('INSERT INTO loaded_recipes VALUES (?,?,?)',[(r['id'],r['serializer'],canonical({
          'recipe_id':r['id'],'serializer':r['serializer'],'type':r['type'],'special':r.get('special',False),
          'codec_json_sha256':hashlib.sha256(canonical(r['raw']).encode()).hexdigest(),
          'source_pack':(r.get('source_resource') or {}).get('effective_source_pack'),
          'source_resource_sha256':(r.get('source_resource') or {}).get('sha256'),
          'normalized_routes':routes_by_recipe.get(r['id'],[]),
          'raw_recipe_not_redistributed':True})) for r in recipes])
        database.execute('INSERT INTO metadata VALUES (?,?)',('document_without_items',canonical({k:v for k,v in document.items() if k!='items'})))
        database.commit()
        if database.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise RuntimeError('SQLite integrity failure')
    database.close()
    os.replace(temporary,db_path)
    normalization={'routes':routes,'unhandled':unhandled,'confirmed_compressed_items':sorted(compressed),'cost_graph':graph_report}
    (DATABASE/'recipe-evidence.json').write_text(json.dumps(normalization,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    (DATABASE/'missing-names.json').write_text(json.dumps(missing,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    report={'status':'GENERATED_REFERENCE_DATABASE_PENDING_DATA_QA','revision':revision,'items':len(records),'eligible':sum(r['eligible'] for r in records.values()),
       'technical':sum(r['technical'] for r in records.values()),'confidence':dict(Counter(r['confidence'] for r in records.values())),
       'methods':dict(Counter(r['cost_method'] for r in records.values())),'missing_chinese_names':len(missing),'recipe_routes':len(routes),'unhandled_recipe_count':len(unhandled),
       'graph':graph_report,'files':{name:sha(DATABASE/name) for name in ['QiZhangOfficialPrices.json','QiZhangOfficialPrices.sqlite','QiZhang官方物价.csv','recipe-evidence.json','missing-names.json']},
       'resource_json_sha256':sha(resource),'generator_sha256':sha(Path(__file__)),'production_modified':False,'automatically_listed':False}
    (DATABASE/'generation-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('files','graph')},ensure_ascii=False,indent=2))

if __name__=='__main__':main()

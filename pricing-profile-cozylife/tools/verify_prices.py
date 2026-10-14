"""GPL-3.0-only. Independent data-contract checks, recipe samples and bounded graph regressions."""
from pathlib import Path
from collections import Counter
from fractions import Fraction
import argparse, csv, hashlib, importlib.util, json, re, sqlite3

HERE=Path(__file__).resolve().parent
PROFILE=HERE.parent
ROOT=PROFILE.parent

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def lines(path):return [json.loads(s) for s in path.read_text('utf8').splitlines() if s]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--database',type=Path,default=PROFILE)
    parser.add_argument('--dump-report',type=Path,default=PROFILE/'inputs/runtime-result.json')
    parser.add_argument('--anchors',type=Path,default=PROFILE/'inputs/market-config.json')
    parser.add_argument('--resource',type=Path,default=ROOT/'finance/src/main/resources/data/cozylife_economy/QiZhangOfficialPrices.json')
    args=parser.parse_args();out=args.database
    document_path=out/'QiZhangOfficialPrices.json'
    if not document_path.exists():document_path=args.resource
    doc=json.loads(document_path.read_text('utf8'));records=doc['items']
    export=json.loads(args.dump_report.read_text('utf8'));manifest_path=Path(export['dump_manifest']);dump=manifest_path.parent
    actual={r['id']:r for r in lines(dump/'items.jsonl')}
    recipes={r['id']:r for r in lines(dump/'recipes.jsonl')}
    tags={r['id']:r['members'] for r in lines(dump/'item-tags.jsonl')}
    anchors={r['item']:r for r in json.loads(args.anchors.read_text('utf8'))['products']}
    checks=[]
    def check(name,condition,detail=None):
        checks.append({'name':name,'passed':bool(condition),'detail':detail})
        if not condition:raise AssertionError(name+': '+str(detail))
    check('已正常退出的真实导出无错误',export['exit_code']==0 and export['error_count']==0 and export['status']=='PASS_COMPLETE_EXPORT_AND_NORMAL_EXIT')
    check('精确覆盖全部7246真实注册ID，无QA添加物品',set(records)==set(actual) and len(records)==7246)
    check('全部8376真实配方与802标签快照',len(recipes)==8376 and len(tags)==802)
    check('schema及稳定revision',doc['schema']==1 and re.fullmatch(r'[A-Za-z0-9._-]{1,96}',doc['revision']) is not None)
    check('资源JSON逐字相同',sha(args.resource)==sha(document_path))
    check('官方51项原始买价全部保留',len(anchors)==51 and all(records[k]['official_price']==v['base'] for k,v in anchors.items()))
    check('默认不自动上架、不自动开放回收',all(r['default_listed'] is False and r['default_sell_enabled'] is False for r in records.values()))
    check('整型价格安全边界',all(type(r['official_price']) is int and 0<=r['official_price']<=1_000_000_000 and r['item']==k for k,r in records.items()))
    check('独立回收价和默认回收价完全一致',all(r['official_sell_price']==r['default_sell_price'] for r in records.values()))
    check('非技术普通物品正回收参考且严格小于买价',all((r['official_sell_price']==0 if r['technical'] or r['official_price']<=1 else 1<=r['official_sell_price']<r['official_price']) for r in records.values()))
    check('稀有、技术、禁售和低可信默认日回收为0',all(r['default_daily_sell_limit']==0 for r in records.values() if r['category']=='rare' or r['technical'] or r['owner_excluded'] or r['confidence']=='low'))
    check('技术/服主禁售/低可信均不允许自动核价',all(not r['eligible'] for r in records.values() if r['technical'] or r['owner_excluded'] or r['confidence']=='low'))
    check('技术标志与服主禁售均为独立布尔字段',all(type(r['technical']) is bool and type(r['owner_excluded']) is bool for r in records.values()))
    check('全部中文名称、来源、限制可读且无待译名',all(re.search(r'[\u3400-\u9fff]',r['name']) and re.search(r'[\u3400-\u9fff]',r['source']) and re.search(r'[\u3400-\u9fff]',r['reason']) and not r['name'].startswith('待核对') for r in records.values()))
    check('没有冒充全量人工审价',doc['policy']['generated_reference_not_human_appraisal'] and all(r['dimensions']['full_manual_review_completed'] is False for r in records.values()))
    check('用户允许加工套利的政策明确保留',doc['policy']['production_processing_arbitrage_allowed'] is True)
    check('正确分类集合',set(r['category'] for r in records.values())=={'building','ores','farming','equipment','modded','rare'})
    check('矿锭全部禁售',all(records[k]['owner_excluded'] for k in actual if k.endswith('_ingot')))
    check('真实存储压缩块标签全部禁售',all(records[k]['owner_excluded'] for tag,members in tags.items() if tag=='c:storage_blocks' or tag.startswith('c:storage_blocks/') for k in members))
    check('禁售示例与可售原木分离',all(records[k]['owner_excluded'] for k in ('minecraft:dirt','minecraft:iron_ingot','minecraft:iron_block','minecraft:hay_block','minecraft:oak_planks','minecraft:oak_stairs','minecraft:stick','minecraft:stripped_oak_log')) and records['minecraft:oak_log']['eligible'])
    check('组件与创造技术品不能按ID售卖',all(records[k]['technical'] and not records[k]['eligible'] for k in ('minecraft:potion','minecraft:enchanted_book','minecraft:filled_map','minecraft:barrier','ae2:crafting_pattern','ae2:processing_pattern','ae2:facade','cozylife_economy:gold_coin')))
    check('可靠配方装备可核价并用于修理',all(records[k]['eligible'] and records[k]['dimensions']['repair_formula'] for k in ('minecraft:iron_sword','minecraft:diamond_pickaxe','minecraft:bow')))
    check('未知价至少多维分层，不是统一兜底数字',len({r['official_price'] for r in records.values() if r['cost_method']=='unresolved'})>15)

    selected_count=0;loaded_selected=0;batch_selected=0;probability_selected=0
    for item,record in records.items():
        route=record['selected_recipe']
        if route is None:continue
        selected_count+=1
        rid=route['recipe_id'];unit=Fraction(route['output_count'])*Fraction(route['output_chance'])
        if unit<=0:raise AssertionError('Nonpositive output '+rid)
        if route['output_count']>1:batch_selected+=1
        if Fraction(route['output_chance'])!=1:probability_selected+=1
        if rid.startswith('qizhang_reference:'):
            if record['cost_method']!='world_reference' or not route['processing_conditions'].get('reference_only'):raise AssertionError('Synthetic world reference disguised as recipe')
        else:
            raw=recipes[rid]['raw'];loaded_selected+=1
            kind=recipes[rid]['serializer']
            if kind=='farmersdelight:cutting':
                possible=[(x['item']['id'],int(x['item'].get('count',1)),Fraction(str(x.get('chance',1)))) for x in raw['result']]
                if (item,route['output_count'],Fraction(route['output_chance'])) not in possible:raise AssertionError('Chance/count mismatch '+rid)
            else:
                result=raw['result']
                if result['id']!=item or int(result.get('count',1))!=route['output_count']:raise AssertionError('Result/count mismatch '+rid)
            if kind in ('minecraft:crafting_shaped','sophisticatedbackpacks:basic_backpack'):
                expected=sum(ch!=' ' for row in raw['pattern'] for ch in row)
                if len(route['consumed_inputs'])!=expected:raise AssertionError('Crafting slot multiplicity lost '+rid)
            if kind=='minecraft:crafting_shapeless' and len(raw['ingredients'])!=len(route['consumed_inputs']):raise AssertionError('Shapeless multiplicity lost '+rid)
        if route['multiple_outputs_no_byproduct_credit'] and record['confidence']!='low':raise AssertionError('Multi-output uncertainty lost '+rid)
        # Recompute arithmetic solely from retained evidence, independently of graph traversal.
        total=sum(Fraction(str(x['net_consumed_reference_cost']))*x['count'] for x in route['consumed_inputs'])
        total+=sum(Fraction(str(x['cost_per_operation'])) for x in route['reusable_tools'])+Fraction(str(route['fuel_cost_per_batch']))
        value=(total*Fraction(str(route['service_multiplier']))+route['service_fee_per_batch'])/unit
        expected=-(-value.numerator//value.denominator)
        if abs(expected-record['official_price'])>1:raise AssertionError('Evidence arithmetic mismatch '+rid)
        if any(records[x['item']]['confidence']=='low' for x in route['consumed_inputs']):raise AssertionError('Unknown reference contaminated graph '+rid)
    check('每条选定配方的批量/概率/槽位和计算可复核',selected_count>5900 and loaded_selected>5900 and batch_selected>1000,{'selected':selected_count,'loaded':loaded_selected,'batch_outputs':batch_selected,'probability':probability_selected})
    processor=records['ae2:printed_calculation_processor']['selected_recipe']
    check('AE2压印模具按1024次摊销并保留复用证据',processor is not None and any(t['reusable'] and t['amortized_uses']==1024 for t in processor['reusable_tools']))
    rice=records['farmersdelight:cooked_rice']['selected_recipe']
    check('农夫锅菜容器成本存在',any(x['item']=='minecraft:bowl' for x in rice['consumed_inputs']))
    check('真实木板一次产出4的参考量保持',records['minecraft:oak_planks']['selected_recipe']['output_count']==4 and records['minecraft:oak_planks']['official_price']==5)
    check('普通铁剑只需材料成本且装备非统一高价',records['minecraft:iron_sword']['official_price']==200)
    check('鱼获按维度/稀有权重区别定价',records['gofish:dragonfish']['official_price']>records['gofish:matrix_fish']['official_price']>records['gofish:endfish']['official_price'])

    con=sqlite3.connect('file:'+str((out/'QiZhangOfficialPrices.sqlite').resolve()).replace('\\','/')+'?mode=ro',uri=True)
    try:
        check('SQLite完整性通过',con.execute('pragma integrity_check').fetchone()[0]=='ok')
        sql_rows={r[0]:json.loads(r[1]) for r in con.execute('select item,record_json from items')}
        check('SQLite与JSON全部7246项完整字段一致',sql_rows==records)
        check('SQLite独立字段与record_json一致',all((r[1],r[2],bool(r[3]),bool(r[4]))==(records[r[0]]['official_price'],records[r[0]]['official_sell_price'],records[r[0]]['owner_excluded'],records[r[0]]['eligible']) for r in con.execute('select item,official_price,official_sell_price,owner_excluded,eligible from items')))
        summaries={k:json.loads(v) for k,v in con.execute('select recipe_id,evidence_json from loaded_recipes')}
        check('SQLite保留全8376条配方摘要和原始SHA，不重分发完整配方',set(summaries)==set(recipes) and all(summaries[k]['codec_json_sha256']==hashlib.sha256(json.dumps(v['raw'],ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest() and summaries[k]['raw_recipe_not_redistributed'] is True and 'raw' not in summaries[k] for k,v in recipes.items()))
        check('SQLite元数据与JSON一致',json.loads(con.execute('select value from metadata where key=?',('document_without_items',)).fetchone()[0])=={k:v for k,v in doc.items() if k!='items'})
    finally:con.close()
    with (out/'QiZhang官方物价.csv').open(encoding='utf-8-sig',newline='') as f:csvrows=list(csv.DictReader(f))
    check('中文CSV行数/买价/回收价/禁售标志一致',len(csvrows)==len(records) and all(int(r['官方参考买价'])==records[r['物品ID']]['official_price'] and int(r['独立参考回收价'])==records[r['物品ID']]['official_sell_price'] and (r['服主禁售']=='是')==records[r['物品ID']]['owner_excluded'] for r in csvrows))
    public_files=[document_path,out/'QiZhang官方物价.csv',HERE/'generate_prices.py']
    if (out/'recipe-evidence.json').exists():public_files.append(out/'recipe-evidence.json')
    forbidden=re.compile(r'(?i)(?:[A-Z]:[\\/]|(?<![-\w.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![\w.])|[\w.-]+\.(?:app|online)(?=[/:"\s])|rcon\.password|server-ip=|server-address)')
    check('发布数据和生成器不含本机绝对路径/玩家/服务器地址',not any(forbidden.search(p.read_text('utf-8-sig')) for p in public_files))

    spec=importlib.util.spec_from_file_location('pricing_generator',HERE/'generate_prices.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    def item():return {'max_damage':0,'enabled_by_world_features':True,'crafting_remainder':{}}
    sample_items={'test:a':item(),'test:b':item(),'test:c':item()}
    rows=[{'id':'test:a_from_b','serializer':'minecraft:crafting_shapeless','raw':{'ingredients':['test:b'],'result':{'id':'test:a','count':9}}},
          {'id':'test:b_from_a','serializer':'minecraft:crafting_shapeless','raw':{'ingredients':['test:a'],'result':{'id':'test:b','count':9}}}]
    routes,_,_=module.normalize_recipes(rows,sample_items,{})
    prices,_=module.solve(sample_items,{i:set() for i in sample_items},routes,{})
    check('没有自然锚点的循环不能凭空产生低价',prices=={})
    seeded,_=module.solve(sample_items,{i:set() for i in sample_items},routes,{'test:a':{'base':100}})
    check('有锚点循环不能把既有锚点稀释',seeded['test:a']['price']==100 and seeded['test:b']['price']==13)
    predicate={'neoforge:ingredient_type':'neoforge:components','items':'test:a','components':{'x':1}}
    check('未知组件输入不扩大成普通ID匹配',module.ingredient_choices(predicate,{},sample_items)==set())
    check('差集标签实际排除项不参与低价选择',module.ingredient_choices({'neoforge:ingredient_type':'neoforge:difference','base':'#test:all','subtracted':'test:a'},{'test:all':['test:a','test:b']},sample_items)=={'test:b'})
    sample=module.fallback('test:unresolved',{'rarity':'RARE','max_damage':1000,'max_stack_size':1},2)
    check('未知物品保持低可信且分维度高参考价',sample['confidence']=='low' and sample['price']>=10_000)
    report={'status':'PASS_GENERATED_REFERENCE_DATABASE_DATA_QA','check_count':len(checks),'checks':checks,'revision':doc['revision'],
      'registered_items':len(records),'eligible':sum(r['eligible'] for r in records.values()),'owner_excluded':sum(r['owner_excluded'] for r in records.values()),
      'technical':sum(r['technical'] for r in records.values()),'confidence':dict(Counter(r['confidence'] for r in records.values())),
      'full_human_appraisal':False,'production_runtime_test':False,'production_modified':False,
      'files':{p.name:sha(p) for p in (document_path,out/'QiZhangOfficialPrices.sqlite',out/'QiZhang官方物价.csv')},'verifier_sha256':sha(Path(__file__))}
    (out/'qa-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('checks','files')},ensure_ascii=False,indent=2))

if __name__=='__main__':main()

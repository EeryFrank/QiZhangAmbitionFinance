# CozyLife 本服定价配置

此目录是 **本服 Minecraft 26.3、104 个正式服模组及实际加载数据包** 的价格配置快照，不是 Minecraft 通用价格表，也不代表其他版本或整合包已兼容。原始注册表来自隔离世界的真实服务器导出，覆盖 7,246 个物品、802 个物品标签、8,376 条配方，导出错误为 0，服务器正常退出。

价格计算为生成参考：51 项保留既有官方买价，6,359 项中可信，836 项低可信；**未逐项人工审价**。3,758 项允许用参考价核价，2,667 项服主禁售，240 项技术物品；标志可能重叠。参考库不会自动把全部物品上架。独立金融模块已有分阶段运行证据，新增签到与种源上架仍待本轮实机验证；数据检查不能代替交易验收。

## 文件

| 文件 | 内容 |
| --- | --- |
| [核心 JSON 资源](../finance/src/main/resources/data/cozylife_economy/QiZhangOfficialPrices.json) | `schema=1`、revision `20261014.f971913cd3d67e274e5e`；唯一维护的完整 JSON 副本 |
| [QiZhangOfficialPrices.sqlite](QiZhangOfficialPrices.sqlite) | `items` 全部价格记录、`metadata` 元信息和 `loaded_recipes` 配方摘要；可只读查询 |
| [QiZhang官方物价.csv](QiZhang官方物价.csv) | UTF-8 BOM 中文列名，适合表格软件查看；修改 CSV 不会自动修改游戏配置 |
| [PRICING_POLICY.md](PRICING_POLICY.md) | 计算方法、自然来源假设、批量/概率/循环边界、禁售与回收规则 |
| [tools/generate_prices.py](tools/generate_prices.py) | 本配置生成器，标准 Python 库，无固定本机绝对路径 |
| [tools/verify_prices.py](tools/verify_prices.py) | 与真实导出对照的全量检查及成本图回归检查 |
| [qa-report.json](qa-report.json) | 41 项离线数据检查结果，不包含原始运行日志或玩家数据 |
| [missing-names.json](missing-names.json) | 本次为空，所有注册项有中文名称 |
| [release-files.json](release-files.json) | 可交付文件的相对路径、大小与 SHA-256 |

JSON 的 SHA-256 是 `689bf9ed12a03ff3e8dfe5992ae762d5098e386a796e49f859b7739a45ba98ef`。SQLite 中每条 `items.record_json` 与该 JSON 的对应条目一致。SQLite 配方表保存 ID、序列化器、规范化输入输出、原始编解码 JSON 的 SHA-256 和资源来源哈希；不重新分发完整上游原始配方或语言文件。

本配置的价格规则、数量和工艺服务费适用于这个服主选择的休闲生存环境。更换模组、配方或基准策略后需要重新生成，不把运行时的 `unreviewed` 保守补录当成可靠售价。

## 管理和定价边界

每个物品有独立的官方买价与回收基准。量产物参考回收比例通常 10%–15%，矿物和普通渔获通常约 20%；稀有物默认回收额度为 0。`default_sell_enabled=false` 和 `default_listed=false` 是该参考条目的默认建议，不会直接覆盖已有 51 商品的运行状态。

`owner_excluded` 表示服主不售卖的低值基础块、矿锭/矿块、基础压缩块和原木以外木制建材。`technical` 是创造、技术或特殊组件物品限制，两者分别保存。人工定价不能绕过这两个限制；其他低可信复杂物品需先人工核价，再通过管理接口决定是否上架。装备修理按照可信官方价与真实耐久缺损计算。

允许正常生产加工利润。循环无根配方不会产生低价；未知价不进入可靠成本图；概率和多产出没有冒充确定配方。详细说明见 [价格政策](PRICING_POLICY.md)，在线管理步骤见 [管理员指南](../docs/ADMIN_GUIDE.md)。

## 在本地重新生成

需要标准 Python 3，原始注册表导出及其成功退出回执、相匹配的服务端模组、客户端简中资源、原版资源索引 34 以及 51 项锚点配置。原始文件由服主管理，**不随此目录发布**。

```text
python tools/generate_prices.py --assets <assets-directory> --dump-report <local-export-receipt> --client <client-directory> --server-mods <server-mods-directory> --anchors <anchor-config> --output <candidate-output-directory> --resource-output <candidate-json>
python tools/verify_prices.py --database <candidate-output-directory> --dump-report <local-export-receipt> --anchors <anchor-config> --resource <candidate-json>
```

应先生成到候选目录，检查后再更新核心资源。输入回执带本机导出清单位置和哈希，生成器只在本机读取它，不把位置写入发布库。`--output` 下会生成额外的规范化证据 JSON 供本地审核；本发布配置已经在 SQLite 中保留必要证据，因此没有重复携带这些大文件。GoFish 注入类哈希变化时需要重新审查，不能直接跳过保护继续定价。

校验已发布文件时，验证器会在当前目录找不到完整 JSON 时读取核心资源，从而避免复制另一份大 JSON。原始数据缺失时，单靠数据库自检不能证明它等于真实加载的注册表；现有 `qa-report.json` 记录的是本次有原始证据参与的检查结果。

41 项检查包含：ID 完整覆盖、原价锚点、价格上下界、回收价差、默认开关、禁售/技术/低可信保护、装备参考、真实配方槽位与产量、容器/模具/概率、循环和未知输入、SQLite/JSON/CSV 全行一致，以及数据和工具中无本机绝对路径或服务器地址。它不表示正式服交易、多版本、故障恢复或长期经济表现已验证。

## 发布内容与许可

此目录不包含世界、玩家数据、账号、服务器地址、原始 QA 回执、运行日志、第三方 JAR、完整语言资源和完整上游原始配方。`inputs/` 只作为本地参数默认位置的约定，不应提交其中内容；本地 Python 字节码和临时输出也不属于交付文件。

自己的规则、工具和文档采用根目录许可。注册 ID、中文名称、配方关系和必要数量事实来自对应上游，不能因本项目许可声明而改称为项目原创内容。第三方说明见项目根目录相应声明；本目录不宣称拥有 Mojang 或模组作者的素材权利。

## 种源商店补充

[planting-stock.json](planting-stock.json) 包含43种实际注册的种子、可直接种植作物、菌类和树苗。合并到原51商品时新增31种、合计82种；4种既有农业物品归入农牧分类，保留既有买价、回收基准和额度。此文件是增量商品配置，不能直接覆盖整个市场或账本。

补充修正了7种活体植物被木材名称匹配误判为禁售的问题；椰树幼苗和模组杜鹃花根据实际树苗/催生行为设参考价16和12。种子袋、作物箱、压缩捆仍不销售。新增普通种源日回收64，树苗32，火把花种子和瓶子草荚果只按基准1余额回收、每天16；实际回收仍受统一市场浮动与实际买价减1上限约束。

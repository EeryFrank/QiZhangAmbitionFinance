# 第三方代码、资源与分发说明

本文件说明 GPL-3.0-only 项目代码之外的权利边界。它不是由本项目代第三方授予的许可。核对日期：2026-10-14。

## 项目自有代码

本项目自有 Java 代码、构建配置和自有文档采用根目录 [LICENSE](LICENSE) 所载 GPL-3.0-only。该声明不把 Minecraft、第三方模组、其资源或其翻译改为 GPL。

## 钱币贴图：Minecraft 衍生资源，非 GPL 原创美术

用户提供绿宝石参考图，并明确要求用脚本精确换色。核对后，其像素造型与明暗来自 Minecraft 原版 16×16 绿宝石纹理；以下三个文件保持该造型，仅替换调色板为铜、银、金。它们不是从空白独立创作的图形，本项目保留用户要求的外观。

公共目录：`finance/src/main/resources/assets/cozylife_economy/textures/item/`

| 文件 | SHA-256 |
| --- | --- |
| `copper_coin.png` | `068779718d117063bed47fd767305995efda4e092a90dcb10bdbbbf89cb86a68` |
| `silver_coin.png` | `dfe1ba4e77ffb5e361dad5d554019b2ed09a02aac4d63d1dcaccc622aa5ef993` |
| `gold_coin.png` | `d7974cad25cdc4ec26802467b588126919be286fedfbd7664a523bf4a5e50776` |

原版素材及其衍生内容的权利归 Mojang / Microsoft 等相应权利人保留。本项目不声称拥有原版素材，不对这些 PNG 授予 GPL、CC0 或其他通用素材再利用许可，也未取得针对这三张 PNG 的单独书面授权。

[Minecraft EULA 的 Using Mods / Content 条款](https://www.minecraft.net/en-us/eula)允许符合条件的独立模组分发，同时区分模组与含游戏本体的修改版本，且保留原版内容和衍生内容的权利。[Minecraft Usage Guidelines 的 Extended functionality and modifications / All uses 条款](https://www.minecraft.net/en-us/usage-guidelines)也将允许分发的模组限定为原创功能扩展，不能包含其大量受保护代码或内容。这些规定支持“合规的独立模组可分发”，不等同于给原版贴图颁发开源素材许可。

本次源码分发保留上述原权利声明。用户提供参考图及同意换色，不代表原版图形变成用户原创，也不使本项目取得 GPL 再授权权利。我们没有把官方允许符合条件的独立模组分发，表述为这三张 PNG 的单独授权；也不声称“全部资源 GPL”或完整清权。本声明说明实际来源与许可边界，不向使用者授予超出本项目权利的素材许可。

这些说明不要求擅自替换本服外观，也不构成 Minecraft 或其他第三方对本项目的背书。本项目不是 Minecraft 官方产品，未获 Mojang 或 Microsoft 批准，也不与其关联。

## 参考价格库与名称来源

`finance/src/main/resources/data/cozylife_economy/QiZhangOfficialPrices.json` 含本服生成的定价、分类与计算依据，并记录物品 ID、配方 ID、标签、上游中文短名称及来源信息。`name_source`、`provenance` 和来源哈希用于追溯，不表示上游作者参与或认可本服定价。

该文件中的计算结果和本服规则应与上游名称、翻译及配方描述的来源区分。Minecraft 简体中文短名称及各模组内置翻译保留相应上游权利；引用它们不意味着整个上游语言包、配方资源或模组转为 GPL。本项目没有附带完整第三方模组 JAR、完整语言资源包或第三方纹理包。

资料中保留已记录的来源与归属，来源哈希只用于追溯，不是许可证证明。项目未移除这些来源后把整份资料声明为原创，也不对上游内容追加开源再授权。

`pricing-profile-cozylife` 的精简 SQLite 记录注册 ID、配方 ID、序列化器名称、规范化输入输出及原始资料哈希，不收录完整原始配方文件或完整语言文件。注册 ID、中文名称和配方事实来自对应上游；本项目自己的定价规则和工具采用 GPL-3.0-only，但这一许可不覆盖或改变上游资料本身的权利。

## Gradle Wrapper

`gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar` 来源于 [Gradle v9.2.1](https://github.com/gradle/gradle/tree/v9.2.1)，保留其上游授权。参见 [gradle/LICENSE-Gradle.txt](gradle/LICENSE-Gradle.txt)。Wrapper JAR 与发行包 SHA-256 校验记录见 [verification/build-bootstrap.json](verification/build-bootstrap.json)。该工具 JAR 不属于本项目金融代码，也不是 Minecraft 游戏内容。

## 不随发布包内嵌的依赖

- Minecraft / NeoForge：构建时由官方服务取得，保留各自条款和许可证，不随模组重新分发游戏本体。
- ModDevGradle：构建插件，从公开仓库解析，不内嵌到模组。
- Waystones、Balm、Shogi 及 Shogi API：可选生活扩展的编译或开发运行依赖，保留作者许可；不复制其源码、纹理或 JAR 到仓库，不装进本项目生产 JAR。
- JUnit、Gson 等测试和运行 API：由构建依赖解析；本项目没有将测试框架或其类复制到生产模组。

依赖坐标和固定版本见 [docs/BUILDING.md](docs/BUILDING.md)。提及第三方项目名称仅用于说明兼容目标或构建用途，不代表已取得额外资产权利或完成全生态兼容验收。

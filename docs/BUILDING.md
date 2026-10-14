# 构建 QiZhangAmbitionFinance

## 固定工具链

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.3 |
| NeoForge | 26.3.0.50-beta |
| JDK | 25，完整开发工具包 |
| Gradle Wrapper | 9.2.1 |
| ModDevGradle | 2.0.148 |

构建配置以 [NeoForge 官方 26.3 MDK](https://github.com/NeoForgeMDKs/MDK-26.3-ModDevGradle) 为依据。使用 ModDevGradle 的 [无需反编译/重编译流程](https://docs.neoforged.net/toolchain/docs/plugins/mdg/#disabling-decompilation-and-recompilation)，降低初次配置的内存消耗。正常构建只需 JDK 25 和网络，不需要现服目录、私人 Maven、登录账号、Python 或预先复制的 Minecraft JAR。

## 从干净检出构建

把 `JAVA_HOME` 指向 JDK 25，并确保 `java -version`、`javac -version` 都是 25。可按个人环境设置 `GRADLE_USER_HOME` 保存下载缓存；项目没有硬编码缓存盘符。

Windows PowerShell：

```powershell
.\gradlew.bat --no-daemon --console=plain clean build
```

Linux/macOS：

```sh
bash ./gradlew --no-daemon --console=plain clean build
```

Gradle Wrapper 自动下载固定 Gradle 发行包，并按 `distributionSha256Sum` 验证。首次运行还会从 NeoForge、Mojang 和 Modrinth 公共服务解析必要依赖。构建默认最多两个 worker、2 GB Gradle 堆；依赖处理还需要额外进程和系统内存。构建成功不代表多人、游戏画面、经济规则或服务器负载已通过运行验收。

输出分别位于：

- `finance/build/libs/`：金融核心，以及 `-sources.jar`、`-javadoc.jar`。
- `cozylife-integration/build/libs/`：可选生活服务扩展，以及源码和文档 JAR。

核心 Javadoc 聚焦 `qizhang.ambitionfinance.api` 公共 API。`sources.jar` 保留各模块对应源代码。模块均不内嵌 Minecraft、NeoForge、Waystones、Balm 或 Shogi。只编译核心可执行 `:finance:build`；完整 `build` 会检查两个模块。

## 模块边界与可选依赖

`finance` 的保留注册 ID 为 `cozylife_economy`，便于已有世界迁移。`cozylife-integration` 注册 ID 为 `qizhang_cozy_services`，依赖核心，客户端界面与生活服务放在该扩展。金融核心不需要 Waystones、Balm 或 Shogi。

扩展仅在编译时解析以下作者发布的 NeoForge 26.3 版本，坐标固定为 Modrinth 不可变版本 ID：

| 模组 | 发布版本 | Maven 版本 ID |
| --- | --- | --- |
| Waystones | 26.3.0.1 | LJsZudo0 |
| Balm | 26.3.0.3 | QcBcRJZb |
| Shogi | 26.3.0.3 | wFw6qDQV |

Modrinth 仓库限制为 `maven.modrinth` 组，防止其影响其他 Maven 坐标解析。Shogi 的 API 位于模组内嵌 JAR，编译器不会自动展开，因此还从作者公共 Maven 显式解析 `net.blay09.mods:shogi-api:26.3.0.3`；该仓库只允许这一坐标。所有这些依赖都不会被源码库或输出包重新分发。

核心 JUnit 测试通过 Maven Central 使用 JUnit BOM 5.13.4、Jupiter 与 Platform Launcher，已接入 `check` / `build`；不把测试依赖带入正式模组。

## 开发运行

```sh
bash ./gradlew :finance:runServer
bash ./gradlew :cozylife-integration:runClient
bash ./gradlew :cozylife-integration:runServer -PwithWaystones=true
```

前两条不自动加入 Waystones。`-PwithWaystones=true` 只将固定的三个可选依赖加入本次开发运行；正式服仍需自行安装兼容版本。游戏运行目录属于各模块的 `run/`，不会被 Git 跟踪。首次服务端运行仍需用户自己阅读并同意 Minecraft EULA，构建脚本不会代为接受。构建和 CI 不启动游戏。

## 版本和许可证

发布版本集中在 `gradle.properties`；模组 metadata 通过 `${mod_version}` 展开。升级 Minecraft、NeoForge 或依赖后须重新进行编译和运行验证。

项目自有代码采用 **GPL-3.0-only**，见根目录 `LICENSE`。Gradle Wrapper 源脚本及 wrapper JAR 保留上游授权，见 `gradle/LICENSE-Gradle.txt`；它们不是本项目原创代码。第三方库、Minecraft 内容及作者发布的模组保留各自的许可证，不因本项目 GPL 声明改变。Wrapper 是唯一纳入版本管理的工具 JAR。

## 常见问题

- Java 版本不符：修改当前 shell 的 `JAVA_HOME`，重新执行 wrapper；不要修改构建脚本指向某台机器的绝对路径。
- 代理证书导致 `PKIX path building failed`：把组织的可信 CA 正确配置到当前 JDK，或在 Windows 使用其受信任证书库。不要关闭 TLS 验证，不要把本机证书库或代理凭据提交到仓库。
- 网络无法访问公开依赖仓库：先解决网络或证书问题。`--offline` 只适用于依赖已经完整下载的缓存，不能替代首次联网构建。
- 修改业务源码时的编译错误：以 Gradle 指出的 Java 文件修复。不要以同名旧 JAR、私人绝对路径或正式服务器类库替代当前源码。

GitHub Actions 以 JDK 25 执行相同的 `clean build` 并上传两模块产物；workflow 文件存在不等于远端 CI 已实际通过。首次公开推送后的运行结果需另行记录。

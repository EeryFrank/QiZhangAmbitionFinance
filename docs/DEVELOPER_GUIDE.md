# 开发者接入说明

当前公共 Java 接口为 `qizhang.ambitionfinance.api.FinanceApi`，`API_VERSION=1`。目标环境是 Minecraft 26.3 + NeoForge，当前运行验证对象是本服 Youer 组合；本次独立模块的实机验证仍待完成。没有 Bukkit/Vault 服务注册，也没有 LuckPerms 自动绑定。不要把下面接口示例当作上述插件已适配的声明。

## 生命周期与线程

在金融核心启动后用 `FinanceApi.forServer(MinecraftServer server)` 取得当前服务器实例；未启动或服务器实例不符会抛出异常。**所有调用必须在拥有该实例的服务器主线程执行**。从异步网络、数据库或其他线程进入时，通过 `server.execute(...)` 排队；不要在游戏主线程等待一个又依赖该主线程执行的 Future。

核心内部仍使用 `cozylife_economy` 注册 ID、物品命名空间和旧财务目录以保护已有存档；项目显示名与 API 包名已经独立。扩展不应另外实例化账本、直接修改 `state.json` 或缓存一个自己的余额真值。

## API v1 的真实签名

| 方法 | 结果和边界 |
| --- | --- |
| `static FinanceApi forServer(MinecraftServer)` | 返回本服务器核心接口 |
| `long balance(UUID) throws IOException` | 查询余额；不存在的账户视为 0，此查询本身不会持久创建账户 |
| `boolean hasAccount(UUID) throws IOException` | 查询账本是否存在账户 |
| `Mutation credit(UUID request, UUID player, long amount, String reason) throws IOException` | 可信服务端业务增加余额 |
| `Mutation debit(UUID request, UUID player, long amount, String reason) throws IOException` | 可信服务端业务扣除余额 |
| `Mutation transfer(UUID request, UUID from, UUID to, long amount, String reason) throws IOException` | 同一账本提交双方余额和幂等回执 |
| `JsonObject sendCoins(UUID request, UUID player, Coin coin, int count) throws IOException` | 向实际加入过的已知玩家发实物币邮件，不增加余额 |
| `JsonObject officialPrice(String item) throws IOException` | 返回含 `estimate`、`revision`、`market_revision` 的对象 |
| `JsonObject statistics() throws IOException` | 返回经济统计、当前/建议档位与人工参考说明 |
| `JsonObject snapshot(ServerPlayer)` | 返回玩家经济快照；检查 `available` 和 `message` |
| `JsonObject quote(ServerPlayer, String product)` | `product` 是 `#001` 类商品编号，返回服务端报价 |
| `JsonObject transactQuote(ServerPlayer, String quoteToken)` | 执行对应服务端一次性报价；检查返回快照/消息，不以“方法返回了对象”视为成交 |
| `void checkTravelBalance(ServerPlayer, long fee) throws IOException` | 检查当前在线玩家状态和传送所需余额，不扣款 |
| `boolean paidTeleport(ServerPlayer, long fee, String reason, BooleanSupplier teleport) throws IOException` | 足额先预扣，再同步执行真实传送；确定失败未移动时退回，成功后核验保存收据并提交 |

`Mutation` 是 `record Mutation(UUID requestId, boolean replay, long balance, long recipientBalance)`。`balance` 为原请求主体在提交后的余额；转账时 `recipientBalance` 为收款方余额，非转账操作为 0。重复回执保留原次操作的提交结果，不保证等于之后其他交易产生的最新余额；需要最新值时再查询。

`FinanceApi.Coin` 只有 `COPPER`、`SILVER`、`GOLD`，分别提供 `id()` 和 `value()`，面值是 1、100、10,000。

## 持久幂等余额操作

先在调用方业务记录中持久生成请求 UUID，再执行一次业务。超时、断线和重启后重试时复用**相同 UUID、操作、账户、金额、原因**。原因不能为空、不得含控制字符、最多 160 字符；金额为正整数。不同参数复用同编号会拒绝。不要每次重试都调用 `UUID.randomUUID()`。

以下方法适合由你的业务处理器调用，参数 `persistedRequestId` 必须已在业务侧保存；`onResult` / `onFailure` 是调用方回调：

```java
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import qizhang.ambitionfinance.api.FinanceApi;

static void transferReward(
        MinecraftServer server, UUID persistedRequestId,
        UUID payer, UUID recipient, long amount,
        Consumer<FinanceApi.Mutation> onResult,
        Consumer<Exception> onFailure) {
    server.execute(() -> {
        try {
            FinanceApi api = FinanceApi.forServer(server);
            FinanceApi.Mutation receipt = api.transfer(
                    persistedRequestId, payer, recipient, amount, "委托任务结算");
            onResult.accept(receipt);
        } catch (IOException | RuntimeException failure) {
            // 保留原编号与原参数，由业务侧核对结果，不能换编号补发。
            onFailure.accept(failure);
        }
    });
}
```

`credit`、`debit` 使用相同主线程和编号规则。它们是可信服务端程序集成接口，**不经过玩家命令权限过滤**；调用方负责授权业务与输入，不能直接把任意客户端“加余额”数据转发进去。两者与管理员实物币邮件是不同用途，面板发币应使用 `sendCoins`。

持久 API 回执目前上限 50,000 条，达到后拒绝新增操作并要求扩容迁移，不会清除旧回执来腾位置。钱包只读或写入失败可以抛 `IOException`，参数/余额条件失败可以抛运行时异常；写入结果不明时保留业务编号，不承诺在异常后“肯定没有扣款”。跨账本或跨其他插件数据库的分布式事务不在 API v1 保证范围内。

## 商店报价与邮件

在主线程调用 `api.quote(player, "#001")`，先检查返回 `available`，再读取 `quote.buy` / `quote.sell` 数组。每个选项带 `count`、`total`、`token`、`enabled`、`reason`，当前批量规格为 1、16、32、64，并受物品实际堆叠上限限制。界面展示服务器给出的整批 `total`，不能自行用首件单价乘数量。

只有玩家明确选择一个 `enabled=true` 的选项后，才调用 `api.transactQuote(player, token)`。令牌绑定玩家、当前连接、账户/市况版本及报价组，约 30 秒过期。修改配置、成交或重新报价都可能使旧令牌失效；失效时重新展示报价，再取得玩家确认，不静默换新令牌成交。报价失败通常通过返回 JSON 的 `message` / `available` 表达，因此也要检查 JSON 结果。

实物币投递示例（同样在主线程）：

```java
JsonObject receipt = api.sendCoins(
        persistedJobId, knownPlayerId, FinanceApi.Coin.SILVER, 3);
// receipt: delivery="mailbox", balance_changed=false, job_id, mail_id, replay ...
```

这里的 `persistedJobId` 是持久发币任务编号，同编号只能同收件人、同币种、同数量。玩家名单由真实加入记录构成，不能拿未知 UUID 伪造已加入玩家。邮件和任务回执一起保存；领取邮件后旧任务凭据仍保留。不要把余额接口的收据编号与发币任务号的存储空间混为同一张去重表。

管理控制台另有 `qzeconomy api <requestUUID> ...` 的 JSON 协议，用于面板读取商品、玩家、任务与白名单管理。它不是 HTTP 服务，也不是 Vault API。字段和权限说明见 [管理员指南](ADMIN_GUIDE.md)。

## 付费传送与可选服务

`checkTravelBalance` 只是预检查。目标维度、区块加载、落点安全、玩家同意和取消策略由服务扩展完成；准备完成后，在主线程使用同步回调。费用范围为 0 至 1,000,000,000 点，说明不能为空、最多 128 字符：

```java
boolean arrived = api.paidTeleport(player, fee, "家园传送", () -> {
    // 使用已经加载并验证的目标同步传送。
    return performActualSynchronousTeleport();
});
```

这是调用形状示例，`performActualSynchronousTeleport()` 由接入者实现。回调返回 `true` 必须代表位置已经实际转移成功；返回 `false` 必须保持位置不变。不得在回调中只排队一个未来传送再返回 `true`，也不得阻塞主线程加载冷区块。

结算顺序为：

1. 核实当前在线连接、状态和足额余额。余额不足在原生回调之前拒绝，不执行传送。
2. `ledger.prepare` 先持久保存包含预扣余额的待处理状态，再执行回调。若调用前余额为 B、费用为 F，在回调内调用 `FinanceApi.balance(playerUUID)` 会读到 **B−F**，不能再次支出这笔钱。其他写入仍禁止重入；不要依靠 `snapshot`、`hasAccount` 或统计查询在该结算窗口可用。
3. 真实成功后保存玩家收据与位置，核验成功，再提交预扣费用。
4. 回调返回 `false`，或抛出运行时异常但能够确认维度和位置均未改变时，恢复旧收据并中止待处理交易，**退回本次预扣且只退一次**，返回 `false`。不是再发一笔任意金额的 `credit`。
5. 如果玩家已经移动却返回失败/抛异常，或存储核验结果不明，则保留待核实收据、保护停用并断开该玩家。这种状态不能当作确定失败盲目退款，也不能以新业务重复调用传送来“补偿”。

退款由 `paidTeleport` 的同一结算流程完成，API v1 没有单独的任意“传送退款”方法。接入者不要在收到 `false` 后再调用 `credit`，否则会重复退钱。`paidTeleport` 没有外部请求 UUID；其收据由核心为本次执行生成，**不能把它当作可任意重复调用的持久幂等余额 API**。重启遇到未决存档收据时，需要先核查恢复结果。

可选模块还通过 `cozylife.economy.network.NavigationExtension` 接口提供 `handles(String)`、`execute(ServerPlayer,String,String)`、`sections(ServerPlayer)`。扩展不得认领金融核心已有动作，不得覆盖核心快照键或访问替换账本；自身生命周期由实现维护。它仍属于内部集成接口，不等于其他版本的稳定插件桥。

## 可选权限解析器

当前接入点是 `FinancePermissions.of(server).setResolver(BiFunction<UUID,String,Boolean>)`。在主线程设置解析器，权限节点形式为 `qizhangfinance.admin.<操作>`：

```java
import cozylife.economy.server.FinancePermissions;

server.execute(() -> FinancePermissions.of(server).setResolver((uuid, node) -> {
    // 返回权限适配层已有缓存的明确结果；不在主线程做远程查询。
    return lookupCachedPermissionOrNull(uuid, node);
}));
```

`lookupCachedPermissionOrNull` 是接入方实现。返回 `true` / `false` 为明确决定，`null` 交回本地 UUID/OP 规则；抛异常会拒绝。UUID 拒绝名单优先于外部解析器；解析器的明确决定优先于允许名单和 OP。只存在一个解析器槽位，替换会覆盖先前注册者，因此适配方需要协调生命周期。该类是当前实现接入点，尚未包装进稳定 `FinanceApi` 门面，也未自带正式 LuckPerms/Vault 桥。

解析器只在服务器主线程调用，实际执行指令时仍逐操作实时校验。Youer 异步发送命令树时，只读取主线程发布的不可变“根指令可见性”快照，不读配置文件或调用解析器；快照绑定 UUID 和当前玩家连接对象，缺失时拒绝。登录会更新快照并重新发送命令树，退出会清理；权限重载/更换解析器会更新，另每 20 tick 检查外部权限变化，仅可见性改变时重发。因此命令显示最多可能延迟约 1 秒，已经失去的执行权限不会因旧显示快照继续生效。此边界已有缓存并发与重连身份测试；第三方解析器适配仍需接入方单独实测。

## 参考库与验证边界

### 签到消息与持久化

核心保留 `checkin_calendar`（id 为 `YYYY-MM`，空值取当前北京时间月份）、`checkin_prepare`（id 为 `YYYY-MM-DD`）及 `checkin_confirm`（id 为服务端返回 token）三种动作。沿用现有连接序号及网络限流，不信任客户端的奖励、UUID、VIP 或连续天数。所有响应都携带完整账户快照与 `checkin` 摘要。准备报价不会写账；取消只需丢弃客户端 token，重新请求日历会撤销该连接的报价。

`checkin` 摘要包含 `registered_date,today,signed_today,current_streak,longest_streak,makeup_credits,vip_bonus_active`。日历对象 `checkin_calendar` 包含 `month,today,registered_date,previous_month,next_month,days`；每个日期提供 `date,status,can_prepare,reward,makeup,vip_bonus_applied`。`status` 固定为 `signed/missed/today/future/before_registration`，仅 `can_prepare=true` 的日期允许交互。已签日期 reward 和 VIP 标记是实际结算历史。

`checkin_quote` 提供 `token,date,kind,enabled,reason,balance_reward,uses_makeup_credit,streak_for_date,longest_streak_after,milestones,expires_at,vip_bonus_applied`；milestones 元素为 `days,item,name,count`。确认后会返回刷新日历及 `enabled=false,token=""` 的空报价，界面应保留成功反馈，不把空报价当成过期。客户端使用 `expires_at-server_time` 加单调时钟展示倒计时。VIP 报价另有 `remaining_days,can_renew,makeup_credit_grant,expires_at`；不要仅因 `vip_active=true` 禁止续费。

账户可选字段 `checkin` 的 schema 为 1：`registered_date,registered_at,registration_source,makeup_credits,signed,claimed_milestones`。signed 是日期到 `{reward,makeup,vip_bonus_applied,settled_at,streak_at_settlement}` 的映射；claimed_milestones 是天数阈值到领取时间戳的映射。只有真实登录会登记，纯离线 API 建账不会登记。旧账户缺少该字段仍可加载。

签到 token 一次性绑定 UUID、具体连接对象、完整账户快照、有效规则、北京时间日期和 VIP 状态；确认时先消费 token，再重新计算并走已有玩家存档收据事务。重连或重启后旧 token 不可用，日期记录防止重复领取。奖励算法以高精度定向上下界证明最终整数 floor 一致，避免巨额指数分配无界内存；无法证明安全结果或超过整数金额范围会拒绝，绝不发放近似余额。新增统计 `checkin_issued` 与 `checkin_milestone_coin_issued` 分列余额投放和银币邮件投放。现有永久补签、签到及里程碑字段会随统一账本备份。

本服价格库是带来源和可信度的整合包配置，不是 Minecraft 通用价格。读取 `officialPrice(item)` 的 `estimate` 时，应同时查看 `eligible`、`technical`、`owner_excluded`、`confidence` 和 `reason`。运行时新物品可能仅有 `unreviewed` 保守值，不能将其自动上架。

工程测试应覆盖同请求重放/参数冲突、重启回执持久化、转账守恒、卖价上限、配置并发版本、组件匹配、实物币邮件余额不变、报价失效和传送失败。故障注入、其他服端、跨版本和长期经济运行必须各自取得证据。当前本服价格数据的 41 项离线 QA 不能代替这些运行验收。

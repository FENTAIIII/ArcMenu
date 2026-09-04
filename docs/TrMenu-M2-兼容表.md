# TrMenu M2 行为兼容表

基线为本地 `TrMenu-stable-v3` 3.12.5 的公开示例和公开实现行为。ArcMenu 参考其使用习惯与执行顺序，没有复制源码。ArcMenu 的后端对象是屏幕轴对齐矩形，不是容器 slot，因此本表只覆盖两者语义相交的部分。

配套 fixture 为 `core/src/test/resources/fixtures/trmenu-m2.yml`，正式示例为 `paper/src/main/resources/menus/example.yml` 与 `details.yml`。

## 点击入口与顺序

| TrMenu 习惯 | ArcMenu M2 | 结果 |
| --- | --- | --- |
| `actions` 可为标量、列表或按点击类型分组 | 支持 | 标量/列表视为 `all` |
| `all` 与具体点击同时匹配 | 支持 | 按 YAML 分组顺序执行；每组内按较小 `priority` 优先，同值按文档顺序 |
| `left`、`right`、`shift-left`、`shift-right` | 配置语法均保留 | 当前准星输入只产生 `right` 或 `shift-right`；左键只保护捕获面，不执行动作 |
| `shift` | 支持 | 同时匹配两种 shift 点击；当前准星实际只能产生 `shift-right` |
| 条件动作组的 `condition/actions/deny/priority` | 支持 | 点击时重新检查条件，失败执行 deny |
| 后端区域顶层 `condition` 与 `deny` | ArcMenu 扩展 | 用于整个矩形的执行条件；不改变可见前端和 hover 区域 |
| 容器 number-key、drop、double-click、drag | 不适用 | Display 菜单没有原版容器 slot，不伪造这些输入 |

点击采用私有捕获结构：每会话一个仅该玩家可见、非持久化的 `Interaction`。鼠标模式与触控模式都只接受右键；右键实体与右键空气/方块仍汇入同一入口，所有主操作统一做 75ms 去重。攻击捕获面始终会被取消，但不会转为菜单主操作。

## 动作

动作只接受 TrMenu v3 的 `action: value` 或单项映射写法。`player` 是 TrMenu `command` 的正式别名，因此 `player: spawn` 有效；`[player] spawn` 属于另一套方言并会明确报错。

| 动作族 | 当前写法与行为 |
| --- | --- |
| 消息 | `tell/message/msg/talk`、`tellraw/json`、`chat/send/say`、`actionbar`、`title/subtitle`、`bossbar`；保留 TrMenu 的反引号多词参数习惯 |
| 命令 | `command/cmd/player/execute`、`console`、`op/operator`；分号分隔多条命令，执行前展开占位符；`consol` 仍视为拼写错误 |
| 声音 | `sound/playsound: SOUND-volume-pitch`；旧枚举名如 `BLOCK_CHEST_OPEN` 与现代 `block.chest.open` 归一到同一 registry key，未知声音每个名字只警告一次 |
| 菜单 | `open/menu/trmenu`、`close/shut/silent-close`、`refresh/update`、`reload-inventory`、`return/break`；`back` 是 ArcMenu 的返回栈扩展 |
| 路由参数 | `open: details arg1`，多词参数用反引号包围；目标菜单收到 `{0}`、`{1}` 与 `%arcmenu_args_0%`。静态目标整目录校验，动态占位符目标运行时校验 |
| 代理连接 | `connect/server/bungee`；通过标准 `BungeeCord` plugin message 发送 Connect 请求，代理未配置时由代理侧决定结果 |
| 状态 | `set/rem/del-meta`、`set/rem/del-data`、`set/rem/del-global-data`、`set/clear-args`。meta 仅保留到本次服务端进程结束；data/global-data 写入 `data.properties` |
| 状态读取 | `{meta:key}`、`{data:key}`、`{globaldata:key}`，以及 `%arcmenu_meta_key%`、`%arcmenu_data_key%`、`%arcmenu_globaldata_key%` |
| 物品 | `give-item`、`take-item` 支持 material/amount/data/model-data/name/lore/head 匹配项；`repair-item` 与 `enchant-item` 支持 TrMenu 的背包目标名称。当前构造原版物品，CE 物品构造留在 CE 阶段 |
| 经济 | `give/take/set-money` 通过可选 Vault provider；`give/take/set-points` 通过可选 PlayerPoints。桥不存在时明确警告，不让插件硬依赖它们 |
| 输入 | 映射式 `catcher` 已支持多阶段 `CHAT`、start/cancel/end、`{meta:input}`、阶段 ID 与 `retype`；取消词为 `cancel/quit/end/q` |

标量动作可用 `&&&` 或 `_||_` 组合。`delay: ticks` 会累计推迟后续动作；每项动作支持 TrMenu 的 `{Delay=}`、`{Chance=}`、`{Condition=}` 与 `<players>`/`<players=condition>` 选项。延时任务绑定创建它的菜单会话，切换或关闭后自动失效，避免旧菜单动作落入新菜单。

## 条件

| 条件族 | 支持写法 |
| --- | --- |
| 权限 | `perm node`、`perm *node`、`permission node` |
| 比较 | `check left is right`、`is not`、`==`、`!=`、`>`、`>=`、`<`、`<=`、`contains` |
| 布尔 | `true`、`false`、`! condition`、`not condition`、`&&`、`||` |
| 聚合 | `all [ condition ; condition ]`、`any [ condition ; condition ]` |
| 动态值 | 先展开 ArcMenu 内建占位符，再由可选 PlaceholderAPI 展开；数值两侧均可解析时按数值比较，否则忽略大小写比较文本 |

任意 JavaScript、Kether 和不能识别的自然语言条件在加载阶段拒绝。条件只在服务端主线程、当前玩家上下文中执行。

## update、动态文本与 tooltip

- text 与后端 tooltip 的 `update` 为单一 tick 间隔；缺省 `-1` 表示不周期刷新，但仍执行初次求值和显式 refresh。
- `0` 与小于 `-1` 的值拒绝。值没有变化时不重复设置 TextDisplay 文本。
- 内建 `%player_name%`、`%player_uuid%`、`%player_world%`、`%player_x%/%player_y%/%player_z%` 可直接使用；检测到 PlaceholderAPI 时继续展开其占位符。
- 普通 tooltip 使用每会话一个 TextDisplay，配置在 `tooltip.yml`。命中变化、指针位置变化或 tooltip update 到期时更新，移出区域时隐藏。
- 管理员 `preview` 永不执行动作；只有 `/arcmenu open <id>` 进入运行态。

## 当前不宣称兼容

M2 仍不宣称完整 TrMenu 文件可直接加载。以下边界会在加载时明确报错，不能静默当作已执行：

- Kether、JavaScript、JEXL、NovaScript 与 function 需要独立脚本后端；ArcMenu 当前不会把未知动作交给默认 Kether 执行器。
- SIGN、ANVIL、BOOK catcher 需要跨 1.21.1–26.2 的输入界面适配；当前只接受 CHAT。
- Layout、Icons、slot、容器 page、set-title/set-property/reset 等依赖原版容器布局的动作不适用于 Display 菜单；页面应拆为独立菜单并使用 `open/back`。
- edit-item 的全部 NBT/组件改写、Triton lang、MeowEco 和 TrMenu 的其他第三方物品插件桥尚未实现。ArcMenu 的内容插件目标仍只有 CraftEngine。
- TrMenu 的任意脚本条件仍不接受；当前条件能力见下节。

新增兼容项必须先加入 fixture、记录时序和失败行为，再进入运行时。不能因为动作名称相同就宣称其第三方数据格式已经完全一致。

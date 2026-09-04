# 第三方应用 API

ArcMenu 的应用 API 用于签到、登录、公会、商店、聊天、动态列表、通知中心和设置等需要持续交互的界面。它和一次性 `ArcMenuRoute` 不同：route 只适合把一个名字映射到一次回调；application 拥有明确的玩家会话、屏幕坐标系、输入流、返回栈和关闭生命周期。

## 职责边界

ArcMenu 负责：

- 首次打开时确定屏幕平面，并在菜单与应用之间切换时保持同一原点和朝向；
- 鼠标模式固定摄像机与光标，触控模式使用射线；两种模式当前都以右键作为主操作；
- 将逻辑画布坐标转换为世界坐标；
- 鼠标模式下把相邻热栏切换转换为滚轮步数；
- 菜单与应用共用的玩家返回栈；
- 玩家退出、插件停用、应用替换、显式关闭、异常和世界重定位时的统一清理；
- 把所有应用回调限制在服务端主线程，并为异步结果提供安全回到主线程的入口。

第三方插件负责：

- 业务数据、权限以外的业务条件、分页、购买、登录、公会、消息和通知规则；
- 应用自己的 Display Entity 绘制、局部命中区域和内部页面状态；
- 鼠标模式收到 `onScroll` 后如何改变列表位置；
- 触控模式根据 `onActivate` 与后续 `onPointerMove` 实现侧滑块拖动；
- 使用聊天、铁砧或其他方式采集文字。ArcMenu 不把某一种输入 GUI 强加给应用。

第三方插件不得创建第二套摄像机、全屏 Interaction 捕获面或鼠标实体。这样多个插件不会争抢视角、点击包和热栏事件。应用自己的 Display Entity 应通过 `context.spawnPrivate(...)` 创建；ArcMenu 会在实体加入世界前设置为默认不可见，只向当前玩家显示并自动追踪清理。

## 注册与打开

附属插件把 ArcMenu 声明为 `depend` 或 `softdepend`，编译时以 `provided` 方式依赖独立的 `com.fentai:arcmenu-api` 构件，然后从 Bukkit `ServicesManager` 获取 `ArcMenuApi`。应用 ID 必须是唯一的 `namespace:path`；重复注册会直接失败。注册句柄可以单独注销，插件停用时 ArcMenu 也会按 owner 自动注销并关闭残留会话。不要依赖 `com.fentai.arcmenu.paper` 下的实现类。

```java
ArcMenuApi arcMenu = Bukkit.getServicesManager().load(ArcMenuApi.class);
ArcMenuApplicationOptions options = ArcMenuApplicationOptions.builder()
    .permission("myplugin.shop")
    .captureMouseScroll(true)
    .tickInterval(1)
    .build();

ArcMenuApplicationHandle handle = arcMenu.registerApplication(
    this,
    "myplugin:shop",
    options,
    context -> new ShopSession(context)
);
```

菜单 YAML 只有下面这一种应用动作：

```yaml
actions:
  right:
    - 'open-app: myplugin:shop featured `two words`'
```

`openapp:`、`app:`、`open_app:` 和 `[openapp]` 都不是 ArcMenu 语法。Java 也可调用 `arcMenu.openApplication(player, "myplugin:shop", arguments)`。

## 屏幕与坐标

`ArcMenuApplicationContext.surface()` 返回当前权威 `ArcMenuSurface`。`canvas.width/height` 是逻辑尺寸，中心是 `(0, 0)`，X 向右，Y 向上；`surface.toWorld(x, y, depth)` 把逻辑坐标转换为世界位置，正 depth 朝向玩家。`origin()`、`right()`、`up()`、`normal()` 都返回副本，附属插件修改这些对象不会破坏宿主状态。

`inheritCanvas` 默认为 true。从菜单打开应用时，应用沿用该菜单的设计画布和本次会话的屏幕平面；直接通过 Java 打开时使用 options 中的默认 320×180 画布。设为 false 后始终使用应用自己的画布。ArcMenu 仍会根据 `offset.yml` 中当前鼠标模式或触控模式的距离换算实际 `pixelsPerBlock`。

首次创建以及传送、重生、切世界、摄像机或实体修复后，ArcMenu 调用 `onSurfaceChanged`。重定位前，所有由 `spawnPrivate` 创建或经 `context.track(entity)` 登记的实体都会被移除；应用应在该回调中按新 surface 重建它们。任务和非实体资源会继续保留到整个应用会话关闭。`track(entity)` 只接管清理，不会替应用修正已经公开生成的实体，因此新实体优先使用 `spawnPrivate`。

## 输入模型

`onPointerMove` 提供当前与上一次逻辑坐标，坐标可能为 null。`onActivate` 是模式已经归一化后的主操作：鼠标模式与触控模式当前都只发送右键，同时仍附带实际按钮、模式和潜行状态。应用可在悬停自己的可交互区域时调用 `context.setCursorInteractive(true)`，ArcMenu 会切换到 `images/mouse/choose.png`。

启用 `captureMouseScroll(true)` 后，ArcMenu 只在鼠标模式监听 `PlayerItemHeldEvent`：相邻槽位与 `8↔0` 环绕变成 `+1/-1`，并取消真实持有槽变化；数字键造成的跨槽跳转会被取消但不会伪装成滚轮。服务端协议无法可靠区分“按相邻数字键”和“滚轮一格”，因此应用只能把它们视为同一方向输入。触控模式不捕获热栏，应用用指针和侧滑块处理滚动。

`onTick` 的间隔由 `tickInterval` 控制。应用应只在状态变化时更新实体，不能把每 tick 全量销毁重建当作渲染循环。

## 导航与生命周期

`context.openMenu`、`context.openApplication` 和 `context.back` 使用同一玩家返回栈。进入应用时 YAML 菜单实体会被移除，但屏幕平面和鼠标摄像机继续沿用；返回时重新创建来源菜单。应用内部页面是否另建自己的栈由应用决定。

关闭原因包括请求关闭、返回、被其他界面替换、玩家退出、ArcMenu 停用、应用提供方停用和回调异常。`onClose` 最多调用一次。之后 ArcMenu 会取消所有通过 `track(BukkitTask)`、`runLater`、`runTimer` 创建的任务，移除 tracked entity，并逆序关闭 tracked `AutoCloseable`。即使应用自己的 `onClose` 抛出异常，强制清理仍会继续。

任一 open、surface、pointer、activate、scroll 或 tick 回调抛出异常时，ArcMenu 会关闭该会话、记录应用 ID 与阶段，并尝试恢复返回栈中的来源界面。一个应用故障不会进入另一个应用的回调。

## 异步业务

所有回调和除 `execute` 外的 context 方法都要求服务端主线程。数据库或网络工作由附属插件异步执行；得到结果后调用 `context.execute(runnable)`。只有原应用会话仍然活动时 runnable 才会执行，从而避免迟到结果修改已经关闭或被替换的界面。

`runLater` 与 `runTimer` 使用 ArcMenu 的受控调度器，并自动绑定会话生命周期。应用也可以跟踪自己创建的 BukkitTask，但未登记的第三方任务仍由第三方插件自己负责取消。

## 能力协商

`apiVersion()` 当前为 `1`。`capabilities()` 可检查应用会话、导航、屏幕平面、指针、鼠标热栏滚轮、资源追踪和 surface 重定位能力。附属插件应在启用时检查自己必需的枚举能力，不依赖实现类或反射访问 ArcMenu 内部对象。

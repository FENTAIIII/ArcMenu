# M5 动画与 API

M5 把动画定义放在插件目录根部的单个 `animations.yml` 中。菜单 YAML 仍只描述前端节点、固定的后端点击区和动作。前端变换轨道不会移动或旋转后端点击区；后端始终与屏幕横竖对齐。进入、切换和退出过渡期间暂停业务点击，过渡结束后才按原后端矩形命中。

## 文件结构

```yaml
schema-version: 1

transitions:
  shop-slide:
    enter:
      duration: 8
      easing: ease-out
      offset: {x: -36, y: 0, z: 0}
      scale: {x: 0.9, y: 0.9}
    switch:
      duration: 7
      offset: {x: 36, y: 0, z: 0}
    exit:
      duration: 6
      easing: ease-in
      offset: {x: 0, y: -28, z: 0}

tracks:
  card-bob:
    target: action-card
    property: offset
    duration: 8
    easing: ease-in-out
    loop: ping-pong
    trigger: api
    keyframes:
      - {at: 0, value: {x: 0, y: -35, z: 1}}
      - {at: 1, value: {x: 0, y: -25, z: 1}}

menus:
  shop:
    transition: shop-slide
    tracks: [card-bob]
```

过渡作用于新菜单的整个前端根节点。`enter` 从配置姿态回到正常姿态；`switch` 在菜单 open/back/扩展路由切换时用于新菜单；`exit` 从正常姿态移动到配置姿态后才清理实体。过渡只支持 `offset` 和 `scale`，不支持 opacity。

轨道的 `target` 是当前菜单中的前端元素 ID。`duration` 以 tick 为单位，范围为 1–72000。关键帧 `at` 必须严格递增、不能重复，并且从 0 开始、到 1 结束。变换关键帧填写该节点的绝对局部值，不是相对增量：

| property | value | 适用目标 | 插值 |
| --- | --- | --- | --- |
| `offset` | `{x, y, z}` | 所有前端节点 | 线性数值插值 |
| `rotation` | `{x, y, z}` | 所有前端节点 | 线性角度插值；矩阵仍按 Z、X、Y 组合 |
| `scale` | `{x, y}` | 所有前端节点 | 线性数值插值，不能为 0 |
| `content` | 字符串 | 仅 `text` | 到达关键帧时离散切换 |
| `opacity` | 0–255 整数 | 仅 `text` | 数值插值 |

`content` 和 `opacity` 用于 group、image、item、block、rectangle、frame 或 line 都会使整个 `animations.yml` 校验失败。整体/组透明度动画没有旁路写法。

`easing` 可为 `linear`、`ease-in`、`ease-out`、`ease-in-out`。`loop` 可为 `once`、`repeat`、`ping-pong`。`trigger: open` 在菜单创建时自动启动；`trigger: api` 等待动作、命令或 Java API 启动。`once` 到达末帧后继续拥有该属性，直到显式停止、同属性轨道替换它或菜单关闭。

同一会话内，一个 `target.property` 同时只有一个所有者。启动另一条相同目标和属性的 API 轨道会替换旧轨道。配置阶段禁止把两条相同所有权的 `open` 轨道绑定到同一菜单。关闭菜单时先取消所有节点轨道，再播放退出过渡；退出结束后统一移除实体。插件只在时间线快照变化时更新既有 Display，不会为每一帧创建实体；变换动画也不会反复展开无关的静态文字 Placeholder。

## 菜单动作与命令

菜单动作沿用现有动作列表语法：

```yaml
actions:
  right:
    - 'animate: card-bob'
    - 'animate: card-scale'
  shift-right:
    - 'stop-animation: card-bob'
```

静态动画 ID 必须属于该菜单在 `animations.yml` 绑定的轨道，否则校验失败。`animation:`、`play-animation:` 是 `animate:` 的别名，`cancel-animation:` 是 `stop-animation:` 的别名。管理员可使用：

```text
/arcmenu animations
/arcmenu animate <轨道ID>
/arcmenu stop-animation <轨道ID>
```

修改菜单或动画后执行 `/arcmenu validate` 可同时校验候选菜单和动画而不应用。`/arcmenu reload` 只有在菜单与 `animations.yml` 都通过时才一起替换；失败会保留当前定义和会话。

## Java 附属 API

`com.fentai.arcmenu.api.ArcMenuApi` 通过 Bukkit `ServicesManager` 发布。附属插件应在 `plugin.yml` 中加入 `depend: [ArcMenu]` 或 `softdepend: [ArcMenu]`，然后在主线程取得服务：

```java
package example;

import com.fentai.arcmenu.api.ArcMenuApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExampleArcMenuAddon extends JavaPlugin {
    private ArcMenuApi arcMenu;

    @Override
    public void onEnable() {
        arcMenu = Bukkit.getServicesManager().load(ArcMenuApi.class);
        if (arcMenu == null) {
            throw new IllegalStateException("ArcMenu API service is unavailable");
        }
        arcMenu.registerRoute(this, "myaddon:shop/main", (player, arguments) ->
            arcMenu.open(player, "m5-animation", arguments)
        );
    }

    @Override
    public void onDisable() {
        if (arcMenu != null) arcMenu.unregisterRoutes(this);
    }
}
```

菜单可用 `open: myaddon:shop/main arg1 arg2` 进入这条路由。路由 ID 必须是小写的 `namespace:path`，不同插件不能占用同一 ID；ArcMenu 也会在附属插件禁用时清理其路由。处理器返回 `true` 表示已接受路由，返回 `false` 表示拒绝。

公共方法还包括 `open`、`close`、`playAnimation`、`stopAnimation`、`animations` 和 `dispatchRoute`；M6 另增 `pointerMode`、`setPointerMode` 与 `pointerPolicy`。正式第三方应用会话、统一输入与资源所有权契约见 [第三方应用 API](第三方应用API.md)。全部方法要求在服务端主线程调用；参数列表会防御性复制。用公共 API 打开的普通菜单或应用会进入同一返回栈，并继续复用本次会话第一次打开时的平面。

## M5 人工验收

1. 完整重启测试服后执行 `/arcmenu open m5-animation`，确认菜单从左侧进入，标题内容/透明度变化，物品持续旋转。
2. 右键中间卡片，确认 group 的背景和文字一起上下移动、缩放；后端预览矩形本身不随动画移动。
3. 在进入/切换/退出画面尚未归位时右键，确认不执行区域动作；归位后点击原后端矩形应执行，追逐动画后的前端图形不应改变服务端命中位置。
4. 潜行右键走 `arcmenu:example-details` 公共路由，确认切换不重新取玩家视角；back 也复用原平面。
5. 用命令停止并重新启动轨道，确认停止后恢复节点 YAML 基础值；执行 `/arcmenu close`，确认节点轨道立即取消、退出过渡完成后实体数回落。
6. 用测试附属插件注册、调用、覆盖冲突和禁用路由，确认 API 返回值、主线程限制和自动清理符合预期。

自动测试只验证解析、调度、矩阵和生命周期语义，不能代替客户端画面、点击位置和附属插件的人工审阅。

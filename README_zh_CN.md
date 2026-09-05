<p align="center">
  <img src="assets/arcmenu-opening.gif" alt="ArcMenu 开场动画" width="640">
</p>

<h1 align="center">ArcMenu</h1>

<p align="center"><strong>为你的 Minecraft 服务器，做一套真正属于它的界面。</strong></p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="https://github.com/FENTAIIII/ArcMenu-Editor">可视化编辑器</a> ·
  <a href="docs/第三方应用API.md">应用 API</a> ·
  <a href="LICENSE">MIT 许可证</a>
</p>

<p align="center">
  <a href="https://github.com/FENTAIIII/ArcMenu/actions/workflows/build.yml"><img src="https://github.com/FENTAIIII/ArcMenu/actions/workflows/build.yml/badge.svg" alt="构建状态"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-38bdf8.svg" alt="MIT 许可证"></a>
  <img src="https://img.shields.io/badge/Paper-1.21.1--26.2-8b5cf6.svg" alt="Paper 1.21.1 至 26.2">
</p>

ArcMenu 是一款高级菜单插件。你可以为你的 Minecraft 服务器添加极其自由的自定义菜单、动画以及界面，**无需玩家安装任何客户端模组**。所有内容都通过资源包加载（也可在不使用资源包的情况下运行，但功能会受限），运算全部在服务端执行。

该插件利用了 Mojang 自 Minecraft 1.19.2 版本起新增的游戏特性，功能覆盖面十分广泛。

你是否想要制作与众不同的菜单与动画，让你的服务器在一众服务器中脱颖而出？那么这款插件就是你的不二之选 :)

> ArcMenu 不仅是个菜单插件，还是一个开源、由服务端驱动的 Minecraft UI 框架。它把玩家私有的展示实体、固定屏幕平面、资源包图形、动画时间线、Tooltip 与公开应用 API 组合起来，让界面成为服务器体验的一部分。它所面向的不只是“更多种菜单”。我们的目标是让一台服务器逐渐生长出自己的应用生态：账号、音乐、公会、商店、任务等接入插件继续掌管各自的数据与规则，ArcMenu 统一承担屏幕、输入、导航和生命周期。

> **开发预览：** 源码现已开放，供开发、审阅与共同改进。首个稳定版本发布前，配置格式、API 与编辑协议仍可能调整；自动测试也不能替代游戏内画面和坐标的人工验收。

## 看看我们的菜单可以被做成什么样子

下面的概念界面展示第三方插件接入 ArcMenu 后的可能性。图中的登录、音乐、副本与社交系统用于演示 API 潜力，并不是 ArcMenu 内置的业务应用。

<table>
  <tr>
    <td width="50%"><img src="assets/music-app.png" alt="音乐播放器概念界面"></td>
    <td width="50%"><img src="assets/login-app.png" alt="账号登录概念界面"></td>
  </tr>
  <tr>
    <td align="center"><strong>音乐插件</strong></td>
    <td align="center"><strong>拥有服务器自身风格的登录流程</strong></td>
  </tr>
  <tr>
    <td><img src="assets/dungeon-app.png" alt="副本对话概念界面"></td>
    <td><img src="assets/social-app.png" alt="社交消息概念界面"></td>
  </tr>
  <tr>
    <td align="center"><strong>剧情与副本交互</strong></td>
    <td align="center"><strong>完整的社交工作区</strong></td>
  </tr>
</table>



## 现在已经具备什么

- **服务端场景：** 通过 YAML 组合文本、图片、物品、方块、矩形、边框、线段和前端嵌套组，并只向目标玩家显示。
- **两种交互模式：** 触控模式和鼠标模式共享逻辑画布、后端命中区、Tooltip、导航与清理规则；两种模式当前都以右键作为主操作。
- **固定的屏幕导航：** 菜单和第三方应用复用首次打开时的屏幕平面，切换路由不会让界面跟随玩家视角重新定位。
- **动画时间线：** 支持菜单进入、切换、退出过渡，以及节点位置、旋转、缩放、文本内容和文本不透明度轨道。
- **资源工作流：** 支持 PNG 图片、自定义 Tooltip 外观、原版物品与方块，以及 CraftEngine 物品模型和资源包合并。
- **轻松配置：** 如果你会用传统chest菜单插件，你大概率会用我们的插件。
- **应用会话：** 附属插件可以获得玩家独立的屏幕平面、指针事件、可选的鼠标模式物品栏滚动、导航、调度、私有实体所有权和确定性清理。
- **多语言：** 插件与编辑器内置简体中文和英语，并允许服务端增加其他语言文件。

## 在图形界面上编辑

可选的 [ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor) 是供管理员使用的 Fabric 模组。它把工具、元素树、模板和属性面板安排在真实的 16:9 游戏视口周围，而权威菜单仍由服务器实际渲染。

<p align="center">
  <img src="assets/editor.png" alt="ArcMenu Editor 正在编辑服务端真实菜单" width="100%">
</p>

前端构图与后端交互区域分别位于不同标签页。管理员可以选择、缩放、排序、分组、移入或移出组、复制、制作模板、撤销、保存和应用；普通玩家不需要安装编辑器。

## 给第三方插件的一层应用基础

ArcMenu 位于业务插件之下，不接管它们的数据和业务规则。附属插件注册带命名空间的应用后，即可获得受管理的玩家会话：

```java
ArcMenuApi arcMenu = Bukkit.getServicesManager().load(ArcMenuApi.class);

ArcMenuApplicationHandle handle = arcMenu.registerApplication(
    this,
    "myplugin:shop",
    ArcMenuApplicationOptions.builder()
        .permission("myplugin.shop")
        .captureMouseScroll(true)
        .build(),
    context -> new ShopSession(context)
);
```

应用可以从 Java 打开，也可以使用唯一的规范 YAML 动作：

```yaml
actions:
  right:
    - 'open-app: myplugin:shop featured'
```

最小接入代码见 [Java 附属示例](examples/java-addon/src/main/java/example/ExampleArcMenuAddon.java)。

## 从源码构建

ArcMenu 是 Maven 多模块项目。默认构建面向 Paper 1.21.1 与 Java 21：

```bash
git clone https://github.com/FENTAIIII/ArcMenu.git
cd ArcMenu
mvn clean verify
```

构建后的服务端插件位于 `paper/target/ArcMenu-0.1.0-SNAPSHOT.jar`。在 Paper 上安装时必须同时安装 ProtocolLib；PlaceholderAPI、Vault、PlayerPoints 与 CraftEngine 是可选接入，其中 CraftEngine 是当前支持的自定义内容插件。

目标版本范围为 **Paper / Minecraft Java 1.21.1–26.2**。自动编译与测试矩阵覆盖 1.21.1、1.21.4、1.21.6、1.21.11、26.1.2 和 26.2；各版本的真实运行画面仍需单独验收。当前编辑器面向 **Minecraft 26.1.2 Fabric**，需要 Java 25。

## 项目结构

| 模块 | 职责 |
| --- | --- |
| `core` | YAML 模型、校验、几何、行为与动画逻辑 |
| `api` | 提供给第三方 Paper 插件的稳定类型 |
| `paper` | 运行会话、渲染、输入、资源与编辑器桥接 |
| `protocol` | 与 ArcMenu Editor 共用的无依赖通信协议 |
| `benchmarks` | 可重复执行的性能基线 |

详细设计与验收记录位于 [`docs`](docs)。源码使用 [MIT 许可证](LICENSE)，第三方授权与致谢记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

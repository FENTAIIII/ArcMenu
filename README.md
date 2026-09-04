<p align="center">
  <img src="assets/arcmenu-opening.gif" alt="ArcMenu opening animation" width="640">
</p>

<h1 align="center">ArcMenu</h1>

<p align="center">
  A server-driven in-world UI framework for Minecraft.<br>
  面向 Minecraft 的服务端驱动式游戏内 UI 框架。
</p>

<p align="center">
  <a href="#english">English</a> · <a href="#简体中文">简体中文</a> · <a href="https://github.com/FENTAIIII/ArcMenu-Editor">ArcMenu Editor</a>
</p>

<a id="english"></a>

## English

### What is ArcMenu?

ArcMenu is an advanced menu and application framework for Paper servers. It renders rich, app-like interfaces inside the Minecraft world with player-private display entities, server-authoritative coordinates, resource-pack assets, animations, tooltips, and two interaction modes.

Regular players do not need a client mod. Server administrators can use the optional Fabric-based [ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor) to edit the same real menu rendered by the server in a 16:9 workspace.

ArcMenu is built as an interface host rather than a collection of hard-coded applications. Third-party plugins can connect login, music, dungeon, social, shop, guild, sign-in, and other systems through the ArcMenu API while keeping their own business data and rules.

> **Development status:** ArcMenu is under active development. This repository currently publishes the project overview and showcase media; source code and release binaries are not included.

### Supported versions

- Target server range: **Paper / Minecraft Java 1.21.1–26.2**.
- The API build and automated-test matrix currently covers **1.21.1, 1.21.4, 1.21.6, 1.21.11, 26.1.2, and 26.2**. Runtime and visual validation is tracked separately for each version.
- The current ArcMenu Editor build targets **Minecraft 26.1.2 with Fabric** and is intended for administrators only.
- **ProtocolLib** provides the protocol layer used by the server-only camera and pointer system.
- **CraftEngine** is the supported integration target for custom item models and resource-pack composition.

### Features

- Player-private, server-rendered menus built from text, images, items, blocks, rectangles, borders, lines, groups, and fixed interaction regions.
- Touch mode and mouse mode share one logical canvas, hit-testing model, tooltip system, navigation stack, and cleanup lifecycle.
- Stable menu transitions plus node translation, rotation, scale, text-content, and text-opacity animation tracks.
- Strict YAML configuration with validation and atomic reload, so invalid drafts do not replace the last valid runtime configuration.
- Dynamic placeholders, conditions, actions, per-player state, custom tooltips, image resources, and CraftEngine item models.
- A visual administrator editor with frontend/backend separation, element management, templates, property controls, undo/redo, save, and apply workflows.
- Chinese and English built in, with server-defined language files for additional locales.

### How ArcMenu works

1. ArcMenu validates menu, animation, tooltip, resource, and input configuration before publishing it to new sessions.
2. The server creates a private UI surface and display entities for each player. Menu-to-menu and menu-to-application navigation reuse the same screen plane, so the view stays fixed.
3. Touch mode and mouse mode are normalized into the same canvas coordinates and activation events. Backend interaction regions remain axis-aligned and independent from visual grouping or animation.
4. The application API gives integrations a surface, pointer stream, scrolling, navigation, scheduling, private-entity ownership, and deterministic cleanup. Integrations retain control of accounts, messages, purchases, quests, and other domain logic.
5. The optional editor works against the server's real preview and keeps editing state separate from the runtime UI used by ordinary players.

<a id="简体中文"></a>

## 简体中文

### ArcMenu 是什么？

ArcMenu 是面向 Paper 服务端的高级菜单与应用框架。它通过仅对单个玩家可见的展示实体、服务端权威坐标、资源包素材、动画、Tooltip 和两种交互模式，在 Minecraft 世界中呈现接近应用程序的界面。

普通玩家无需安装客户端模组。服务器管理员可以使用可选的 Fabric [ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor)，在 16:9 工作区中直接编辑由服务端真实渲染的菜单。

ArcMenu 提供的是界面宿主能力，而不是一组写死的业务应用。登录、音乐、副本、社交、商店、公会、签到等第三方插件可以通过 ArcMenu API 接入，同时继续自行管理业务数据与规则。

> **开发状态：** ArcMenu 仍在持续开发。本仓库目前只发布项目介绍和展示素材，不包含源码与发行构建。

### 支持版本

- 目标服务端范围：**Paper / Minecraft Java 1.21.1–26.2**。
- 当前 API 编译与自动测试矩阵覆盖 **1.21.1、1.21.4、1.21.6、1.21.11、26.1.2 和 26.2**；各版本的服务端运行与画面表现仍分别验收。
- 当前 ArcMenu Editor 面向 **Minecraft 26.1.2 Fabric**，仅供管理员使用。
- **ProtocolLib** 提供纯服务端摄像机与指针系统所需的协议层能力。
- 自定义物品模型与资源包合并仅以 **CraftEngine** 作为内容插件接入目标。

### 功能

- 使用文字、图片、物品、方块、矩形、边框、线段、组和固定交互区域构建仅对目标玩家可见的服务端菜单。
- 触控模式与鼠标模式共用逻辑画布、命中模型、Tooltip、导航栈和清理生命周期。
- 提供稳定的菜单过渡，以及节点平移、旋转、缩放、文字内容和文字不透明度动画轨道。
- 使用严格、单一语义的 YAML 配置，支持校验与原子重载；错误草稿不会替换正在运行的有效配置。
- 支持动态占位符、条件、动作、玩家独立状态、自定义 Tooltip、图片资源和 CraftEngine 物品模型。
- 提供面向管理员的可视化编辑器，包含前后端分离、元素管理、模板、属性控制、撤销/重做、保存和应用流程。
- 内置简体中文与英语，并允许服务器通过语言文件增加其他语言。

### ArcMenu 如何工作？

1. ArcMenu 在把配置发布给新会话前，统一校验菜单、动画、Tooltip、资源与输入配置。
2. 服务端为每位玩家建立私有 UI 平面和展示实体。菜单与应用之间切换时复用同一屏幕平面，避免视角跳动。
3. 触控模式和鼠标模式统一转换为相同的画布坐标与激活事件。后端交互区域始终横竖对齐，不跟随前端分组或动画旋转。
4. 应用 API 向第三方插件提供屏幕平面、指针流、滚动、导航、受控调度、私有实体所有权和确定性清理；账号、消息、购买、任务等业务规则仍由接入插件负责。
5. 可选编辑器直接对接服务端真实预览，并让编辑状态与普通玩家使用的运行界面保持隔离。

## API integration showcase · API 接入示例

The following concept menus demonstrate what third-party plugins can build on the ArcMenu API. They are showcase integrations, not bundled login, music, dungeon, or social applications.

以下概念菜单用于展示第三方插件接入 ArcMenu API 后的实现潜力，并不表示 ArcMenu 内置登录、音乐、副本或社交业务。

<table>
  <tr>
    <td width="50%"><img src="assets/music-app.png" alt="Music application concept"></td>
    <td width="50%"><img src="assets/login-app.png" alt="Login application concept"></td>
  </tr>
  <tr>
    <td align="center"><strong>Music player · 音乐播放器</strong></td>
    <td align="center"><strong>Account login · 账号登录</strong></td>
  </tr>
  <tr>
    <td><img src="assets/dungeon-app.png" alt="Dungeon dialogue concept"></td>
    <td><img src="assets/social-app.png" alt="Social application concept"></td>
  </tr>
  <tr>
    <td align="center"><strong>Dungeon dialogue · 副本对话</strong></td>
    <td align="center"><strong>Social messaging · 社交消息</strong></td>
  </tr>
</table>

## Visual editor · 可视化编辑器

[ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor) places tools and dockable panels around a real 16:9 game viewport. Administrators edit frontend elements, fixed backend interaction regions, templates, and YAML-backed properties while viewing the server's actual menu.

[ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor) 在真实的 16:9 游戏视口周围布置工具与停靠面板。管理员可以在查看服务端实际菜单的同时编辑前端元素、固定的后端交互区域、模板以及由 YAML 保存的属性。

<p align="center">
  <img src="assets/editor.png" alt="ArcMenu Editor" width="100%">
</p>

## Language support · 语言支持

ArcMenu and ArcMenu Editor include English and Simplified Chinese. Server owners can add and select custom locale files without changing menu YAML semantics.

ArcMenu 与 ArcMenu Editor 内置英语和简体中文。服务器可以增加并选择自定义语言文件，同时保持菜单 YAML 的单一语义。

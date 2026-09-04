<p align="center">
  <img src="assets/arcmenu-opening.gif" alt="ArcMenu opening animation" width="640">
</p>

<h1 align="center">ArcMenu</h1>

<p align="center">A server-driven in-world UI framework for Minecraft.</p>

<p align="center">
  <strong>English</strong> · <a href="README_zh_CN.md">简体中文</a> · <a href="https://github.com/FENTAIIII/ArcMenu-Editor">ArcMenu Editor</a>
</p>

## What is ArcMenu?

ArcMenu is an advanced menu and application framework for Paper servers. It renders rich, app-like interfaces inside the Minecraft world with player-private display entities, server-authoritative coordinates, resource-pack assets, animations, tooltips, and two interaction modes.

Regular players do not need a client mod. Server administrators can use the optional Fabric-based [ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor) to edit the same real menu rendered by the server in a 16:9 workspace.

ArcMenu is built as an interface host rather than a collection of hard-coded applications. Third-party plugins can connect login, music, dungeon, social, shop, guild, sign-in, and other systems through the ArcMenu API while keeping their own business data and rules.

> **Development status:** ArcMenu is under active development. This repository currently publishes the project overview and showcase media; source code and release binaries are not included.

## Supported versions

- Target server range: **Paper / Minecraft Java 1.21.1–26.2**.
- The API build and automated-test matrix currently covers **1.21.1, 1.21.4, 1.21.6, 1.21.11, 26.1.2, and 26.2**. Runtime and visual validation is tracked separately for each version.
- The current ArcMenu Editor build targets **Minecraft 26.1.2 with Fabric** and is intended for administrators only.
- **ProtocolLib** provides the protocol layer used by the server-only camera and pointer system.
- **CraftEngine** is the supported integration target for custom item models and resource-pack composition.

## Features

- Player-private, server-rendered menus built from text, images, items, blocks, rectangles, borders, lines, groups, and fixed interaction regions.
- Touch mode and mouse mode share one logical canvas, hit-testing model, tooltip system, navigation stack, and cleanup lifecycle.
- Stable menu transitions plus node translation, rotation, scale, text-content, and text-opacity animation tracks.
- Strict YAML configuration with validation and atomic reload, so invalid drafts do not replace the last valid runtime configuration.
- Dynamic placeholders, conditions, actions, per-player state, custom tooltips, image resources, and CraftEngine item models.
- A visual administrator editor with frontend/backend separation, element management, templates, property controls, undo/redo, save, and apply workflows.
- English and Simplified Chinese built in, with server-defined language files for additional locales.

## How ArcMenu works

1. ArcMenu validates menu, animation, tooltip, resource, and input configuration before publishing it to new sessions.
2. The server creates a private UI surface and display entities for each player. Menu-to-menu and menu-to-application navigation reuse the same screen plane, so the view stays fixed.
3. Touch mode and mouse mode are normalized into the same canvas coordinates and activation events. Backend interaction regions remain axis-aligned and independent from visual grouping or animation.
4. The application API gives integrations a surface, pointer stream, scrolling, navigation, scheduling, private-entity ownership, and deterministic cleanup. Integrations retain control of accounts, messages, purchases, quests, and other domain logic.
5. The optional editor works against the server's real preview and keeps editing state separate from the runtime UI used by ordinary players.

## API integration showcase

The following concept menus demonstrate what third-party plugins can build on the ArcMenu API. They are showcase integrations, not bundled login, music, dungeon, or social applications.

<table>
  <tr>
    <td width="50%"><img src="assets/music-app.png" alt="Music application concept"></td>
    <td width="50%"><img src="assets/login-app.png" alt="Login application concept"></td>
  </tr>
  <tr>
    <td align="center"><strong>Music player</strong></td>
    <td align="center"><strong>Account login</strong></td>
  </tr>
  <tr>
    <td><img src="assets/dungeon-app.png" alt="Dungeon dialogue concept"></td>
    <td><img src="assets/social-app.png" alt="Social application concept"></td>
  </tr>
  <tr>
    <td align="center"><strong>Dungeon dialogue</strong></td>
    <td align="center"><strong>Social messaging</strong></td>
  </tr>
</table>

## Visual editor

[ArcMenu Editor](https://github.com/FENTAIIII/ArcMenu-Editor) places tools and dockable panels around a real 16:9 game viewport. Administrators edit frontend elements, fixed backend interaction regions, templates, and YAML-backed properties while viewing the server's actual menu.

<p align="center">
  <img src="assets/editor.png" alt="ArcMenu Editor" width="100%">
</p>

## Language support

ArcMenu and ArcMenu Editor include English and Simplified Chinese. Server owners can add and select custom locale files without changing menu YAML semantics.

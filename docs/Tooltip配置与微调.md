# Tooltip 配置与微调

`plugins/ArcMenu/tooltip.yml` 使用 `touch` 和 `mouse` 两个完整样式段，分别对应
触控模式和鼠标模式。两段可以完全独立；省略 `mouse` 时会继承整套 `touch`。
旧段名 `crosshair` / `cursor` 只保留读取兼容。

```yaml
mouse:
  offset: {x: 75, y: -20, z: 3}
  anchor: bottom-left
  wrap: false
  size: 7
  line-width: 180
  background: '#D0101010'
  skin:
    background: /ce/topaz_background.png
    frame: /ce/topaz_frame.png
    border: 8
    padding: {left: 8, right: 8, top: 8, bottom: 8}
    min-size: {width: 24, height: 24}
    size-adjust: {width: 0, height: 0}
    offset: {x: 0, y: 0, z: -0.25}
    scale: {x: 1.0, y: 1.0}
    text-offset: {x: 0, y: 0, z: 0}
    seam-overlap: {x: 0, y: 0}
    glyph-offset: {x: 0, y: 0}
    column-offset: {left: 0, center: 0, right: 0}
    row-offset: {top: 0, center: 0, bottom: 0}
```

`anchor` 可设为 `top-left`、`top-right`、`bottom-left` 或 `bottom-right`。选中的
tooltip 外框角固定在“指针位置 + 顶层 `offset`”；文字多少、皮肤宽高和换行变化
都不会改变这个基准点。x 正值向右，y 正值向上。

`wrap: false` 是默认行为：tooltip 列表中的每一项固定为一行，长行不会因为
TextDisplay 的临界字宽突然换行。只有显式设置 `wrap: true` 时，`line-width`
才作为客户端字体像素最大宽度并允许自动换行。`size` 控制文字大小，也决定一个
tooltip 字体像素对应多少菜单逻辑单位。没有 `skin` 时，`background` 是原版
TextDisplay 背景色；启用 `skin` 后它不再绘制，以免压在资源包皮肤上。

`skin.background` 和 `skin.frame` 相对于 `plugins/ArcMenu/images`。`frame` 可省略。
`border` 是原 PNG 上下左右不可拉伸部分的真实源像素宽度；修改图片路径、
`frame` 或 `border` 后需要 `/arcmenu reload all` 和 `/ce reload all`。

以下参数用于布局：

- `padding`：文字内容到皮肤四边的留白。旧的 `{x, y}` 简写仍可用；单独的
  `left/right/top/bottom` 优先。
- `min-size`：短文字下皮肤仍需保持的最小宽高。
- `size-adjust`：在自动测量结果上额外加减总宽高。负值不会获准把皮肤压到
  无法容纳左右或上下 border。
- `skin.offset`：只移动完整皮肤；不移动文字。x/y 为菜单逻辑单位，z 为菜单
  深度单位；z 默认 `-0.25`，使皮肤在文字后方。
- `skin.scale`：只缩放完整皮肤的宽、高；不改变文字。
- `text-offset`：只移动文字；不移动皮肤。单位与 `skin.offset` 相同。
- `seam-overlap`：让相邻九宫格块在 x/y 方向互相覆盖指定字体像素，用于消除
  细缝；只能为非负数。
- `glyph-offset`：把九块共同移动指定字体像素，适合修正客户端资源包字形的
  统一细小误差。
- `column-offset`：分别移动左、中、右三列，只影响 x。
- `row-offset`：分别移动上、中、下三行，只影响 y。

`padding`、`min-size`、`size-adjust`、`seam-overlap`、`glyph-offset`、
`column-offset` 和 `row-offset` 都使用 tooltip 字体像素，并随 `size` 与
`skin.scale` 一起缩放。只修改这些布局参数、顶层参数或位移/缩放时，执行普通
`/arcmenu reload` 即可，不需要重新生成资源包。

建议按以下顺序微调，避免多个参数互相抵消：

1. 先选择希望固定在指针旁的 `anchor`，并保持所有微调为零。
2. 整张皮肤偏离文字时调 `skin.offset`；文字本身需要放进特定内容区时调
   `text-offset`。
3. 四边留白不合适时调 `padding`；整体仍偏宽或偏高时调 `size-adjust`。
4. 全局比例不对时调 `skin.scale`。
5. 九宫格有细缝时从 `seam-overlap: {x: 0.1, y: 0.1}` 开始增加。
6. 三列或三行中的某一条单独错位时，最后再调 `column-offset` / `row-offset`。

每次截图时同时记录当前分辨率、GUI 缩放、菜单名、tooltip 文本和鼠标尖端坐标。
这些信息可以区分固定布局误差与 FOV/GUI 缩放造成的视觉变化。

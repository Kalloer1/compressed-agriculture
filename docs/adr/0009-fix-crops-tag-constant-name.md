# 0009 · 修正 `BlockTags.CROP` → `BlockTags.CROPS`，并澄清骨粉路径的可行性前提

- 状态：已采纳（2026-10-31）
- 影响面：§0 表格、§1.1、§5.1、§12
- 性质：**纯事实修正**，不改变设计意图

## 问题一：常量名写错，且被文档自己钉死

方案 v1.9.2 在四处使用 `BlockTags.CROP`：

- §0 表格：「普通作物 | `BlockTags.CROP` 标签 + `CropBlock` 回退」
- §1.1：「代码中使用 `BlockTags.CROP`」
- §5.1：「方块属于 `#compressedagriculture:compressible_crops`（默认 `#minecraft:crop`）」
- §12：「`BlockTags.CROP` 为单数常量名」

在 1.21.1 / NeoForge 21.1.235 中，`net.minecraft.tags.BlockTags` 的实际常量是：

```java
public static final TagKey<Block> CROPS = create("crops");   // 第 94 行
```

**是 `CROPS`，复数。** 对应的数据文件是 `data/minecraft/tags/block/crops.json`，同样复数。不存在 `crop` 标签，也不存在 `BlockTags.CROP` 常量。

§12 那句「为单数常量名」把错误当成了注意事项写下来，反而会让人在编译报错时怀疑自己记错，而不是去查常量。必须删掉。

## 问题二：`#minecraft:crops` 里含有瓜类茎

`data/minecraft/tags/block/crops.json` 的实际内容：

```
beetroots, carrots, potatoes, wheat,
melon_stem, pumpkin_stem,
torchflower_crop, pitcher_crop
```

它**包含 `melon_stem` 和 `pumpkin_stem`**。

而方案 §5.1 用「属于 `compressible_crops`（默认 `#minecraft:crops`）」来识别普通作物，§7 又单独用 `instanceof StemBlock` 处理瓜类茎。

两者冲突：茎既在普通作物标签里，又是 `StemBlock`。而 `StemBlock` **不是** `CropBlock` 的子类（它 `extends BushBlock implements BonemealableBlock`，见 `StemBlock.java:30` 与 `CropBlock.java:25`），所以走 §5.2 的「非 `CropBlock` 但属于标签 → 回退到 `AGE` 最大值判定」时，茎的 `AGE` 最大值是 7，会被判为「成熟普通作物」，与 §7 的瓜类路径抢同一个方块。

## 决策

1. 全文档 `BlockTags.CROP` → `BlockTags.CROPS`，`#minecraft:crop` → `#minecraft:crops`。
2. 删除 §12 的「`BlockTags.CROP` 为单数常量名」一条。
3. 普通作物识别在标签判定**之前**加一条排除：`instanceof StemBlock` 的方块一律不走普通作物路径，交由 §7 处理。这与 §5.1 现有的「条件 2 不收树苗、可可豆」是同一类排除，扩展它即可。
4. `#compressedagriculture:compressible_crops` 作为**数据层**默认引用 `#minecraft:crops` 不变；茎的排除放在代码层，因为那是行为判断而非数据配置。

## 理由

- 事实错误必须修，没有取舍空间。
- 第 3 条选在代码层而非标签层：整合包作者不应该为了避开茎而去手写一份减掉茎的作物列表——那正是 §0 核心原则反对的「把方块列表交给数据配置去维护」。行为类判断归代码，数据只管「我认为哪些算作物」。

## 后果

- 编译不再报 `CROPS cannot be resolved`。
- 茎的路径唯一化：`instanceof StemBlock` 命中 → 走 §7；否则再跑 §5 的标签/`CropBlock` 判定。
- 需要补一条测试：`minecraft:melon_stem` 放在压缩耕地上，成熟后必须由瓜类路径结算，**不能**由普通作物路径重复结算。

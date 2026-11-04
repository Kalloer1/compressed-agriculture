# 0010 · 压缩耕地必须加入 `#minecraft:dirt` 标签，否则瓜类路径不成立

- 状态：已采纳（2026-10-31）；**结论不变，理由已修订**（ADR-0020，2026-11-04）
- 影响面：§7.2 Mixin 注入、标签注册（§11.3）
- 性质：**可行性前提**，不满足则整条瓜类路径是死代码

> **后续修订（ADR-0020）**：本文下半部分推导出的「压缩耕地要在 `#minecraft:dirt` 里」依然成立，但**保留理由变了**。
> 种植那一半已由 `CompressedFarmlandBlock.canSustainPlant` 接管，不再依赖本标签；本标签现在只为**瓜类果实落位**而留——
> `StemBlock.randomTick` 的果实落位判断是
> `blockstate.getBlock() instanceof FarmBlock || blockstate.is(BlockTags.DIRT)`（`StemBlock.java:93` 附近），
> **它不读 `canSustainPlant`**，所以 `canSustainPlant` 再宽松也救不了它。

## 背景

方案 §7.2 的瓜类路径依赖「Mixin 注入 `StemBlock` 生成结果方块的路径」。文档假设：茎下方是压缩耕地 → 由我们接管。

## 问题

`StemBlock.randomTick` 放果实之前有一道**原版硬检查**：

```java
BlockPos $7 = $2.relative($6);
BlockState $8 = $1.getBlockState($7.below());
if ($1.getBlockState($7).isAir() && ($8.is(Blocks.FARMLAND) || $8.is(BlockTags.DIRT))) {
    // 才去查 fruit / attachedStem 并放置
}
```

（`StemBlock.java`，随机刻分支；`else` 分支只在 `AGE == 7` 时执行）

也就是说：**果实放置要求目标位置下方是 `Blocks.FARMLAND` 或属于 `#minecraft:dirt`**。压缩耕地如果两条都不满足：

- 不是 `Blocks.FARMLAND`（当然不是，它是另一个方块）
- 不在 `#minecraft:dirt` 里

那么原版这个 `if` **永远为 false**，`randomTick` 根本走不到放置果实那一步。Mixin 注入点如果在这个 `if` 内部，注入的代码永远不会执行；如果在外部，就没有「原版即将放置果实」这个时机可拦截。

§7.2 的成功分支「取消结果方块放置、取消茎到 `AttachedStemBlock` 的转换」都**以「原版准备放置」为前提**。前提不存在，整个分支是死代码。

另注：`#minecraft:dirt` 的实际内容是 `dirt` / `grass_block` / `podzol` / `coarse_dirt` / `mycelium` / `rooted_dirt` / `moss_block` / `mud` / `muddy_mangrove_roots`，**不含 farmland**——farmland 是被那个 `||` 的左边单独覆盖的。

## 决策

**压缩耕地（全部 9 个等级）注册时加入 `#minecraft:dirt` 标签。**

## 理由

- 这是让原版 `randomTick` 走到放置分支的**唯一**干净办法，不需要额外 Mixin 去改那个 `if`。
- 加入 `#dirt` 同时也让压缩耕地接受各种「需要泥土」的原版行为（树苗 / 花 / 蘑菇种植等）。
- 不给压缩耕地单独 Mixin 改这个条件：那会变成第二个注入点，且行为与标签方案完全等价，复杂度更高。

## 需要注意的副作用

`#minecraft:dirt` 是很宽的标签，加入后压缩方块会**凭空获得一堆原版行为**，需要逐条确认是否接受：

| 行为 | 后果 | 处理 |
|---|---|---|
| 草蔓延（grass_block 蔓延到泥土） | 无风险：`SpreadingSnowyDirtBlock.randomTick` 用的是硬编码 `is(Blocks.DIRT)`，不读标签 | 无需处理 |
| 树苗/花可在其上种植 | 压缩方块上能直接种花 | 可能正是想要的，需确认 |
| 锄头右键变耕地 | `HoeItem.TILLABLES` 是**硬编码方块表**，不读标签 | **不会发生**（已验证，见下） |
| 僵尸踩踏 farmland / 水冲 | 与上同理 | 需确认 |
| 蘑菇可放置 | §8 手动收获依赖「下方是压缩耕地」 | 一致，无冲突 |

## 已验证不受影响：锄头

`HoeItem.TILLABLES` 是一张硬编码的 `ImmutableMap.of(...)`，键是 `Blocks.GRASS_BLOCK` / `DIRT_PATH` / `DIRT` / `COARSE_DIRT` / `ROOTED_DIRT` 这五个**具体方块**，**不读任何标签**。因此把压缩泥土加入 `#minecraft:dirt` 不会让它被锄头变成普通耕地。

这消除了本 ADR 最大的风险。但同时也意味着：**「锄头右键压缩泥土 → 压缩耕地」不会自动发生**，必须由 §11.14 的形态转换主动实现（例如给压缩泥土注册自己的 `useOn`，或在 `UseItemOnBlockEvent` 里拦截）。这两件事是分开的，不要因为「反正加了 dirt 标签」就以为锄头能用了。

## 后果

- §11.3 标签注册任务要明确列出 `#minecraft:dirt` 这一项，文档当前只列了 4 个自定义标签，没有它。
- 「锄头右键压缩泥土 → 压缩耕地」需要显式实现，不能依赖原版 `HoeItem`。
- 建议把上表当成实现前的检查清单，逐条给出接受/拦截的结论。

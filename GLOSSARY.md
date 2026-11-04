# 术语表 · 压缩农业系统

本文件只定义**词**，不写实现。平台：Minecraft 1.21.1 / NeoForge。

## 方块

**压缩方块**（compressed block）
由 9 个下级方块合成而来的方块，带等级。分两形态：压缩泥土、压缩耕地。共 18 个 Block（2 形态 × 9 等级）。等级与形态由 Block 本身表达，不存进 BlockEntity。

**形态**（form）
压缩方块的两种互转状态之一：泥土 / 耕地。形态之间的转换不损失货仓内容。

**等级**（level，记作 L）
压缩方块的合成深度，取值 1–9。L 决定**收获倍率** `9^L`。等级与形态正交。

**货仓**（chest）
压缩方块内部的存储，27 格，每格一种物品 + `long` 计数。货仓容量由 `chestCount` 扩容决定，与等级正交。`chestCount == 0` 时界面不可打开，但收获仍写入，每格上限 64。

**货仓扩容**（chestCount）
货仓容量的扩容档位。`stackLimit = 64 × (1 + chestCount)`。上限 `MAX_CHEST_COUNT = 1_000_000_000L`。

**通用地基**（universal substrate）
压缩耕地的新角色：`canSustainPlant` 对未被禁种表排除的植物一律返回 TRUE，因此任何植物都能种在压缩耕地上并存活。代价是失去原版的部分地基约束（甘蔗不需要临水、作物不需要光照）。

**禁种表**（not_plantable_on_compressed_farmland）
`#compressedagriculture:not_plantable_on_compressed_farmland`，默认空。列入其中的植物方块在压缩耕地上会退回原版判断。用途：某个模组植物在耕地上行为崩坏时，用数据包一键关掉而不改代码。

## 收获

**结算**（settle）
一次完整的「按掉落清单取物 → 乘 9^L → 写入货仓」动作。结算有且只有一个入口，入口本身不含任何方块 ID 判断。

**倍率**（multiplier）
`9^L`。L 为承载该作物的压缩方块的等级。

**重置幼苗**（reset to seedling）
结算成功后把作物设回 `age = 0`，让同一株继续下一轮。重置只在 `SUCCESS` 时发生。

**INSUFFICIENT_SPACE**
货仓放不下本次结算的任一物品。语义为**原子失败**：一个物品都写不进去，且按 §5.6 重置幼苗。

**INVALID_INPUT**
掉落清单为空。语义为**不作处理**：不写入、不重置，避免破坏空战利品表植株。

**统一入口**（unified entry）
`settle(...)`。所有模块（普通作物 / 树木 / 瓜类 / 蘑菇 / 截顶 / 紫颂循环 / 手动兜底）唯一允许的写入路径。

**多格植物**（multi-block plant）
靠自身随机刻在头顶逐格累加**同种**方块的植物，属于 `#compressedagriculture:multi_block_plants`。默认成员：竹子、仙人掌、甘蔗。紫颂不在此列（它长高时第 1、2 格是两种不同方块），树苗与蘑菇也不在（它们长出的是原木 / 菌柄，由树木 / 蘑菇模块接管）。

**截顶**（top-cut）
多格植物长到第 2 格时，拆掉第 2 格、产物乘 `9^L` 写进货仓的动作。只拆第 2 格，第 1 格保留继续下一轮。判定条件是「第 2 格与第 1 格同方块」+「第 1 格属于多格植物标签」+「第 2 格下方 2 格是压缩耕地」。

**停摆**（stalled）
货仓放不下截顶产物时，第 2 格原样保留、机器暂停，直到玩家腾出货仓空间。停摆时不拆方块、不写货仓、不重复刷日志。

## 识别

**可压缩作物**（compressible crop）
属于 `#compressedagriculture:compressible_crops`，或是 `CropBlock` 子类且 `isMaxAge(state) == true`。二者取或，不是取且。

**可压缩树苗**（compressible sapling）
属于 `#compressedagriculture:compressible_saplings`（默认引用 `#minecraft:saplings`）。代码中用 `BlockTags.SAPLINGS`（复数）。

**瓜类茎**（stem）
`instanceof StemBlock` 的方块。其果实由 `StemBlock.fruit` 字段给出，不硬编码。

**树木产物映射**（tree_loot）
**已废弃**（ADR-0011）。原先的 `data/compressedagriculture/tree_loot/<sapling>.json` 手工映射被「真实生成树再拆」取代，不再存在。

**真实生成**（real generation）
让原版自己生成一棵完整的树 / 蘑菇树。产物不来自手工表。

**连通性枚举**（connectivity enumeration）从树苗位置向上找到树干，BFS 扩散把所有方块分成三类：`logs`（`BlockTags.LOGS` / `SLIMY_LOGS` / `MUSHROOM_STEM`）、`leaves`（任何有 `distance` 整数属性的方块；蘑菇块按固定半径红菇 2 / 褐菇 3）、`attachments`。思路取自 Create 的 `TreeCutter`。

**`validateCut`**
防止把树的中段当成整棵拆掉的检查：看被砍位置上方是否还有不依附本层的原木。

**枚举上限**
一次连通性枚举最多 4096 格。超出按 `INSUFFICIENT_SPACE` 语义处理并 WARN。

**通用回退**（generic fallback）
**已废弃**（ADR-0011）。原「树苗自身 1 个 + 木棍 0–2」，因真实生成已能给出正确产物而不再需要。

**蘑菇树**（huge mushroom）
骨粉把蘑菇催成的世界生成结构。由 `mushroom_stem` 树干与 `red_mushroom_block` / `brown_mushroom_block` 顶盖组成。红菇顶盖 5×5（`foliage_radius` 2），褐菇 7×7（`foliage_radius` 3）；树高 4–6，1/12 概率翻倍至 8–12。它是蘑菇模块的主要收益来源。

**回填**（refill）
树 / 蘑菇树被拆掉后，在原位重新放置下一轮的种子。单格树放一棵树苗，2×2 巨树放四格，蘑菇树放一个同种蘑菇。树苗的 `STAGE` 必须置 0，否则会变成无限加速循环。

**蘑菇模块的两条路径**
路径一 = 骨粉催蘑菇树（主要收益）；路径二 = 原版随机扩散（仅保留原版约束，作为路径一的种子来源）。二者都不加速。

## 触发

**随机刻路径**
`CropGrowEvent.Post`。**仅**在随机刻生长成功时触发，骨粉催熟不会触发它。

**骨粉路径**
玩家对作物使用骨粉。`BonemealEvent` 是 `ICancellableEvent`，且在 `performBonemeal` **之前**触发。NeoForge 21.1.235 **不存在** `BonemealEvent.Post`。

**手动兜底**（manual fallback）
玩家空手右键结果方块 / 破坏蘑菇时，由本模组接管的写入路径。

## 约定

**核心原则**
能种什么、能收什么，由标签和方块行为决定，不由代码里的方块列表决定。代码中不出现任何具体作物 / 树苗 / 果实 / 茎的方块 ID。

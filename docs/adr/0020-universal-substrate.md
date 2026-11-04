# 0020 · 压缩耕地是通用地基：`canSustainPlant` 对一切植物返回 TRUE

- 状态：已采纳（2026-11-04）
- 影响面：`CompressedFarmlandBlock`（新增一个 override）、`CropBlockMixin` / `StemBlockMixin`（删除 `mayPlaceOn` 注入）、`PitcherCropBlockMixin`（整文件删除）、新增方块标签
- 关系：**取代** ADR-0018 里「用 `mayPlaceOn` 注入让压缩耕地能种作物」那一条，也取代 ADR-0016 里 `StemBlock.mayPlaceOn` 那一条；ADR-0010（`#minecraft:dirt`）**继续有效**，但保留理由变了
- 编号：ticket 12 写的是 ADR-0019，但 0019 已被「破坏时货仓随物品走」占用，所以落到 0020

## 背景

spec 用户故事 11 / 16：「我想把原版作物种在压缩耕地上」「我想让模组作物也能种在压缩耕地上」。

ADR-0018 的做法是**逐点扩展**：原版哪一处用身份比较认耕地，就在哪一处打一个 mixin。这条路能覆盖小麦/胡萝卜/马铃薯/甜菜/瓶子草/瓜类，但对「下界疣、蘑菇、甘蔗、仙人掌、竹子、花、树苗、紫颂花」全部无效——它们各自 `mayPlaceOn` 里写死了自己的地基（灵魂沙 / 沙子 / `isSolidRender` / `#bamboo_plantable_on` / 末地石），逐个打 mixin 就是一张永远补不完的表，而且每加一个模组植物就得再补一次。

NeoForge 早就为这件事留了口子：`IBlockExtension.canSustainPlant`。

## 关键源码事实（NeoForge 21.1.235 实际运行源码，`javap` + Vineflower 核对）

`net.neoforged.neoforge.common.extensions.IBlockExtension`（`IBlockExtension.java:393`）：

```java
default TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing, BlockState plant) {
    return TriState.DEFAULT;
}
```

`Block` 已经 `implements IBlockExtension`（`javap` 确认：`public class Block extends BlockBehaviour implements ItemLike, IBlockExtension`），所以**直接 override 即可，不需要任何 mixin**。

原版各植物的 `canSurvive` 第一步都会问脚下这块地，返回非 `DEFAULT` 就采纳、后面整段硬编码检查跳过：

| 植物 | 位置 | 被绕过的原版检查 |
|---|---|---|
| `CropBlock`（含 `TorchflowerCropBlock` 等子类） | `CropBlock.canSurvive` 第一行 | `hasSufficientLight`（光照 ≥ 8）**和** `mayPlaceOn`（`instanceof FarmBlock`） |
| `BushBlock` 全体（含 `NetherWartBlock`、`StemBlock`、`MushroomBlock`、`SaplingBlock`、`FlowerBlock`、`DoublePlantBlock`） | `BushBlock.canSurvive` 第一行 | 各自的 `mayPlaceOn`：灵魂沙 / `#dirt`+`FarmBlock` / `isSolidRender` |
| `PitcherCropBlock` | 经 `DoublePlantBlock` → `BushBlock.canSurvive` | 自己那份 `mayPlaceOn`（`is(Blocks.FARMLAND)`） |
| `SugarCaneBlock` | `SugarCaneBlock.canSurvive` 第一行（`blockstate.is(this)` 之后） | 下方必须是 `#dirt`/`#sand` + **水平相邻有水** |
| `CactusBlock` | `CactusBlock.canSurvive` 的**侧面检查之后** | 下方必须是沙子/仙人掌 |
| `BambooStalkBlock` | `canSurvive` 与 `getStateForPlacement` | `#bamboo_plantable_on` |
| `ChorusFlowerBlock` | `canSurvive` 第一行；`randomTick` 的 `flag` 判定也问它 | 下方必须是末地石 |

`MushroomBlock` 的实际形状最绕，值得单记一笔：

```java
return blockstate.is(BlockTags.MUSHROOM_GROW_BLOCK)
    ? true
    : (soilDecision.isDefault() ? level.getRawBrightness(pos, 0) < 13 && this.mayPlaceOn(blockstate, level, blockpos)
                                : soilDecision.isTrue());
```

即 `#mushroom_grow_block` 检查**排在** `canSustainPlant` 之前（不受影响），而光照 < 13 与 `isSolidRender` 两道都在之后（都被绕过）。压缩耕地的形状是 15/16，`getCollisionShape` 跟着 `getShape` 走，所以 `isSolidRender` 恒为 false——**这才是蘑菇原本种不上的真正原因**，与光照无关。

## 决策

1. `CompressedFarmlandBlock` override `canSustainPlant`：
   - `plant` 属于 `#compressedagriculture:not_plantable_on_compressed_farmland` → 返回 `TriState.DEFAULT`（交回原版判断）
   - 其余一律返回 `TriState.TRUE`
2. 新增方块标签 `#compressedagriculture:not_plantable_on_compressed_farmland`，默认空，作为**禁种表**逃生口。
3. 删除三处因本改动变成死代码的 `mayPlaceOn` 注入：`CropBlockMixin`、`StemBlockMixin`、`PitcherCropBlockMixin`（整个文件）。
4. **保留** `CropBlockMixin` 里 `getGrowthSpeed` 的两个 `@Redirect`（见下）。
5. **保留** `#minecraft:dirt` 标签（见下）。

## 两个已接受的副作用

- **甘蔗不需要临水**：原版 `SugarCaneBlock.canSurvive` 的「下方 `#dirt`/`#sand` + 水平相邻有水」整段被跳过。
- **作物不因光照不足弹掉**：原版 `CropBlock.canSurvive` 要求光照 ≥ 8，现在整段被跳过。
  **但「不弹掉」不等于「能长大」**：`CropBlock.randomTick` 里还有一道独立的 `level.getRawBrightness(pos, 0) >= 9`，它不在 `canSurvive` 里，本决策碰不到。所以黑暗地窖里的作物会稳定停在幼苗，不会消失、也不会生长。这条差别不在 ticket 12 的文字里，实现时实测确认。

## 保留原版约束的一处：仙人掌侧面检查

`CactusBlock.canSurvive` 的四邻检查（四邻不能 `isSolid()`、不能有岩浆）**排在** `canSustainPlant` 之前，所以旁边贴着实心方块时仙人掌照样弹掉。这是唯一一个「通用地基碰不到」的原版地基约束，已有 GameTest 覆盖。

## 为什么 `#minecraft:dirt` 仍然必须保留

瓜类果实的落位判断在 `StemBlock.randomTick` 里：

```java
if (level.isEmptyBlock(blockpos) && (blockstate.getBlock() instanceof FarmBlock || blockstate.is(BlockTags.DIRT))) {
```

**它不读 `canSustainPlant`**，`canSustainPlant` 再宽松也救不了它。ADR-0010 的结论继续成立，但保留理由从「瓜类**种植**」变成「瓜类**果实落位**」——种植那半已经由本 ADR 接管。

## 为什么保留 `getGrowthSpeed` 的两个 `@Redirect`

`CropBlock.getGrowthSpeed` 里「脚下这块地算不算耕地」走的是：

```java
TriState soilDecision = blockstate.canSustainPlant(level, pos, Direction.UP, blockState);
if (soilDecision.isDefault() ? blockstate.getBlock() instanceof FarmBlock : soilDecision.isTrue()) {
    f1 = 1.0F;
    if (blockstate.isFertile(level, pos)) { f1 = 3.0F; }
}
```

- `canSustainPlant` 那个 `@Redirect` 在本 ADR 之后**已经是冗余的**（`CompressedFarmlandBlock` 的 override 会给出同样的 `TRUE`）。保留它不改变结果，删掉也不会出错；留着是因为它不影响正确性，而删它属于「顺手重构」，不在本票范围。
- `isFertile` 那个 `@Redirect` **必须保留**：`BlockState.isFertile` 内部仍是 `is(Blocks.FARMLAND)`，压缩耕地永远为 false，水合 → 3.0 那段会失效，作物在浇过水的压缩耕地上会按 1.0 慢速长。

## 备选方案

1. **继续逐点打 mixin**（ADR-0018 的路）：每加一个植物补一处。放弃——覆盖不了模组植物，而且清单永远在长。
2. **让 `CompressedFarmlandBlock` 继承 `FarmBlock`**：`FarmBlock` 的 `randomTick` / `tick` / `fallOn` 会 `turnToDirt` 把方块换成原版泥土，货仓直接丢（ADR-0002）。放弃。
3. **把压缩耕地塞进更多原版标签**（`#bamboo_plantable_on`、`#mushroom_grow_block`…）：只解决读标签的那部分，身份比较和「临水」这类非标签检查仍然无解，而且要污染一批语义完全不同的标签。放弃。

## 后果

- `CompressedFarmlandBlock.canSustainPlant` 是「通用地基」的**唯一实现点**。想调整行为只改这一个方法或那张标签，不要去别处打补丁。
- 新加的模组植物默认就能种在压缩耕地上，不需要本模组做任何事；崩坏时用禁种表退回原版判断。
- 三处 `mayPlaceOn` 注入已删，`compressedagriculture.mixins.json` 里不再有 `PitcherCropBlockMixin`。
- `#minecraft:dirt` 的保留理由变了（见上），ADR-0010 不重写，只补一条指引。
- 多格植物（甘蔗 / 仙人掌 / 竹子）现在能长高，但「只保留 1 格高度」是 ticket 13 的事；紫颂花的特殊处理是 ticket 14 的事。

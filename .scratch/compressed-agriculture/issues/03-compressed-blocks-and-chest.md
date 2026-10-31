# 03: 27 个压缩方块与货仓

**What to build:** 玩家能用 9 个泥土合成 1 个 1 级压缩泥土，放下去是一个正常方块（有正常的挖掘速度和工具需求），挖开能拿到，活塞推得动。它内部有一个 27 格货仓，每格存一种物品加一个 `long` 计数，能跨区块卸载保留。这一步结束时，货仓还没有任何东西往里写——写入口是 04 之后的事，所以验收靠命令或调试手段直接往货仓塞。

**Blocked by:** 02（统一入口 `settle` 与 `ChestTarget`）

**Status:** ready-for-agent

## 验收标准

### 方块与 BE

- [ ] 27 个 Block 全部注册：3 形态（压缩泥土 / 压缩草地 / 压缩耕地）× 9 等级
- [ ] 等级与形态由 Block 本身表达，**不存进 BlockEntity**
- [ ] 每个压缩方块都有对应的 `CompressedBlockEntity`
- [ ] 空手右键时方块不掉落异常、不崩溃
- [ ] 破坏时按原版掉落自身（货仓数据随物品搬运在 `03b` 落地，本票不要求）
- [ ] 挖掘速度与工具需求等同对应原版方块（泥土 / 草地 / 耕地）
- [ ] 类结构（本票必须按此落地，09 依赖它做形态转换）：
  ```java
  public abstract class CompressedBlock extends Block {   // 携带 level
      public int level();                                  // 1..9
      public abstract CompressedForm form();               // DIRT / GRASS / FARMLAND
  }
  public class CompressedDirtBlock     extends CompressedBlock {}
  public class CompressedGrassBlock    extends CompressedBlock {}
  public class CompressedFarmlandBlock extends CompressedBlock {}  // 额外带 MOISTURE
  ```
- [ ] `CompressedFarmlandBlock` 必须携带 `BlockStateProperties.MOISTURE`（**与 `FarmBlock.MOISTURE` 同一个属性实例**），默认 0（ADR-0018：`CropBlock.getGrowthSpeed` 的注入会读它，没有就 `IllegalStateException`）。水合逻辑留给 09
- [ ] **不要继承 `FarmBlock`**：`FarmBlock` 的 `randomTick` / `tick` / `fallOn` 会调 `turnToDirt` 把方块换成原版泥土，货仓直接丢。踩踏/水合由 09 自己实现
- [ ] 一个 `BlockEntityType<CompressedBlockEntity>` 注册一次，validBlocks 包含全部 27 个方块
- [ ] 1.21.1 的序列化签名是 `saveAdditional(CompoundTag, HolderLookup.Provider)` / `loadAdditional(CompoundTag, HolderLookup.Provider)`——**不是** plan §10 写的 `ValueOutput`/`ValueInput`（那是 1.21.2+）

### 货仓

- [ ] 27 格，每格一种物品 + `long` 计数
- [ ] `stackLimit = 64 × (1 + chestCount)`，全程 `long`
- [ ] `MAX_CHEST_COUNT` 与 `stackLimit(long)` 从 ticket 02 的 `com.kalloer.compressedagriculture.settle.ChestCapacity` 引用，**BE 内不重新声明常量、不重写公式**（否则两处算术会分叉）
- [ ] `chestCount == 0` 时每格上限 64
- [ ] 序列化字段：`schemaVersion = 1`、`chestCount`（`long`）、`slotItems[27]`（`ResourceLocation`）、`counts[27]`（`long`）
- [ ] **无 `cropId` 字段**
- [ ] `counts[i] == 0` 时 `slotItems[i] == null`
- [ ] 加载时 `chestCount` clamp 到 `[0, MAX_CHEST_COUNT]` 并 `setChanged()`
- [ ] 区块卸载再加载，货仓内容不丢
- [ ] `CompressedBlockEntity` 实现 `ChestTarget`，使 `settle` 能直接写入真货仓
- [ ] `tryReserve` 的预留状态**只在单次 `settle` 调用内有效**，不跨调用存活；未 commit 的预留不得影响后续结算

### 等级读取出口契约（05 / 07 依赖）

等级编码在 Block 里、不在 BE 里，但 05 和 07 都需要「从某个位置的方块读出 `L`」。本票必须提供这个出口，否则下游只能各自猜方法名。

- [ ] **契约已定死，不要改名**：`public static OptionalInt CompressedBlock.levelOf(BlockState state)`
  - 压缩方块 → `OptionalInt.of(level)`；非压缩方块 → `OptionalInt.empty()`；`state == null` → `empty()`，不抛异常
  - 只读 `BlockState`，不读 BE、不读世界
- [ ] 同时提供实例方法 `public int level()`，供已经拿到方块实例的代码直接用

### 标签

- [ ] `#compressedagriculture:compressible_crops` 默认引用 `#minecraft:crops`
- [ ] `#compressedagriculture:compressible_saplings` 默认引用 `#minecraft:saplings`
- [ ] `#compressedagriculture:mushrooms` 默认含红蘑菇、褐蘑菇
- [ ] `#compressedagriculture:melon_like_stems` 与 `#compressedagriculture:pumpkin_like_stems` 存在且默认为空
- [ ] **全部 27 个压缩方块（3 形态 × 9 等级）加入 `#minecraft:dirt`**（ADR-0010，瓜类路径的可行性前提；瓜类要求茎下方的**压缩耕地**在标签内，所以三形态都要进）
- [ ] 注意：`#dirt` **只解决果实放置**（`StemBlock.java:93`），**不解决种植**（`StemBlock.java:77` 的 `mayPlaceOn` 只认 `Blocks.FARMLAND`，不读标签）。种植由 ticket 07 的 Mixin 负责，本票不得因 ADR-0016 而删除 `#dirt` 条目

### ADR-0010 五条 `#dirt` 副作用逐条给结论

加入 `#minecraft:dirt` 会让压缩方块凭空获得一堆原版行为。每一条必须在票里写下**接受**或**拦截**，以及理由：

**结论已核对源码并写死，实现时只需在票里保留这些结论，不要改判：**

- [x] **草蔓延：不会发生，接受。** `SpreadingSnowyDirtBlock.randomTick` 的蔓延目标是 `getBlockState(pos).is(Blocks.DIRT)`——身份比较，压缩泥土不在其中。普通草地不会覆盖压缩泥土。压缩方块之间的草蔓延由 09 自己实现
- [x] **树苗 / 花可在压缩方块上种植：接受，而且正是想要的。** `BushBlock.mayPlaceOn` 读 `BlockTags.DIRT`，压缩方块进 `#dirt` 后树苗/花/蘑菇可直接种在上面（ticket 05 依赖这一点）
- [x] **僵尸踩踏 / 水冲：不会发生，接受。** 这两个行为属于 `FarmBlock` 的方法；本票的压缩耕地**不继承 `FarmBlock`**，因此不会被换成原版泥土。09 若要等价行为，在自己类里实现
- [x] **蘑菇可放置：与 `#dirt` 无关，无冲突。** `MushroomBlock.mayPlaceOn` 走 `isSolidRender`，`canSurvive` 只看 `MUSHROOM_GROW_BLOCK` 或「光照 < 13 且 `isSolidRender`」
- [x] **锄头右键：不会发生，接受。** `HoeItem.TILLABLES` 是硬编码方块表、不读标签；「锄头 → 压缩耕地」需 09 显式实现

### 玩家可见

- [ ] 物品有正确的名称与 `default` 语言键，游戏里不显示 `block.compressedagriculture.xxx` 这类原始键名
- [ ] 极简可见性：3 个 block model（泥土 / 草地 / 耕地各一，贴图直接复用原版 `minecraft:block/dirt` / `grass_block` / `farmland`）+ 27 个 blockstate JSON，避免方块渲染成紫黑格。**不做自定义贴图、不做等级外观区分**

## 备注

- 形态之间的互转不在这张票里，留给 09。本票只要求三个形态各自能放下、能挖开
- 合成配方已拆到 `03b-crafting-recipes.md`（9 泥土 → L1、2–9 级的自定义 9 合 1），本票不做配方
- 扩容配方（`chestCount + 1`）留给 08，因为它是 GUI 的验收面
- `#dirt` 副作用清单是**玩法验收**，不写自动化测试（spec 的 Seam 3 已说明），但必须留下书面结论

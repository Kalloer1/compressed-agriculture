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
- [ ] 挖掘速度与工具需求等同对应原版方块（泥土 / 草地 / 耕地）

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

- [ ] 提供一个可被 05 / 07 调用的等级读取方法（静态工具或方块接口，二选一），签名把 `BlockState` 映射到 `L ∈ [1, 9]`
- [ ] 对非压缩方块返回明确的「无等级」结果（例如 `OptionalInt.empty()` 或 0），不得抛异常
- [ ] 该方法不读 BE、不读世界，只依赖 `BlockState`
- [ ] 在票内写下最终选定的方法名与签名，供 05 / 07 直接引用

### 标签

- [ ] `#compressedagriculture:compressible_crops` 默认引用 `#minecraft:crops`
- [ ] `#compressedagriculture:compressible_saplings` 默认引用 `#minecraft:saplings`
- [ ] `#compressedagriculture:mushrooms` 默认含红蘑菇、褐蘑菇
- [ ] `#compressedagriculture:melon_like_stems` 与 `#compressedagriculture:pumpkin_like_stems` 存在且默认为空
- [ ] **9 个等级的压缩方块全部加入 `#minecraft:dirt`**（ADR-0010，瓜类路径的可行性前提）
- [ ] 注意：`#dirt` **只解决果实放置**（`StemBlock.java:93`），**不解决种植**（`StemBlock.java:77` 的 `mayPlaceOn` 只认 `Blocks.FARMLAND`，不读标签）。种植由 ticket 07 的 Mixin 负责，本票不得因 ADR-0016 而删除 `#dirt` 条目

### ADR-0010 五条 `#dirt` 副作用逐条给结论

加入 `#minecraft:dirt` 会让压缩方块凭空获得一堆原版行为。每一条必须在票里写下**接受**或**拦截**，以及理由：

- [ ] 草蔓延：普通草方块是否会蔓延到压缩泥土上？压缩泥土会不会被普通草地覆盖？
- [ ] 树苗 / 花能否直接在压缩方块上种植？这是想要的吗？
- [ ] 僵尸踩踏 / 水冲：是否发生，是否接受？
- [ ] 蘑菇可放置：**已验证与 `#dirt` 无关**。`MushroomBlock.mayPlaceOn` 是 `isSolidRender`，`canSurvive` 只看 `MUSHROOM_GROW_BLOCK` 或「光照 < 13 且 `isSolidRender`」。结论：加 `#dirt` 不影响蘑菇放置，无冲突
- [ ] 锄头右键：**已验证不会发生**（`HoeItem.TILLABLES` 是硬编码方块表、不读标签），只需确认现状并记录「锄头 → 压缩耕地」需 09 显式实现

### 合成

- [ ] 9 泥土 → 1 级压缩泥土（普通 JSON 配方）
- [ ] 2–9 级的 9 合 1 配方：`chestCount` 用 `Math.addExact` 累加，超 `MAX_CHEST_COUNT` 时**配方不匹配**，内容物清空

### 玩家可见

- [ ] 物品有正确的名称与 `default` 语言键，游戏里不显示 `block.compressedagriculture.xxx` 这类原始键名

## 备注

- 形态之间的互转不在这张票里，留给 09。本票只要求三个形态各自能放下、能挖开
- 扩容配方（`chestCount + 1`）留给 08，因为它是 GUI 的验收面
- `#dirt` 副作用清单是**玩法验收**，不写自动化测试（spec 的 Seam 3 已说明），但必须留下书面结论

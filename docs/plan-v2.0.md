# 压缩农业系统 · 最终方案 v2.0

平台：Minecraft 1.21.1 / NeoForge
核心原则：能种什么、能收什么，由标签和方块行为决定，不由代码里的方块列表决定。

> v2.0 由 v1.9.2 修订而来。全部改动见 `docs/plan-v2-changes.md`，每条对应 `docs/adr/0001`–`0015`。

---

## 0. 总体原则

一切“哪些方块”走标签或行为类，掉落清单走动态获取，代码中不出现任何具体作物/树苗/果实的方块 ID。

| 模块 | 识别方式 | 掉落来源 |
|---|---|---|
| 普通作物 | `BlockTags.CROPS` 标签 + `CropBlock` 回退 | 方块自身战利品表 |
| 树苗 | `BlockTags.SAPLINGS` 标签 | 真实生成树 → 连通性枚举 → 逐方块 `getDrops` |
| 蘑菇 | `#compressedagriculture:mushrooms` | 骨粉催蘑菇树 → 同树引擎 |
| 瓜类茎 | `StemBlock` 类型检查 + 可选标签 | `StemBlock.fruit` 字段（Accessor Mixin） |

---

## 1. 标签体系

### 1.1 `#compressedagriculture:compressible_crops`

默认引用 `#minecraft:crops`。代码中使用 `BlockTags.CROPS`（**复数**）。普通作物识别的第一优先级。

### 1.2 `#compressedagriculture:compressible_saplings`

默认引用 `#minecraft:saplings`。代码中使用 `BlockTags.SAPLINGS`，不出现任何树苗 ID。

### 1.3 `#compressedagriculture:mushrooms`

默认值：`minecraft:red_mushroom`、`minecraft:brown_mushroom`。模组蘑菇可自行加入。

### 1.4 可选茎标签

`#compressedagriculture:melon_like_stems` / `pumpkin_like_stems`，默认为空。仅作为非 `StemBlock` 茎的显式兼容入口，代码中不包含任何茎 ID。

### 1.5 原版标签加入

**9 个等级的压缩方块全部加入 `#minecraft:dirt`。**

必须如此，否则两条路径不成立：

- 瓜类：`StemBlock.randomTick` 放果实前有硬检查 `below.is(Blocks.FARMLAND) || below.is(BlockTags.DIRT)`。
- 蘑菇：`BushBlock.mayPlaceOn` 是 `is(BlockTags.DIRT) || is(Blocks.FARMLAND)`。

已验证副作用：`HoeItem.TILLABLES` 是硬编码方块表、不读标签，所以加入 `#dirt` **不会**让锄头把压缩泥土变成普通耕地。但反过来也意味着「锄头右键压缩泥土 → 压缩耕地」**不会自动发生**，必须由 §14 形态转换主动实现。

其余待确认副作用见 ADR-0010（草蔓延、花可种、僵尸踩踏、水冲）。

> 原 §1.5「树木产物映射」**整节删除**。`tree_loot` 手工映射已废弃，理由见 §6.3 与 ADR-0011。

---

## 2. 方块与等级

18 个方块：2 形态（压缩泥土 / 压缩耕地）× 9 等级。BE 不持久化 `level`/`form`，从 Block 读取。

**形态转换保留货仓内容。** 两种形态之间转换时，27 格数据（含 `chestCount`）原样带到新方块。理由：泥土 → 耕地是种地必经步骤，清空等于每次丢一次货；而「按形态过滤」需要一张物品表，正面撞 §0 核心原则（ADR-0002）。

---

## 3. 合成与扩容

- 9 泥土 → 1 级压缩泥土：普通 JSON。
- 2–9 级：自定义 9 合 1，`chestCount` 使用 `Math.addExact` 累加，结果必须 ≤ `MAX_CHEST_COUNT`，超限配方不匹配；内容物清空。
- 箱子扩容：`chestCount + 1`，仅当 `chestCount < MAX_CHEST_COUNT` 时可合成。
- `MAX_CHEST_COUNT = 1_000_000_000L`。加载时 clamp 到 `[0, MAX]` 并 `setChanged()`。

**等级与扩容是两个正交轴**（ADR-0003）：

- 等级 `L` 控制**进来多少**：每次结算 `× 9^L`。
- `chestCount` 控制**装得下多少**：每格上限 `64 × (1 + chestCount)`。

升等级不会自动扩容，扩容不改变倍率。绑死会炸：L=9 时 `9^9 ≈ 3.87 亿`，任何 `chestCount` 都装不下。高等级配低容量是**合法配置**，会触发 `INSUFFICIENT_SPACE`，这是设计意图不是 bug。

---

## 4. 容器

27 格，每格一种物品，`long` 计数。`stackLimit = 64 × (1 + chestCount)`。单槽策略：同物品优先同槽，否则空槽；多物品原子分配。`chestCount == 0` 时界面不可开，但收获仍写入，每格上限 64。无 `cropId` 字段；`counts[i] == 0` 时 `slotItems[i] = null`。

⚠ `stackLimit` 计算中的 `chestCount` 必须是 `long`：`64 × (1 + chestCount)` 在 `chestCount` 接近 10 亿时会**溢出 `int`**。

---

## 5. 普通作物收获

### 5.1 识别

一个方块被视为可压缩普通作物，当且仅当满足以下任一：

1. 方块属于 `#compressedagriculture:compressible_crops`（默认 `#minecraft:crops`）；
2. 方块是 `CropBlock`（或其子类），且当前 `isMaxAge(state)` 返回 true。

条件 2 **不收**“任意 `BonemealableBlock` + `AGE_*`”。树苗、可可豆等虽实现 `BonemealableBlock` 且有 `AGE`，但由各自模块或原版路径处理，不进入普通作物结算。

**条件 3（排除）：方块 `instanceof StemBlock` 时一律不走普通作物路径**，交由 §7 处理。

> 必须排除：`#minecraft:crops` 实际包含 `melon_stem`、`pumpkin_stem`，而 `StemBlock extends BushBlock`、**不是** `CropBlock`。若不排除，茎会掉进 §5.2 的 AGE 回退分支（AGE 最大 7，判为成熟），与 §7 抢同一方块。见 ADR-0009。

### 5.2 成熟判定

若方块是 `CropBlock`：

```java
boolean mature = ((CropBlock) block).isMaxAge(state);
```

若方块不是 `CropBlock` 但属于 `compressible_crops` 标签，回退到 `AGE` 属性最大值判定：

```java
IntegerProperty ageProp = ...;
int maxAge = Collections.max(ageProp.getPossibleValues());
boolean mature = state.getValue(ageProp) == maxAge;
```

### 5.3 掉落获取

```java
LootParams.Builder builder = new LootParams.Builder(serverLevel)
    .withParameter(LootContextParams.ORIGIN, pos)
    .withParameter(LootContextParams.TOOL, tool)
    .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity);
List<ItemStack> drops = state.getDrops(builder);
```

`tool` 取自触发来源（玩家主手 / 空手 `ItemStack.EMPTY`）；`BLOCK_ENTITY` 在方块实体存在时作为可选参数传入，保证依赖 BE 的模组作物掉落正确。掉落清单可能包含随机结果，尚未乘倍率。

### 5.4 触发与去重

成熟结算有**两条互不重叠的触发路径**，二者都汇入统一入口。

#### 路径 A：随机刻成熟 → `CropGrowEvent.Post`

监听 `CropGrowEvent.Post`。该事件仅在随机刻生长成功时触发。检测 `event.getNewState()` 是否成熟；成熟 → 调用统一入口。

#### 路径 B：骨粉催熟 → tick 末尾检测

**关键事实（已验证，见 ADR-0001）**：`BonemealEvent` 在 NeoForge 21.1.235 中

- **没有** `Post` 变体；
- 唯一触发点在 `BoneMealItem.applyBonemeal` 开头、`performBonemeal` **之前**；
- `setSuccessful(boolean)` 内部会 `setCanceled(true)`。

所以 v1.9.2 里「在事件返回之后检查」这个时机**不存在**。在该监听器里读 `getBlockState(pos)` 得到的是催熟**前**的状态，`isMatureCrop(after)` 恒为 false。

正确做法：

```java
@SubscribeEvent
public void onBonemeal(BonemealEvent event) {
    // 客户端也会触发，必须挡掉
    if (!(event.getLevel() instanceof ServerLevel level)) return;
    // 不取消、不调 setSuccessful，让原版完整执行 performBonemeal
    pendingThisTick.add(level.dimension(), event.getPos());
}

@SubscribeEvent
public void onServerTick(ServerTickEvent.Post event) {
    for (ServerLevel level : event.getServer().getAllLevels()) {
        for (BlockPos pos : pendingThisTick.get(level.dimension())) {
            BlockState state = level.getBlockState(pos);
            if (isMatureCrop(state) && !alreadySettled(level, pos)) {
                settleCrop(state, pos, level, null);
                resetToSeedling(level, pos, state);
            }
            // 未成熟 → 什么都不做（原「路径 C」）
            // 已不是作物 → 什么都不做
        }
    }
    pendingThisTick.clear();   // 每 tick 无条件清空
}
```

`ServerTickEvent.Post` 只携带 `MinecraftServer`（`getServer()`），**不携带 Level**，须用 `getAllLevels()` 遍历。

**去重**：`pendingThisTick` 按 `(ResourceKey<Level>, BlockPos.asLong())` 存储，同一 tick 内同位置多次骨粉只结算一次。集合每 tick 无条件清空，不跨 tick 累积，因此**不会因区块卸载而泄漏**。不要用 `ServerLevel` 引用做键。

### 5.5 重置幼苗

结算成功后：

- `CropBlock` → 设置回 `age = 0`（通过 `getAgeProperty()` 构造 `state.setValue(ageProp, 0)`）；
- 非 `CropBlock` 但属于 `compressible_crops` 标签 → 若方块有 `AGE` 属性，设置为 0；否则调用 `performBonemeal` 或保持原状并记录调试日志。

### 5.6 失败语义

| 失败 | 行为 |
|---|---|
| `INSUFFICIENT_SPACE` | **不写入、不生成 `ItemEntity`、保留成熟态**（不重置幼苗） |
| `INVALID_INPUT`（掉落清单为空） | 不写入、不重置；记录 **WARN** |

**为什么 `INSUFFICIENT_SPACE` 不重置幼苗**（ADR-0005）：货仓满 → 作物长熟 → 结算失败 → 苗被设回 age=0，玩家看到刚长熟的地又变回青苗，这一轮 9^L 份产出凭空消失。而 ADR-0003 已确立「高等级配低容量是合法配置」，用作物去惩罚玩家的扩容决策是错误的归因。瓜类（§7.2）本来就是「失败保留 `AGE=7`」，普通作物与之对齐。

保留成熟态**不会** tick 风暴：`CropBlock.isRandomlyTicking` 返回 `!isMaxAge(state)`，成熟作物根本不进入随机刻。重试由外部驱动：

1. 玩家关闭该 BE 的 GUI（`PlayerContainerEvent.Close`），对该 BE 上方 3×3 尝试一次；
2. 玩家右键该压缩方块；
3. 玩家破坏该压缩方块前（`playerWillDestroy`）。

⚠ 本模组 GUI 的 menu 必须继承 `AbstractContainerMenu`，否则收不到 `PlayerContainerEvent.Close`，第 1 条重试路径丢失。

**`INVALID_INPUT` 用 WARN**（ADR-0007）：空掉落清单几乎总是整合包配错，且后果是永久卡住（作物保持成熟、永远结算失败），必须可诊断。记录内容含方块 ID、维度、坐标、当前 `BlockState`。按 `(维度, 坐标)` 限流，同一位置每 600 tick 最多一次。

---

## 6. 树木收获

### 6.1 识别

完全依赖 `#compressedagriculture:compressible_saplings`（默认 `#minecraft:saplings`）。代码中使用 `BlockTags.SAPLINGS`，不出现任何具体树苗 ID。

### 6.2 两阶段生长与 2×2 判定

**两阶段生长**（ADR-0014）：压缩树苗与原版完全一致地走 `STAGE 0 → 1 → growTree`，不跳过、不加速。

原版 `SaplingBlock.advanceTree`：

```java
if (state.getValue(STAGE) == 0) {
    level.setBlock(pos, state.cycle(STAGE), 4);   // 0 → 1，什么都不长
} else {
    this.treeGrower.growTree(...);
}
```

**2×2 搜索**：原版 `TreeGrower.growTree` 的遍历是

```java
for (int x = 0; x >= -1; x--)
    for (int z = 0; z >= -1; z--)
        if (isTwoByTwoSapling(state, level, pos, x, z)) { ... }
```

即**从事件坐标自身开始，按 `(0,0) → (0,-1) → (-1,0) → (-1,-1)` 偏移顺序搜索**，四格中任意一格都可以当锚点，只尝试第一个找到的。压缩农业不改变这一顺序。

`isTwoByTwoSapling` 只比较 `Block` 相同（`.is(block)`），**不比较 ID 列表，也不比较 `STAGE`**。

**基底探测**：检查西北角树苗下方那一格。若为压缩方块且 BE 存在 → 压缩路径；若为普通泥土/草地 → 放行原版。**不检查另外三格**，因为原版生长判定不依赖它们。

**容器选择**（ADR-0004）：从西北角 BE 开始，按 **西北 → 东北 → 西南 → 东南** 选择第一个 BE。与生长判定的锚点一致，避免「判定用西北格、存进东南格」的错位。容器选择顺序与生长判定分离，不要求所有压缩方块等级一致。

### 6.3 产物获取：真实生成 + 连通性枚举

**不再有 `tree_loot`。** 采用 Create `TreeCutter` 的思路（ADR-0011）：

1. 允许原版正常生成树（**不 DENY**）；
2. 生长完成后，从树苗原位置向上找到树干，做**连通性遍历**，把方块分成三类：
   - `logs`：`state.is(BlockTags.LOGS) || SLIMY_LOGS || state.is(Blocks.MUSHROOM_STEM)`
   - `leaves`：任何有 `distance` 整数属性且非 `STABILITY_DISTANCE` 的方块；蘑菇块 / 菌岩按固定半径（红菇 2、褐菇 3）
   - `attachments`：`AllBlockTags.TREE_ATTACHMENTS`
3. BFS 时用 `visited` 集合防重复，原木向 UP 扩散，有根（`ROOTS` 标签）时向 DOWN 扩散，树叶按 `distance` 递增方向扩散；
4. 用 `validateCut` 检查被砍位置上方是否还有不依附本层的原木，防止把树的中段当成整棵；
5. 对每个枚举出的位置调 `Block.getDrops(state, serverLevel, pos, blockEntity, player, tool)`；
6. 汇总送入统一入口，`× 9^L`；
7. 把所有枚举位置设为空气，并回填树苗（§6.5）。

不需要在生长过程挂钩。树的方块本来就能从树结构反推，生长与收割解耦。

**数量上限**：无硬上限，与 Create 的 `TreeCutter` 对齐。枚举走 `visited` 集合，每个位置只进一次，遍历天然有界（受区块加载边界限制）。不引入人为截断——截断会让巨型丛林树静默少给产物，而那是最难排查的一类问题。

若实测发现异常结构导致卡顿，处理方向是限定枚举的区块范围，而不是砍掉方块数。

### 6.4 反馈

木质破坏音效 + 短时木屑/树叶粒子，位置在写入容器上方或 2×2 中心。无实际砍伐动作，不涉及工具耐久。

### 6.5 回填树苗（ADR-0014）

树被拆完后：

- **单格树**：原树苗位置放回一棵同种树苗，`STAGE = 0`。
- **2×2 巨树**：**四格全部放回同种树苗，`STAGE = 0`**。

只还一格的话下次长不出巨树，玩家每次都得手动补三格，违背「种一次就不用管」。四格下方等级可以不同（产物只进西北 BE），但回填**不看等级**，四格都放。

⚠ `STAGE` 必须置 0。若保持 1，下一轮树苗立刻满足 `growTree` 条件，变成「拆了就马上再长」的无限加速循环。

⚠ 三个保护：

- 回填必须在拆树**之后**，否则回填的树苗会被拆树逻辑误删；
- 原位已被其他方块占据时**不回填、不覆盖**；
- 回填的树苗会立即被识别为下一轮目标——这是预期行为（它就是种子），但要确保回填动作本身不触发结算。

---

## 7. 瓜类收获

### 7.1 茎识别

```java
blockState.getBlock() instanceof StemBlock
```

行为类检查，不是方块 ID 列表。可选标签 `#compressedagriculture:melon_like_stems` / `pumpkin_like_stems` 仅用于非 `StemBlock` 子类的显式兼容，默认为空。

### 7.2 Mixin 注入

在 `StemBlock` 生成结果方块的路径中注入，`randomTick` 和 `performBonemeal` 两条路径都要覆盖。

判断条件：

- 茎下方是压缩耕地，且 BE 存在；
- `AGE == 7`；
- 目标位置为空或可替换。

成功：

- 调用统一入口结算本次瓜类产物；
- 取消结果方块放置；
- 取消茎到 `AttachedStemBlock` 的转换；
- 保留 `AGE=7`；
- 播放反馈。

失败（`INSUFFICIENT_SPACE`）：

- 取消结果放置；
- 取消转换；
- 保留 `AGE=7`；
- 不消耗茎、不生成结果；
- 可选设置 20–40 tick 内部冷却。

> ⚠ **前提**：`StemBlock.randomTick` 有硬检查 `below.is(Blocks.FARMLAND) || below.is(BlockTags.DIRT)`。压缩耕地若不满足，这个分支**永远不成立**，注入点根本不存在。所以 §1.5 的 `#minecraft:dirt` 加入是这条路径的**可行性前提**，不是优化。

### 7.3 果实解析

通过 Accessor Mixin 读取 `StemBlock` 的 `private final ResourceKey<Block> fruit` 字段：

```java
@Mixin(StemBlock.class)
public interface StemBlockAccessor {
    @Accessor("fruit")
    ResourceKey<Block> compressedagriculture$getFruit();
}
```

统一入口通过该 accessor 获取果实 `ResourceKey<Block>`，再以其 `ItemStack` 或果实战利品表作为产物。西瓜存西瓜方块，南瓜存南瓜方块，模组瓜类存其果实方块，语义统一，不出现任何果实 ID。

### 7.4 骨粉与容器满

骨粉不单独结算，只让原版推进。容器满时按 7.2 的失败语义处理：取消放置、保留茎、可选冷却。

### 7.5 手动右键兜底

玩家空手右键结果方块：

1. 从结果方块四邻域按**北 → 东 → 南 → 西**顺序找匹配的茎（`StemBlock` 或标签茎），且茎下方为压缩耕地；
2. 取**第一个匹配的茎立即使用**，不再继续找；
3. 通过 Accessor 获取该茎的果实，作为写入清单；
4. 原子空间检查；
5. 足够 → 写入 → 移除结果方块，不移除茎；
6. 不足 → 不移除，不写入。

**取第一个而非「最近的」**（ADR-0008）：四邻域下两个候选的坐标距离完全相同，无法区分；固定遍历序、取首个，结果可预测且可复现。

⚠ 若第一个匹配的茎下方不是压缩耕地，**不跳过、不继续找**——直接判定该结果方块不走压缩路径，放行原版。顺序必须固定，不能变成「找到能用的为止」的可变规则。

---

## 8. 蘑菇

识别使用 `#compressedagriculture:mushrooms`。

### 8.1 路径一（主要收益）：骨粉催蘑菇树

1. 玩家对压缩耕地上的蘑菇使用骨粉；
2. **不做任何拦截**，让原版 `performBonemeal` 正常执行 `growMushroom`；
3. 蘑菇树生成完成后，按 §6.3 **同一套**连通性枚举逻辑（`mushroom_stem` 走 `isLog` 的 `MUSHROOM_STEM` 分支，蘑菇块走 `nonDecayingLeafDistance`）结算；
4. `× 9^L` 写入容器；
5. 拆除整棵蘑菇树；
6. 在原蘑菇位置回填一个同种蘑菇（ADR-0015）。

**蘑菇树的规模**（已验证）：红菇顶盖 5×5（`foliage_radius` 2），褐菇 7×7（半径 3）；树高 `nextInt(3) + 4` = 4–6，1/12 概率翻倍到 8–12。一棵蘑菇树 20–60+ 方块。

**产物语义**（已验证）：蘑菇方块**默认不掉自身**，普通破坏掉 `red_mushroom` / `brown_mushroom` × 0–2，只有精准采集才掉方块。所以拆一棵蘑菇树 ≈ 几十个蘑菇物品 × `9^L`。这是蘑菇模块的主要价值所在。

**回填**（ADR-0015）：`growMushroom` 里 `level.removeBlock(pos, false)` 把原蘑菇消耗掉，成功时不恢复。所以拆完树要在**原蘑菇位置**回填一个同种蘑菇（用被催熟的那个 `BlockState.getBlock()`，不查 ID）。原位被占据时不回填、不覆盖。

### 8.2 路径二（种子来源）：随机扩散，仅保留原版约束

扩散必须至少保留原版两条硬性条件：

1. 光照等级 < 13（除非目标位置下方是菌丝、灰化土或菌岩）；
2. 以原蘑菇为中心的 9×9×3 范围内，同种蘑菇数量少于 5 朵。

不满足任一条件 → 不写入、不额外扩散。

**不加速、不限流。** 扩散速度是原版的平衡设计；加速会让蘑菇变成无限种子机，与「用面积换产量」的定位冲突。扩散出的新蘑菇可以在压缩耕地上再次骨粉催熟，所以它是路径一的种子来源，不是独立收益。

### 8.3 手动收获

破坏蘑菇时若下方是压缩耕地，写入容器；否则放行原版。

### 8.4 可种植性（已验证）

`MushroomBlock extends BushBlock`，其 `canSurvive` 走 `BushBlock.mayPlaceOn`：

```java
protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
    return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND);
}
```

靠 §1.5 的 `#minecraft:dirt` 天然满足，蘑菇可直接种在压缩耕地上。

但另一条：`canSurvive` 的完整写法是

```java
return below.is(BlockTags.MUSHROOM_GROW_BLOCK)
    ? true
    : level.getRawBrightness(pos, 0) < 13 && this.mayPlaceOn(below, level, belowPos);
```

`MUSHROOM_GROW_BLOCK` 只含 `mycelium` / `podzol` / `crimson_nylium` / `warped_nylium`，**压缩方块不在内**。因此压缩耕地上的蘑菇**仍受光照 < 13 约束**，不享受菌丝特权。不把压缩方块加进去——它是耕地不是菌土。

---

## 9. 统一写入入口

```text
输入：掉落清单 List<ItemStack>（已含随机结果，尚未乘倍率）、等级 L
1. 每种物品 × 9^L（Math.multiplyExact，捕获溢出 → INSUFFICIENT_SPACE）
2. 原子分配槽位，检查 stackLimit 剩余空间
3. 任一不足 → INSUFFICIENT_SPACE，不写入
4. 全部足够 → 写入 → setChanged() → SUCCESS
5. 调用方仅在 SUCCESS 时执行成功状态处理
```

**`L` 的来源**（ADR-0006）：

> `L` = 该次结算所对应**作物的正下方那个压缩方块的等级**。
> 若正下方不是压缩方块，则该作物不走本模组路径。
> 2×2 巨树与瓜类茎，`L` 取自 §6.2 / §7.2 选中的那个 BE 所在方块的等级（容器选择与等级来源是同一格）。

`settle(...)` 显式接收 `L`，入口内部**不反查坐标**——保持纯函数：不读世界、不查 Block、只做「乘倍率 + 写货仓 + 原子性判断」，与核心原则一致。

掉落清单来源：

- 普通作物：`state.getDrops(...)`，`LootParams.Builder` 包含 `ORIGIN`、`TOOL`、可选 `BLOCK_ENTITY`；
- 树木 / 蘑菇树：真实结构 + 连通性枚举 → 逐方块 `Block.getDrops`；
- 瓜类：Accessor 获取的 `fruit` 方块。

统一入口本身不包含任何方块 ID 判断。

**溢出保护**：`L=9` 时 `9^9 = 387,420,489`。一次结算若掉落 N 个物品，`N × 9^9` 可能逼近甚至超过 `MAX_CHEST_COUNT`（`1e9`）。必须 `Math.multiplyExact` 并在捕获溢出时按 `INSUFFICIENT_SPACE` 处理，**不得静默截断**。

---

## 10. 数据结构

```java
class CompressedBlockEntity extends BlockEntity {
    static final long MAX_CHEST_COUNT = 1_000_000_000L;
    int schemaVersion = 1;
    long chestCount;
    ResourceLocation[] slotItems = new ResourceLocation[27];
    long[] counts = new long[27];
    // saveAdditional(ValueOutput) / loadAdditional(ValueInput)
}
```

无 `cropId`。活塞迁移使用 `clearData` / `setData`，与 BE 持久化分开。

**活塞迁移与形态转换必须共用同一套 BE 搬运代码**（ADR-0002），否则两条路径的复制逻辑会分叉。

---

## 11. 任务拆分

1. 注册 18 方块 + BE + `DataComponents`。
2. 原版识别 Mixin：作物 → 压缩耕地；蘑菇 → 压缩耕地；树苗 → 压缩泥土。
3. 注册标签：`compressible_crops`（默认 `#minecraft:crops`）、`compressible_saplings`（默认 `#minecraft:saplings`）、`mushrooms`、可选茎标签，**以及把 9 个等级的压缩方块加入 `#minecraft:dirt`**。
4. 存储、加载校验、破坏 NBT 保留。
5. 合成 + 扩容 + 9 合 1。
6. GUI + `CustomPacketPayload`。menu 必须继承 `AbstractContainerMenu`（§5.6 重试依赖它）。
7. 统一写入入口：接受动态掉落清单 + 显式 `L`；`INVALID_INPUT` 记 WARN 并限流。
8. 普通作物模块：`CropGrowEvent.Post` 随机刻路径 + `BonemealEvent` → tick 末尾观察；`pendingThisTick` 每 tick 清空；`CropBlock.isMaxAge` 优先；排除 `StemBlock`。
9. 树木模块：9a 标签识别 + `Block` 同种比较；9b 基底探测只检查西北角 + 容器选择西北→东北→西南→东南；9c **真实生成 + 连通性枚举（logs/leaves/attachments）+ `validateCut` + 回填树苗**（无方块数上限）。
10. 瓜类模块：10a 反编译确认 `StemBlock` 两条路径；10b Mixin 成功/失败分支；10c Accessor Mixin 读取 `fruit`。
11. 手动右键兜底（取第一个匹配茎）。
12. 蘑菇模块：标签 + 扩散约束（光照 < 13、9×9×3 < 5 朵）+ **骨粉催蘑菇树接管** + 回填。
13. 活塞 BE 迁移（与 14 共用搬运代码）。
14. 草蔓延/水合/形态转换。
15. JEI/REI/EMI。
16. 性能与安全验证：聚焦**树的生成与枚举成本**和 **`9^L` 溢出**；**不设**作物结算次数上限（一个压缩方块只承载一个作物，与普通耕地等价）。

---

## 12. 风险与边界

- `BlockTags.CROPS` 是**复数**。`#minecraft:crops` 含 `melon_stem` / `pumpkin_stem`，必须在代码层用 `instanceof StemBlock` 排除，否则与 §7 抢方块。
- **压缩方块必须加入 `#minecraft:dirt`**，否则瓜类路径（`StemBlock.randomTick` 的 `FARMLAND || DIRT` 检查）与蘑菇可种植性（`BushBlock.mayPlaceOn`）都不成立。
- **`BonemealEvent` 没有 Post 变体，且早于 `performBonemeal`**；骨粉路径必须在 `ServerTickEvent.Post` 观察实际状态，不能在同一监听器里做前后对比。
- **2×2 巨树的原版搜索是 `(0,0)→(0,-1)→(-1,0)→(-1,-1)`**，四格中任意一格都可作锚点；基底检查只涉及西北角。
- `getDrops` 的 `LootParams.Builder` 应包含 `ORIGIN`、`TOOL` 和可选 `BLOCK_ENTITY`；`tool` 为 `ItemStack.EMPTY` 时时运不生效是预期行为。
- 瓜类失败不消耗茎：`INSUFFICIENT_SPACE` 时取消放置与转换，保留 `AGE=7`，可选冷却避免 tick 风暴。
- `StemBlock.fruit` 是 private，通过 `@Accessor` Mixin 读取，不硬编码果实 ID。
- 蘑菇扩散必须检查光照 < 13 和 9×9×3 内同种 < 5 朵；压缩方块不在 `MUSHROOM_GROW_BLOCK` 内，因此仍受光照约束。
- **真实生成树 / 蘑菇树后必须彻底拆除包围盒**，否则残块会掉落成独立方块。
- **树木枚举后必须回填树苗（单格一棵 / 2×2 四格），`STAGE = 0`**；不重置 `STAGE` 会导致无限加速循环。
- **`L=9` 时 `9^9 = 387,420,489`**，`N × 9^9` 可能逼近 `MAX_CHEST_COUNT`（`1e9`），必须 `Math.multiplyExact` 并捕获溢出。
- 树木无实际砍伐：无工具耐久，反馈为音效 + 粒子。
- 非 `StemBlock` 的模组瓜类茎默认不识别；作者可通过可选标签显式兼容。

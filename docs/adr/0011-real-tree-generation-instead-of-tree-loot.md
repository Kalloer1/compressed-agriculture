# 0011 · 树木产物改为「真实生成一棵树再拆」，废弃 `tree_loot` 数据映射

- 状态：已采纳，2026-10-31 修订（原版「记录生长过程」方案被推翻）
- 影响面：§1.5、§6.2、§6.3、§11.9c
- 取代：方案 v1.9.2 中 `tree_loot` JSON 映射 +「通用回退：树苗自身 1 个 + 木棍 0–2 个」

## 背景

方案 v1.9.2 的树木模块用数据映射 `data/compressedagriculture/tree_loot/<sapling>.json` 决定「这个树苗长成树后给什么」，缺失时回退「树苗自身 + 木棍 0–2」。

## 问题

那张表是**二十多种树 × 每种的方块构成**（原木数、树叶数、苹果概率、树干高度分布、巨型丛林树的枝干）。它必然与原版世界生成漂移，而且漂移无声。更根本的是：值不值得维护一张手工表，去模拟游戏本来就能自己算出的结果？

## 参考实现：Create 的 `TreeCutter`

Create（机械动力）的机械锯能一锯砍掉整棵树并把掉落送进容器。它的做法**不是**记录生长过程，而是**从被砍的那一格出发，做连通性遍历**。

源码：`E:/mods/other_mod/Create/src/main/java/com/simibubi/create/content/kinetics/saw/TreeCutter.java`

### 核心结构

`TreeCutter.findTree(reader, pos, brokenState)` 返回一个 `Tree`，含三个位置列表：

```java
public static class Tree extends AbstractBlockBreakQueue {
    private final List<BlockPos> logs;          // 原木 + 树根
    private final List<BlockPos> leaves;        // 树叶 / 蘑菇块
    private final List<BlockPos> attachments;   // 挂在树上的东西

    @Override
    public void destroyBlocks(Level world, ItemStack toDamage, @Nullable Player playerEntity,
                              BiConsumer<BlockPos, ItemStack> drop) {
        attachments.forEach(makeCallbackFor(world, 1 / 32f, toDamage, playerEntity, drop));
        logs.forEach(makeCallbackFor(world, 1 / 2f, toDamage, playerEntity, drop));
        leaves.forEach(makeCallbackFor(world, 1 / 8f, toDamage, playerEntity, drop));
    }
}
```

三个列表各自带**粒子效果概率**（`effectChance`），只影响视觉。

### 识别方式（全部是标签 / 行为类，无 ID 列表）

```java
public static boolean isLog(BlockState state) {
    return state.is(BlockTags.LOGS)
        || AllTags.AllBlockTags.SLIMY_LOGS.matches(state)
        || state.is(Blocks.MUSHROOM_STEM);       // ← 蘑菇树干
}

public static boolean isRoot(BlockState state) {
    return AllBlockTags.ROOTS.matches(state);
}

private static boolean isLeaf(BlockState state) {
    for (Property<?> property : state.getValues().keySet())
        if (property instanceof IntegerProperty && property.getName().equals("distance")
            && property != BlockStateProperties.STABILITY_DISTANCE)
            return true;
    return false;   // ← 按「有没有 distance 属性」判断，不是按 ID
}
```

`isLeaf` 的写法值得直接抄：**任何有 `distance` 整数属性的方块就是树叶**。这同时覆盖模组树叶，且不写任何 ID。

`nonDecayingLeafDistance` 给蘑菇块和菌岩类一个固定半径（红菇 2、褐菇 3），因为它们的 `distance` 语义不同。

### 遍历算法

**第一步：找出所有原木**

```java
visited.add(pos);
BlockPos.betweenClosedStream(pos.offset(-1, 0, -1), pos.offset(1, 1, 1))
    .forEach(p -> frontier.add(new BlockPos(p)));
```

从被砍格周围 3×3×2 开始，BFS 扩散，只走 `isLog` / `isRoot`，每次向 **UP** 方向扩邻居（`SearchDirection.UP` 即 `(0, 1)` 的 Y 范围）。

**第二步：有树根则向下扩**

```java
if (hasRoots) { ... forNeighbours(currentPos, visited, SearchDirection.DOWN, ...); }
```

**第三步：从所有原木出发扩散找树叶**

```java
int horizontalDistance = Math.max(Math.abs(subtract.getX()), Math.abs(subtract.getZ()));
if (horizontalDistance <= nonDecayingLeafDistance(state) && visited.add(currentPosImmutable)) {
    leaves.add(currentPosImmutable);  // 蘑菇块等按固定半径收集
    ...
}
if (isLeaf(state) && getLeafDistance(state) > prevLeafDistance && ...) {
    leaves.add(currentPosImmutable);  // 普通树叶：distance 递增方向
    ...
}
```

树叶的收集判据是 **`distance` 属性沿离树心方向递增**，不是按距离半径硬算。这让它自动适配原版树叶的衰减规则。

**第四步：`validateCut` 防误伤**

```java
private static boolean validateCut(BlockGetter reader, BlockPos pos) {
    // 检查被砍那一层以上，是否还有「不依附本层」的原木
    // 若有 → 返回 false，说明砍的是树中间，不是整棵
}
```

这条很重要：**防止砍到一棵大树中间时把上半截当成一整棵树拆掉**。

### 掉落怎么算

`BlockHelper.destroyBlockAs`（`foundation/utility/BlockHelper.java:213`）：

```java
BlockState state = level.getBlockState(pos);
BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
// ...
if (level instanceof ServerLevel serverLevel && ...) {
    List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, player, usedTool);
    // 走 NeoForge 的 BlockDropsEvent
}
```

**逐个方块调用 `Block.getDrops`，产出 `List<ItemStack>`，通过 `BiConsumer<BlockPos, ItemStack>` 回调交给调用方。**

## 决策（修订版）

**废弃 `tree_loot`。改用 Create 的连通性遍历思路：**

1. 树苗正常长成树（原版 `growTree`，见 ADR-0014）；
2. 从树苗原位置向上找到树干，**用连通性遍历枚举 `logs` / `leaves` / `attachments` 三类位置**；
3. 对每个位置调 `Block.getDrops(...)` 取真实掉落；
4. 汇总送入统一入口，`× 9^L`；
5. 把枚举出的所有位置设为空气。

**不再需要「记录生长过程」。** 这是本 ADR 的关键修订——树的方块本来就能从树结构反推出来，不需要在生长期间挂钩。生长结束后随时可以重新枚举。

## 理由

- **零维护**：原版改树形状、模组加新树，遍历自动适配。`tree_loot` 永远做不到。
- **遵守 §0 核心原则**：`isLog` 用 `BlockTags.LOGS`，`isLeaf` 用「有没有 `distance` 属性」，`isRoot` 用标签。零 ID。
- **不需要在生长过程挂钩**，实现难度和风险都比原方案低一个数量级。生长与收割解耦：可以先长完，玩家来收时才枚举。
- 顺带覆盖了竹子、甘蔗、仙人掌（`isVerticalPlant`）、紫颂植物（`isChorus`）、带根的树（`hasRoots`）。

## 直接抄的部分

| Create | 我们的用法 |
|---|---|
| `isLog` 用 `BlockTags.LOGS` + `MUSHROOM_STEM` | 直接抄。`MUSHROOM_STEM` 让蘑菇树走同一条路径 |
| `isLeaf` 用 `distance` 属性探测 | 直接抄 |
| `nonDecayingLeafDistance` 红菇 2 / 褐菇 3 | **正好对应 ADR-0013 的蘑菇树**，可直接用 |
| `validateCut` 防砍中段 | 直接抄 |
| `BFS` + `visited` 集合 | 直接抄 |
| `Block.getDrops` 逐方块算 | 与我们的 `settle` 一致 |

## 不能直接抄的部分

| Create 的做法 | 我们为什么不同 |
|---|---|
| 拆的时候对工具扣耐久（`usedTool.mineBlock`） | 压缩农业无工具 |
| 掉落通过 `BiConsumer` 回调直接进容器 | 我们要先乘 `9^L`、再原子检查容量 |
| 带 `BlockDropsEvent` 与 `BreakEvent` | 我们不是玩家破坏，不应触发玩家破坏事件（否则经验、统计、成就都会误加） |
| 树叶按 `distance` 递增收，但**不拆**树干中段 | 我们的树是完整生成的，可以从根部枚举 |

## 后果

- §1.5 整节删除；§6.3 整节重写；§11.9c 删除；§12 最后一条删除。
- §6.2「成功 → DENY 原版巨树生成」改为「允许生长 → 枚举 → 结算 → 拆除 → 回填树苗（ADR-0014）」。
- **方块数量上限**：无。与 Create 对齐。枚举走 `visited` 集合，每个位置只进一次，遍历天然有界（受区块加载边界限制）。不引入人为截断——截断会让巨型丛林树静默少给产物，那是最难排查的一类问题。若实测卡顿，方向是限定枚举的区块范围，不是砍方块数。
- 枚举顺序：Create 先 `attachments` 再 `logs` 再 `leaves`。我们的枚举顺序无所谓（不是逐个破坏），但**统计顺序需固定**以保证 `settle` 结果可复现。
- 蘑菇树的「蘑菇块」通过 `nonDecayingLeafDistance` 被识别为 leaves，`mushroom_stem` 通过 `isLog` 的 `MUSHROOM_STEM` 分支识别为 logs。**一套代码同时处理普通树和蘑菇树**，ADR-0013 的「共用引擎」得到落实。

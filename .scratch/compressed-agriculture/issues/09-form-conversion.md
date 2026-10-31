# 09: 形态互转、水合与草蔓延

**What to build:** 玩家能用工具把压缩泥土变成压缩草地、再变成压缩耕地（以及反向），货仓里的东西一样不少。压缩耕地会随水源/降雨改变 `MOISTURE`，压缩草地会向相邻压缩泥土蔓延。活塞迁移拆到 `09b`，破坏掉落已由 `03b` 完成（ADR-0019）。

形态互转与 `09b` 必须共用同一套「搬运 27 格数据」的代码（ADR-0002）。

**Blocked by:** 03（方块与货仓）、03b（搬运工具 `CompressedBlockEntity.saveData` / `loadData`）

**Status:** done

## 验收标准

### 形态互转（ADR-0002）

- [x] 压缩泥土 ↔ 压缩草地 ↔ 压缩耕地 三种形态互转
- [x] 互转时货仓**全部 27 格数据原样带到新方块**，包括 `chestCount`
- [x] 互转不动等级 `L`
- [x] 锄头右键压缩泥土 → 压缩耕地：**必须显式实现**，不能依赖原版 `HoeItem`（`TILLABLES` 是硬编码方块表、不读标签，已验证）
- [x] 其余两种转换的触发方式在实现时确定并写进票内（见下方「实现记录」：铲子右键、草蔓延）

### 水合与草蔓延（ADR-0018）

- [x] 压缩耕地 `randomTick`：4 格内（含上下各 1 层）有水源或上方降雨 → `MOISTURE = 7`；否则 `MOISTURE` 递减，降到 0 后不再变化
- [x] 水合生效后，ticket 04 的 `getGrowthSpeed` 注入会自然给到 3.0 倍率（`isFertile` 的 Redirect 已按 `MOISTURE > 0` 判断）
- [x] 压缩草地 `randomTick`：向相邻（含斜角、上方一格）的**压缩泥土**蔓延成同等级压缩草地；条件照抄原版（光照、上方为空气或雪层、不被水覆盖）
- [x] 草蔓延只发生在**同等级**的压缩方块之间（`level()` 相等），不跨等级
- [x] **蔓延是形态转换的一种，被覆盖那一格的货仓必须原样保留**（ADR-0002：形态转换不损失货仓内容）。压缩泥土上如果攒了 3.87 亿份小麦，草蔓延不能把它清掉
- [x] 普通草方块**不会**蔓延到压缩泥土（原版 `is(Blocks.DIRT)` 身份比较，已核实），本票不需要拦截

### 踩踏（ADR-0018）

- [x] 实体落在压缩耕地上且满足原版踩踏条件 → 变成**同等级压缩泥土**，货仓内容原样保留（**不得**替换成原版泥土）

### 不做的事

- [x] 不按目标形态过滤货仓内容。如果需要「压缩草地上不该有作物产物」这类限制，用配方或交互解决，**不清空货仓**（ADR-0002）
- [x] 不做活塞迁移（`09b`）、不做破坏掉落（`03b` 已做，ADR-0019）

## 备注

- 搬运代码的接口应该长这样：给定源 BE 和目标 BE（或目标方块），把 27 格 + `chestCount` 搬过去，然后两端都 `setChanged()`
- 形态互转的三种触发方式（本票定死，写进这里）：
  1. **锄头右键压缩泥土 → 同等级压缩耕地**（消耗 1 点耐久、播 `SoundEvents.HOE_TILL`、粒子）。`HoeItem.TILLABLES` 硬编码，必须显式实现
  2. **铲子右键压缩草地 / 压缩耕地 → 同等级压缩泥土**（消耗 1 点耐久、播 `SoundEvents.SHOVEL_FLATTEN`）
  3. **压缩草地 randomTick → 相邻压缩泥土**（草蔓延）
- 转换必须保留 `chestCount` 与 27 格内容；实现方式：读源 BE 的 `saveData` → 放新方块 → `loadData` 到新 BE。若中途失败（新方块放不下），不得丢数据
- 转换后两端都要 `setChanged()`
- 水合/蔓延/踩踏都只在本模组方块之间发生，不改原版方块

## Comments

2026-10-31 · 预检补充（ADR-0018）

- 草蔓延：原版 `SpreadingSnowyDirtBlock.randomTick` 只认 `is(Blocks.DIRT)`（身份比较），**不会**蔓延到压缩泥土；压缩方块之间的草蔓延由本票在 `randomTick` 里自己实现
- 水合：`CompressedFarmlandBlock` 已带 `BlockStateProperties.MOISTURE`（默认 0），本票负责按 4 格内水源/降雨把 MOISTURE 提到 7，并把 `getGrowthSpeed` 的 3.0 倍率真正用起来
- 僵尸踩踏 / 水流：压缩耕地不继承 `FarmBlock`，默认不会发生；若要等价行为，本票自己实现，且**不得**把方块替换成原版泥土（会丢货仓）
- 锄头：`HoeItem.TILLABLES` 硬编码，压缩泥土 → 压缩耕地必须由本票显式挂右键处理

2026-10-31 · 03 完工后补记

- **活塞会丢货仓**：`CompressedBlock` 的 `PushReaction` 是默认 `NORMAL`，推得动；但原版 `PistonMovingBlockEntity` 只搬方块状态不搬 BE 的 NBT，推一次货仓清空。本票必须在两条里选一条并写进票：① 搬数据（`clearData`/`setData` 或 `getUpdateTag` 路线）；② 改成 `PushReaction.BLOCK` 明确禁止推动。**不允许保持现状**
- 形态转换与活塞迁移必须共用同一套 BE 搬运代码（ADR-0002）

2026-10-31 · 拆分说明

- 原 ticket 09 拆成 **09（本票）= 形态互转 + 水合 + 草蔓延 + 踩踏** 与 **09b = 活塞迁移**
- 「破坏掉落」不再由 09 实现：03b 已按 ADR-0019 做成「货仓随方块物品走」，散落 `ItemEntity` 在 640 亿的数量级下不可行

2026-10-31 · 完工（worker 实现，主会话复核：`test build` 绿、`runGameTestServer` 24/24 全绿）

- **搬运工具（本票写死的名字）**：`storage/CompressedChestTransfer.convert(ServerLevel level, BlockPos pos, BlockState newState) -> boolean`。
  顺序固定：`CompressedBlockEntity.saveData` 读旧数据 → `level.setBlock(..., Block.UPDATE_ALL)` → 新 BE `loadData` → `setChanged()`。
  `newState` 不是 `CompressedBlock` 时抛 `IllegalArgumentException`（调用方编程错误）；`setBlock` 返回 false 时什么都不改，数据仍留在原方块。
  **09b 活塞迁移直接复用这一个方法**（ADR-0002）。
- **三种转换的触发方式（定死）**：
  1. `block/FormConversionModule.onRightClickBlock`（`PlayerInteractEvent.RightClickBlock`，只处理服务端）：手持 `HoeItem` 右键 `form()==DIRT` → 同等级 `FARMLAND`；手持 `ShovelItem` 右键 `form()!=DIRT` → 同等级 `DIRT`。消耗 1 点耐久、播 `HOE_TILL` / `SHOVEL_FLATTEN`、发方块碎屑粒子、`gameEvent(BLOCK_CHANGE)`，取消事件并回 `SUCCESS`。
  2. 压缩草地 `randomTick` → 相邻同等级压缩泥土（草蔓延）。压缩泥土 → 压缩草地**没有**工具路径，只有蔓延。
- **等级到方块的映射**：新增 `ModBlocks.block(CompressedForm, int level)`，代码里仍然不出现任何方块 ID。
- **水合**：`CompressedFarmlandBlock.randomTick`。水源扫描范围 `9×3×9`（水平 ±4、**上下各 1 层**）；原版 `FarmBlock.isNearWater` 只扫 `y ∈ [0, +1]`，这里向下多扫一层（票要求「含上下各 1 层」）。`MOISTURE` 降到 0 就停住，**不**变回泥土。
- **草蔓延**：`CompressedGrassBlock.randomTick`。亮度 `getMaxLocalRawBrightness(above) >= 9`；目标须为同等级 `DIRT`；`canBeGrass` / `canPropagate` 照抄原版；每次最多 4 次尝试。换方块走 `CompressedChestTransfer`，被覆盖那格的货仓原样保留。
  ⚠ 取样范围照抄原版 `(nextInt(3)-1, nextInt(5)-3, nextInt(3)-1)`，实际是 **3×5×3**（向上 1 格、向下最多 3 格），票里写的是「3×3×3」——按「照抄原版」处理。
  ⚠ **未实现**原版的「长不成草就变泥土」分支（那会换掉方块，本票没要求）；但保留了「长不成草就不蔓延」（原版 `canBeGrass`），被压住的草地不会往下蔓延。
- **踩踏**：`CompressedFarmlandBlock.fallOn`。判据 = 原版 `FarmBlock.fallOn`（`random < fallDistance - 0.5`、活体、玩家或开生物破坏、底面积×高度 > 0.512）**外加「潜行不踩踏」**（1.21.1 原版无此判断，按本票要求加上）。命中后换成同等级压缩泥土，走搬运工具，**不调** `FarmBlock.turnToDirt`。
- **验证**：`./gradlew test build` 绿（JUnit 28/28 未退化）；`runGameTestServer` → **24/24 全绿**（21 条旧 + 3 条新：转换保货仓、水源水合、草蔓延保货仓且不跨等级）。
- **未验证（headless）**：真实玩家手持锄头/铲头右键的手感、粒子在客户端的观感、降雨水合（GameTest 世界无天气控制）、活塞迁移（09b）。

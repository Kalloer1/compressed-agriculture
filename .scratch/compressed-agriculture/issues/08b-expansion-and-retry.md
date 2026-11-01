# 08b: 扩容配方与三处重试时机

**What to build:** 玩家能用配方把货仓容量升一档（每格上限 64 → 128 → 192 …）。货仓满了导致结算失败时，玩家关界面、右键方块、或拆掉方块之前，系统都会再试一次结算，让成熟作物被补收。

**Blocked by:** 08（界面已能读写货仓）、04（作物模块）、07（瓜类模块）

**Status:** done

## 验收标准

### 扩容配方

- [x] 扩容配方：`chestCount + 1`，**仅当 `chestCount < MAX_CHEST_COUNT` 时可合成**（到顶时配方不匹配，不消耗材料）
- [x] 输入 = 1 个压缩方块 + 其余材料（材料自定，写进票内）；产物保留输入方块的**等级、形态与全部 27 格内容**，只把 `chestCount` 加 1
- [x] 累加用 `Math.addExact`；结果超 `ChestCapacity.MAX_CHEST_COUNT` → 不匹配
- [x] 扩容**不改变**倍率 `9^L`；等级**不改变** `chestCount`（ADR-0003，两者正交）
- [x] `MAX_CHEST_COUNT = 1_000_000_000L`，加载时 clamp（03 已实现，确认即可）
- [x] 实现方式：`CustomRecipe` 子类 + `RecipeSerializer` 注册，参照 `recipe/CompressedUpgradeRecipe` 的写法

### 三处重试时机（ADR-0005，均不轮询）

- [x] **重试逻辑抽成公开入口**：现在 `CropModule.trySettle` 是 `private`。把它（以及瓜类的等价逻辑）抽成一个可复用的单元，三处重试与两个模块共用，**不各写一份**
- [x] 位置 1：玩家关闭本模组 menu → `PlayerContainerEvent.Close`，对该 BE 上方 3×3 范围尝试一次结算
- [x] 位置 2：玩家右键该压缩方块 → 交互时顺带尝试一次
- [x] 位置 3：玩家破坏该压缩方块前 → 尝试一次（`PlayerInteractEvent.LeftClickBlock` 或 `BlockEvent.BreakEvent`，选一个并写明理由）
- [x] 三处都**不做定时轮询**
- [x] 重试只处理「成熟且尚未结算」的方块，已结算过的不重复结算（`SUCCESS` 后作物已被重置，天然满足；但要在票里写明确认过）

### 验收场景（写进票内，手工验证）

1. 货仓填满 → 作物长熟 → 保持成熟态（不重置）
2. 打开界面取走一些 → 关闭界面 → 作物被补收、重置幼苗
3. 右键该方块 → 同样补收
4. 拆掉该方块前 → 先补收，再掉出携带数据的物品（ADR-0019）

## 备注

- 三处重试都在「玩家主动动作」上，不引入 tick 轮询（ADR-0005 / ADR-0012）
- 破坏前重试与 03b 的掉落搬运是两件事、同一路径上：**先重试结算（可能写入新产出），再掉出携带数据的物品**
- 扩容配方放这里而不是 03，因为它是唯一能看见 `chestCount` 数值的地方

## Comments

2026-10-31 · 从原 ticket 08 拆出

## 实现记录（08b）

### 扩容配方

- 配方 ID：`compressedagriculture:expand_compressed_chest`，序列化器 `compressedagriculture:compressed_expansion`，**一个** JSON 覆盖全部 27 个方块。
- 材料：**1 个压缩方块（任意形态、任意等级）+ 8 个 `minecraft:iron_ingot`**，3×3 格子里放哪一格都行（格子顺序无关）。
- 匹配规则（`CompressedExpansionRecipe.compressedSlot`）：9 格全满，恰好 1 格是 `BlockItem` 且 `getBlock() instanceof CompressedBlock`，其余 8 格都是铁锭；且该方块的 `chestCount + 1 ≤ MAX_CHEST_COUNT`（`Math.addExact`，溢出即不匹配）。到顶时不匹配，材料不消耗。
- 产物：**同一个物品**（形态、等级都不变），`chestCount + 1`，27 格内容原样保留——读输入 `BLOCK_ENTITY_DATA` 整份 tag → `ChestStorage.load` → 只改 `chestCount` → `ChestStorage.save` → `BlockItem.setBlockEntityData`。输入没有组件时按空货仓处理（`SCHEMA_VERSION` + `chestCount = 1` + 空槽）。

### 重试入口

- `com.kalloer.compressedagriculture.retry.SettlementRetry`，公开入口 `static void retryAbove(ServerLevel level, BlockPos chestPos)`：对 `chestPos` 上方 `x-1..x+1, y+1, z-1..z+1` 逐格尝试一次结算。
- 不复制结算逻辑：普通作物转调 `CropModule.trySettle`（由 `private` 改为 `public static`，`invalidInputWarn` 随之改为 `static final`），瓜类转调 `StemModule.harvest` + `StemModule.resetStem`（判据 `AGE == MAX_AGE`，与 `StemBlockMixin` 同一套收尾）。
- **已确认不重复结算**：`SUCCESS` 之后作物被重置（幼苗 / `AGE = 0`），`isMature` / `AGE == MAX_AGE` 都不再成立；`INSUFFICIENT_SPACE` 保持成熟态，等下一次重试。`SettlementRetryGameTest.retryDoesNotSettleTwice` 断言连续两次 `retryAbove` 只结算一次。

### 三处接线（`CompressedAgriculture` 构造器里注册，均无轮询）

1. `PlayerContainerEvent.Close` → `SettlementRetry.onContainerClose`：`event.getContainer() instanceof CompressedChestMenu` → `menu.blockPos()`（本次新增的 getter，取 BE 的坐标，客户端缺 BE 时为 `null`）→ `retryAbove`。
2. `PlayerInteractEvent.RightClickBlock` → `SettlementRetry.onRightClickBlock`：目标是压缩方块、且「未扩容或手里拿着东西（这次右键不会开界面）」时 `retryAbove`；会开界面的那种情况由位置 1 兜。
3. `BlockEvent.BreakEvent` → `SettlementRetry.onBlockBreak`。**选 `BreakEvent` 而不是 `LeftClickBlock`**：前者在方块真正被移除**之前**触发、只在服务端、只在确定要挖掉时触发一次；`LeftClickBlock` 会随按住左键连续触发，也会在空手试探时误触。事件返回后原版才走 `getDrops`，所以先补收的产出会被 03b 带进掉落物。

### 文件

- 新增：`recipe/CompressedExpansionRecipe.java`、`recipe/CompressedExpansionRecipeGameTest.java`、`retry/SettlementRetry.java`、`retry/SettlementRetryGameTest.java`、`data/compressedagriculture/recipe/expand_compressed_chest.json`
- 改动：`registry/ModRecipes.java`（多注册一个序列化器）、`crop/CropModule.java`（`trySettle` → `public static`，WARN 限流表 `static final`）、`menu/CompressedChestMenu.java`（新增 `blockPos()`）、`CompressedAgriculture.java`（三处监听）

### 验证

- `./gradlew test build` 通过：JUnit 33 个用例全绿（`WarnThrottleTest` 6 / `SettleTest` 10 / `ChestStorageExtractTest` 5 / `ChestStorageTest` 12）。
- 新增 8 条 GameTest（扩容 4 条 + 重试 4 条），**未执行**——按任务约束不跑 `runGameTestServer`，只保证编译通过。

## Comments

2026-10-31 · 完工（worker 实现，主会话修正一条测试断言 + 复核：JUnit 33/33、GameTest 34/34、`build` 绿）

- **扩容配方材料**：1 个压缩方块（任意形态/等级）+ 8 个 `minecraft:iron_ingot`，顺序无关；产物同形态同等级，`chestCount + 1`，27 格内容原样保留（读整份 `BLOCK_ENTITY_DATA` → `ChestStorage.load` → 只改 `chestCount` → `save` → `BlockItem.setBlockEntityData`）
- **重试入口**：`retry/SettlementRetry.retryAbove(ServerLevel, BlockPos chestPos)`，对上方 `x-1..x+1, z-1..z+1` 逐格试一次。普通作物转调 `CropModule.trySettle`（已从 `private` 改成 `public static`），瓜类转调 `StemModule.harvest` + `resetStem`，**没有第二份结算逻辑**
- **三处接线**：
  1. `PlayerContainerEvent.Close` → `menu.blockPos()` → `retryAbove`（主路径）
  2. `PlayerInteractEvent.RightClickBlock` → 目标是压缩方块且「不会开界面」时先重试
  3. `BlockEvent.BreakEvent`（选它而非 `LeftClickBlock`：移除前触发、仅服务端、按住左键不连发）→ 先重试，之后 `getDrops` 带走新数据
- **主会话修正**：worker 的 GameTest 断言「货仓只占 1 格」是错的——源方块 `chestCount = 4` → 每格上限 320，500 个小麦本来就跨 2 格。改成断言总量 500 一字不差（槽位个数属于分配策略，不是本票契约）
- 未验证（headless）：真实关界面/右键/破坏触发的补收，扩容配方的实际合成操作

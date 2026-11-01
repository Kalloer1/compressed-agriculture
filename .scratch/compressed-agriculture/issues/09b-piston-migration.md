# 09b: 活塞迁移

**What to build:** 活塞推动压缩方块时，货仓内容跟着走，一个物品都不丢。

**Blocked by:** 09（形态互转，已提供搬运工具）、03b（`saveData` / `loadData`）

**Status:** done

## 验收标准

- [x] 压缩方块能被活塞推动（原版 `PistonBaseBlock.isPushable` 的默认分支是 `!state.hasBlockEntity()`，带 BE 的方块一律推不动，所以必须 Mixin）
- [x] 推动后货仓内容与 `chestCount` 完整保留
- [x] 与形态互转**共用同一套 BE 搬运代码**（`CompressedBlockEntity.saveData` / `loadData`），不复制第二份
- [x] 粘性活塞拉回时同样保留数据
- [x] 不被推动的方块（例如被挡住时）数据不丢
- [x] 不能推动的边界情况与原版一致（黑曜石、基岩、世界高度限制等）

## 已知约束（`javap` 已核）

- `PistonMovingBlockEntity` **没有** `BlockEntity` 字段，只存 `movedState` → 原版根本不为移动中的方块保留 BE 数据
- `PistonBaseBlock.isPushable` 的 `switch (getPistonPushReaction())` 默认分支是 `return !state.hasBlockEntity();`

## 实现方向（两条路，选一条并写进票）

### ① 搬数据（推荐，满足 spec 故事 7/8）
1. Mixin `PistonBaseBlock.isPushable`：`CompressedBlock` 在默认分支放行
2. 在 `PistonBaseBlock.moveBlocks` 创建 `PistonMovingBlockEntity` 之后，把源 BE 的 `saveData` 塞进移动中的 BE（用 duck 接口 / `@Unique` 字段存 `CompoundTag`）
3. 在 `PistonMovingBlockEntity.finalizePistonMovement`（或 `tick` 结束时放置方块的那一步）把数据 `loadData` 到目标位置的新 BE
4. 边界：目标位置已有 BE（被覆盖）时按原版行为处理，不试图合并

### ② 禁止推动（保底）
`CompressedBlock` 覆写 `getPistonPushReaction()` 返回 `PushReaction.BLOCK`，并在票里写明「放弃 spec 故事 7/8，理由：原版移动 BE 的架构不支持，改造成本高于收益」。

**先试 ①；若 30 分钟内无法跑通 GameTest，退回 ② 并在票里记录取舍。**

## 验证
- GameTest：放一个装了货仓的压缩方块 → 用活塞推动一格 → 断言新位置方块是压缩方块、货仓内容一致
- 先 `rm -rf run/gameTestServer/world`（残留世界会卡死 batch）

## Comments

2026-10-31 · 从原 ticket 09 拆出

## Comments

2026-10-31 · 完工（worker 实现，主会话修正一条测试断言 + 复核）

**走的是路线①（搬数据）**，spec 故事 7/8 保住了。

- `mixin/PistonBaseBlockMixin`
  - `@Redirect` `isPushable` 里唯一一处 `BlockState.hasBlockEntity()`：`hasBlockEntity() && !(block instanceof CompressedBlock)` → 压缩方块放行，其它带 BE 的方块（箱子、熔炉）行为不变，高度/黑名单等边界判断原样保留
  - `@Redirect` `moveBlocks` 里的 `Level.setBlockEntity`：注册移动方块之前先 `PistonChestMigration.capture(...)`，从**源位置**读货仓。`moveBlocks` 先处理远端格子、后清源方块，所以此刻源 BE 还在
- `mixin/PistonMovingBlockEntityMixin`：`@Unique CompoundTag` 口袋（接口 `storage/PistonChestCarrier`），跟着 `saveAdditional`/`loadAdditional` 进出 NBT（移动只有 2–3 tick，但区块卸载仍可能发生）。落点还原接了两处：静态 `tick` 的 `TAIL` 与 `finalTick` 的 `TAIL`——`javap` 显示原版这两条路径各自内联了一份放置逻辑，只接一条会漏
- `storage/PistonChestMigration`：`capture` / `restore`，编码只走 `CompressedBlockEntity.saveData` / `loadData`，与 03b、09a 同一套（ADR-0002）
- `restore` 成功后清空携带的数据，所以 `tick` 与 `finalTick` 两条路径即使都跑到也只应用一次

**主会话修正**：worker 的 GameTest 断言「推动后源位置是空气」是错的——普通活塞推出后，活塞头会占据被推方块腾出来的那一格。改成断言源位置是 `minecraft:piston_head`，之后 26/26 全绿。

- `./gradlew test build` 通过（JUnit 28/28）
- `runGameTestServer`（先删 `run/gameTestServer/world`）→ **All 26 required tests passed**（新增 2 条：普通活塞推一格、粘性活塞拉回，均逐字节比对货仓 NBT）
- 未验证（headless）：多格连续推动、被挡住时不推动的数据安全性、区块卸载中途的移动方块
- 已知现象：GameTest 有一条失败后服务器不会自行退出（卡到超时）。排查时先看 `failed at` 那行，别当成新 bug

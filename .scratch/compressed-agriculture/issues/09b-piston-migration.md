# 09b: 活塞迁移

**What to build:** 活塞推动压缩方块时，货仓内容跟着走，一个物品都不丢。

**Blocked by:** 09（形态互转，已提供搬运工具）、03b（`saveData` / `loadData`）

**Status:** ready-for-agent

## 验收标准

- [ ] 压缩方块能被活塞推动（原版 `PistonBaseBlock.isPushable` 的默认分支是 `!state.hasBlockEntity()`，带 BE 的方块一律推不动，所以必须 Mixin）
- [ ] 推动后货仓内容与 `chestCount` 完整保留
- [ ] 与形态互转**共用同一套 BE 搬运代码**（`CompressedBlockEntity.saveData` / `loadData`），不复制第二份
- [ ] 粘性活塞拉回时同样保留数据
- [ ] 不被推动的方块（例如被挡住时）数据不丢
- [ ] 不能推动的边界情况与原版一致（黑曜石、基岩、世界高度限制等）

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

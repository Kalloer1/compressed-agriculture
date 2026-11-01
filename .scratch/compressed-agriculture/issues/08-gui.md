# 08: 货仓界面（27 格 + 取出 + 容量显示）

**What to build:** 玩家右键 `chestCount > 0` 的压缩方块，打开一个 27 格货仓界面：每格显示物品图标与 `long` 计数，能把东西取到背包里，界面上同时显示等级 `L` 与容量档位 `chestCount`。

扩容配方与三处重试时机拆到 `08b`，本票只做界面与同步。

**Blocked by:** 03（方块与货仓）、03b（`chestCount` 已能随物品搬运）

**Status:** ready-for-agent

## 验收标准

### 界面

- [ ] 右键 `chestCount > 0` 的压缩方块打开 27 格货仓界面
- [ ] `chestCount == 0` 时**打不开界面**，但收获仍正常写入、每格上限 64
- [ ] 每格显示物品图标与 `long` 计数
- [ ] 玩家能把物品取出来到自己的背包
- [ ] 界面同时显示**等级 `L`** 与**容量档位 `chestCount`** 两个数（ADR-0003 要求界面上能判断该扩哪个）
- [ ] 服务端 / 客户端同步走标准 `CustomPacketPayload`，不做特殊优化
- [ ] menu **继承 `AbstractContainerMenu`**（`08b` 的重试路径 1 依赖 `PlayerContainerEvent.Close`，不走标准 menu 就收不到）
- [ ] 打开界面用 `Player.openMenu(...)` + `IMenuProvider`（或 `NetworkHooks.openScreen`），不要在 `RightClickBlock` 里自己 `setScreen`

### 货仓数据

- [ ] GUI 的读写不破坏 03 定下的序列化约束（无 `cropId`、`counts[i] == 0` 时 `slotItems[i] == null`）

## 备注

- GUI 渲染本身无自动化测试价值（spec Seam 3），靠手动验证；但**取出逻辑**（`ChestStorage` 的扣减）必须写 JUnit
- `ChestStorage` 目前没有「取出」方法，本票要加，并保证：取出量不能超过该格现有量、扣到 0 时 `slotItems[i]` 变 `null`、`counts[i] == 0`（03 的序列化约束）
- 取出时物品 ID → `Item` 的转换在服务端做；未知 ID（模组被卸载）要能安全跳过并记 WARN，不能让 GUI 崩
- 扩容配方与三处重试时机 → `08b`

## Comments

2026-10-31 · 03 完工后补记

- `CompressedBlockEntity` 目前**没有 `getUpdateTag`**，货仓数据不同步到客户端。GUI 要显示货仓内容，本票必须补上（或在打开 menu 时用 `CustomPacketPayload` 同步，二选一，在票里写明）
- `chestCount` 的 GUI 展示依赖 `03b`（物品形态）——已完成

2026-10-31 · 拆分说明

- 原 ticket 08 拆成 **08（本票）= 界面 + 同步 + 取出** 与 **08b = 扩容配方 + 三处重试时机**
- 理由：一张票要在一个 worker 运行内完成「menu + screen + 网络同步 + 扩容配方 + 三处重试 + 跨模块重构」，实测会撞运行时限

# 08: 货仓界面（27 格 + 取出 + 容量显示）

**What to build:** 玩家右键 `chestCount > 0` 的压缩方块，打开一个 27 格货仓界面：每格显示物品图标与 `long` 计数，能把东西取到背包里，界面上同时显示等级 `L` 与容量档位 `chestCount`。

扩容配方与三处重试时机拆到 `08b`，本票只做界面与同步。

**Blocked by:** 03（方块与货仓）、03b（`chestCount` 已能随物品搬运）

**Status:** done

## 验收标准

### 界面

- [x] 右键 `chestCount > 0` 的压缩方块打开 27 格货仓界面
- [x] `chestCount == 0` 时**打不开界面**，但收获仍正常写入、每格上限 64
- [x] 每格显示物品图标与 `long` 计数
- [x] 玩家能把物品取出来到自己的背包
- [x] 界面同时显示**等级 `L`** 与**容量档位 `chestCount`** 两个数（ADR-0003 要求界面上能判断该扩哪个）
- [x] 服务端 / 客户端同步走标准 `CustomPacketPayload`，不做特殊优化（**已选①：`CompressedBlockEntity.getUpdateTag` + `getUpdatePacket` + 取出后 `sendBlockUpdated`**，见 Comments）
- [x] menu **继承 `AbstractContainerMenu`**（`08b` 的重试路径 1 依赖 `PlayerContainerEvent.Close`，不走标准 menu 就收不到）
- [x] 打开界面用 `Player.openMenu(...)` + `IMenuProvider`（或 `NetworkHooks.openScreen`），不要在 `RightClickBlock` 里自己 `setScreen`

### 货仓数据

- [x] GUI 的读写不破坏 03 定下的序列化约束（无 `cropId`、`counts[i] == 0` 时 `slotItems[i] == null`）

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

2026-10-31 · 实现记录（worker）

- **同步方式选①**：`CompressedBlockEntity.getUpdateTag`（= `saveWithoutMetadata`，与存档/物品形态同一份编码）+ `getUpdatePacket`
  （`ClientboundBlockEntityDataPacket.create(this)`）；界面取出后调 `changedAndSync()`（`setChanged` + `sendBlockUpdated`）推一次。
  界面的图标来自槽位同步的展示栈（数量封顶 64），精确 `long` 计数从同步过的 BlockEntity 货仓读。
- **provider 没挂在 `CompressedBlock` 上**：`MenuProvider#createMenu` 只拿得到玩家、拿不到坐标，方块实现 `MenuProvider` 无法
  知道自己被右键的是哪个位置。改为 `CompressedBlock.useWithoutItem` 里现场构造私有 record `ChestMenuProvider(pos)`，
  打开仍走 `player.openMenu(provider, pos)`（NeoForge 的 `IPlayerExtension#openMenu(MenuProvider, BlockPos)`，服务端发
  `AdvancedOpenScreenPayload` 把坐标带给客户端），没有自己 `setScreen`。
- 数据槽：`SimpleContainerData` 风格的 2 格数组，`[0] = chestCount`（≤ 1e9，装得进 int）、`[1] = level`。
- 取出：`ChestStorage.extract(int slot, long amount)`，越界/非正数/空格返回 0 且不改状态（槽位号来自客户端点击包，
  不能让它把服务端打崩），扣到 0 时 `slotItems[i] = null`、`counts[i] = 0`。JUnit 5 个用例在
  `src/test/java/.../storage/ChestStorageExtractTest.java`。
- 未知物品 ID（模组被卸载）在槽位读取处跳过并 WARN 一次（按 ID 去重），界面不崩。
- 未做：GameTest（`useWithoutItem` 的行为未自动化验证）、真实 GUI 渲染与点击交互（靠手动验证）。

## Comments

2026-10-31 · 完工（worker 实现，主会话复核：JUnit 33/33、GameTest 26/26、`build` 绿）

- **同步方式选①**：`CompressedBlockEntity.getUpdateTag()` = `saveWithoutMetadata`（与存档/物品形态同一份编码），`getUpdatePacket()` = `ClientboundBlockEntityDataPacket.create(this)`；取出后 `changedAndSync()` = `setChanged()` + `sendBlockUpdated`。界面不自己发网络包
- **打开方式**：`player.openMenu(provider, pos)`（NeoForge `IPlayerExtension`，服务端发带坐标的 payload），没有 `setScreen`。`chestCount == 0` → `useWithoutItem` 返回 `PASS` 且不开界面
- **槽位**：`StorageSlot`，`mayPlace` 恒 false（只取出）；展示栈数量截到 64（`ItemStack` 计数是 int），精确 `long` 计数走 BE 同步，等级/容量走 2 个数据槽
- **未知物品 ID**（模组被卸载）跳过并 WARN，按 ID 去重，界面不崩
- **shift 点击**循环终止于背包满，不会因天文数字计数死循环
- `ChestStorage.extract(int slot, long amount)`：返回实际取出量，扣到 0 时 `slotItems[slot] = null`、`counts[slot] = 0`；`amount <= 0` / 越界 / 空格返回 0 且不改状态。JUnit 5 条
- Screen 无贴图，面板与格子用 `GuiGraphics.fill` 画，背景沿用 `renderBackground` 默认实现
- 未验证（headless）：真实打开界面、图标与计数渲染、取出到背包、shift 点击

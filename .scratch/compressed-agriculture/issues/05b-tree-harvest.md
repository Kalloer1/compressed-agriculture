# 05b: 树木收获接线（生长 → 结算 → 拆除 → 回填）

**What to build:** 玩家把树苗种在压缩泥土 / 压缩草地上，树照常长成一棵真实的树。长成后整棵树被自动拆掉，掉落物乘 `9^L` 进货仓，原地重新出现同种树苗接着长。产物是原版自己算的——砍一棵橡树给的木棍、苹果、树苗，和你手动砍一模一样。

**Blocked by:** 05（枚举与 `validateCut`）

**Status:** done

## 验收标准

### 生长

- [ ] 树苗种在压缩泥土 / 压缩草地上，允许原版 `growTree` 正常执行，**不取消、不拦截** —— **headless 未验证，待手工**
- [x] 树苗保持原版两阶段生长节奏（`STAGE 0 → 1 → growTree`），不加速（ADR-0014）
- [x] 树苗下方是普通泥土时走原版路径，本模组不介入
- [ ] 2×2 基底探测只看**西北角**树苗下方那一格（与原版生长判定锚点一致）；不要求四格等级一致 —— **headless 未验证，待手工**

### 结算

- [x] 逐位置 `Block.getDrops(state, serverLevel, pos, blockEntity, player, tool)` 取真实掉落，掉落统计顺序固定（`settle` 结果可复现）
- [x] 汇总后调 `settle`，`L` 取容器所在格的等级（`CompressedBlock.levelOf(BlockState)`）
- [x] **单格树：容器 = 树苗脚下那个压缩方块**
- [x] **2×2 巨树：容器按 西北 → 东北 → 西南 → 东南 取第一个存在的 BE**（ADR-0004），不要求四格等级一致
- [x] `settle` 成功后所有枚举出的位置设为空气
- [x] `settle` 失败（`INSUFFICIENT_SPACE` / `INVALID_INPUT`）时不拆除，保留树等重试
- [x] **不触发** `BreakEvent` / `BlockDropsEvent`——不是玩家破坏，不该加经验、统计、成就
- [ ] 砍树时有木质破坏音效与粒子（位置在写入容器上方或 2×2 中心） —— **headless 未验证，待手工**

### 回填（ADR-0014）

- [x] 单格树拆完原位放一棵同种树苗
- [x] 2×2 巨树拆完四格都放同种树苗
- [x] 树苗 `STAGE` 必须置 0，否则会变成无限加速循环
- [x] **先拆后填**；2×2 的四格回填不会被自己的拆除逻辑命中
- [x] 原位已被其他方块占据时不回填、不覆盖

## 备注

- 触发时机：`SaplingGrowTreeEvent`（或等价事件）只用于**记录位置**，不 DENY；树真正长成后按「本 tick 待观察集合 + `ServerTickEvent.Post` 读实际状态」的模式处理（与 ticket 04 的骨粉路径同一套模式，可直接复用 `GlobalPosKey`）
- 枚举与 `validateCut` 一律调用 05 的 `TreeEnumeration`，不复制逻辑
- 失败重试与 08 的「重试时机」共用一套逻辑；本票先保证失败时树**完整保留**
- 06（蘑菇）会复用「枚举 → 结算 → 拆除 → 回填」这条链，**把它抽成可调用的单元**，不要写死在树苗监听器里
- 游戏内行为（长树、拆树、回填）headless 无法验证；能在 GameTest 里验的部分（例如「对一棵手搭的树调用拆除单元后，方块变空气且树苗回填」）尽量写成 GameTest

## Comments

2026-10-31 · 拆分说明

- 从原 ticket 05 拆出：本票负责事件接线、结算、拆除、回填、反馈；枚举与 `validateCut` 在 05

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

- **事件类不是 `SaplingGrowTreeEvent`**：NeoForge 21.1.235 里没有这个类（`javap` 确认）。实际用的是 `net.neoforged.neoforge.event.level.BlockGrowFeatureEvent`，它在 patch 过的 `TreeGrower.growTree` **放置 feature 之前**触发，四棵树苗还在——正好满足「2×2 必须在生长前判定」
- 枚举起点做了兜底：先试事件坐标，空结果再试西北角。原因：2×2 巨树的树干落在西北角，若事件坐标是另外三格之一，长完后那里是空气
- 容器选择、基底探测、回填**共用同一个有序列表**（西北 → 东北 → 西南 → 东南），不各自排序
- `./gradlew test build` 通过；`./gradlew runGameTestServer`（先删 `run/gameTestServer/world`）→ **All 6 required tests passed**，含新增的「手搭 3 格树 → 拆成空气 + 回填 STAGE=0 树苗 + 货仓 27 个橡木原木」
- 未验证（headless）：真树苗长成、2×2 巨树、音效粒子、货仓满时树完整保留
- `06` 可直接复用 `TreeHarvest.harvest(level, trunkPos, chestPos, refillCells, refillState)`

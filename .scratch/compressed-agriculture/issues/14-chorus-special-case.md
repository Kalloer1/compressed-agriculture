# 14: 紫颂特例

**What to build:** 压缩耕地上种的紫颂花长到第 2 格时，把**整株**（底座 `chorus_plant` + 顶上那朵花）一起结算，产物乘 `9^L` 写进货仓，然后在原位回填一朵 `age = 0` 的紫颂花，让循环继续。

**Blocked by:** 13（共用 `onPlace` 记录 + tick 末处理的管线）

**Status:** done

## 背景与已定决策

紫颂是这批植物里唯一「不是作物」的东西，必须单独处理：

- 它没有「成熟」状态，靠 `ChorusFlowerBlock.randomTick` 向上长；长高时**底座会变成 `chorus_plant`、头顶出现一朵新的 `chorus_flower`**——第 1 格和第 2 格是**两种不同方块**，所以 ticket 13 的「同方块」判定抓不到它。
- 命中条件：新方块（第 2 格）是 `chorus_flower`，且 `pos.below()` 是 `chorus_plant`，且 `pos.below(2)` 是压缩耕地。
- 决策：按「整株结算」——底座那格 `chorus_plant` 和顶上那朵花都拆，产物含紫颂果（能吃、有意义），而不是只掉一堆不能吃的紫颂花。
- 决策：回填**免费**（不从产物里扣），与仓库现有语义一致（蘑菇树拆完凭空回填一个同种蘑菇，ADR-0015）。
- 已知限制：紫颂花在上方被挡住时会**横向分枝**，分枝出来的花悬空（下方是空气），不在「第 2 格」的位置上，命中不了规则。**不处理**，正常开阔田地上永远不触发（只有玩家自己给田盖顶才会出现），记进文档。

## 关键源码事实（已核对 1.21.1 战利品表）

- `chorus_flower` 的掉落表：`{"condition": "minecraft:entity_properties", "entity": "this", "predicate": {}}` —— **没有实体就掉 0 个**。仓库现有 `CropModule` 的 `collectDrops` 用的是 `new LootParams.Builder(level)`（不带实体），直接查这张表会得到**空清单** → `settle` 返回 `INVALID_INPUT` → 什么都不发生、植株卡死。
- `chorus_plant` 的掉落表：`chorus_fruit`，`set_count uniform 0..1`，**无实体条件**，能正常查。

## 验收标准

### 判定与结算

- [x] 在 ticket 13 的 `onPlace` 记录层里增加紫颂分支（或新建一个并列的记录器）：命中条件 = 新方块是 `chorus_flower` 且 `pos.below()` 是 `chorus_plant` 且 `pos.below(2)` 是 `CompressedFarmlandBlock`（带货仓）
- [x] tick 末处理时重新校验状态；通过后：
  - 对底座 `chorus_plant`（`pos.below()`）查战利品表 → 通常 0–1 个紫颂果
  - 对顶上 `chorus_flower`（`pos`）查战利品表 → 1 朵紫颂花
  - **两次查询都必须带虚拟实体**：`LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, FakePlayerFactory.getMinecraft(level)).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))`，否则花的掉落恒为空、整个循环会断在 `INVALID_INPUT`
  - 合并成一份掉落清单 → `settle(drops, level, chestTarget)`，`level` 从 `pos.below(2)` 读，`chestTarget` 指向 `pos.below(2)` 的货仓
- [x] `SUCCESS` → 把底座和顶上那格都设为空气，然后在**底座位置**回填一朵 `age = 0` 的 `chorus_flower`（回填在拆除之后做，避免被自己的拆除逻辑命中）
- [x] `INSUFFICIENT_SPACE` → **整株原样保留**（停摆），不拆、不回填
- [x] `INVALID_INPUT` → 不拆、不回填，打 `WARN`
- [x] 回填的状态必须显式 `setValue(ChorusFlowerBlock.AGE, 0)`（原版 `AGE` 范围 0–5，不置 0 会从当前年龄继续，影响生长节奏）

### GameTest

- [x] 新建 `topcut/ChorusHarvestGameTest.java`，至少覆盖：
  - 1 级压缩耕地上放一朵 `age=0` 的紫颂花 → 模拟它长高（`setBlock` 底座为 `chorus_plant`、上方为 `chorus_flower`，这正是 `onPlace` 的触发路径）→ tick 末整株被拆、底座位置回填 `chorus_flower` 且 `AGE == 0`、货仓里出现 **9 的倍数**的紫颂花和/或紫颂果
  - 停摆：货仓塞满 → 整株保留、不回填
  - 反例：普通末地石上的紫颂花长高 → **不**被拆（只对压缩耕地生效）
  - 反例：只放一朵 `chorus_flower` 在压缩耕地上（还没长高）→ 不触发

### 文档

- [x] `docs/adr/0022-chorus-special-case.md`：新 ADR（0019 已被 `break-keeps-chest-on-item` 占用、0020 是 ticket 12 的 `universal-substrate`、0021 是 ticket 13 的 `top-cut`），记录：为什么紫颂不能走「同方块」规则、为什么整株结算而不是只掉花、为什么必须注入虚拟实体（附战利品表的 `entity_properties` 证据）、回填免费的理由、横向分枝作为已知限制
- [x] `GLOSSARY.md` 新增一个词（措辞照抄）：
  ```
  **紫颂循环**（chorus cycle）
  压缩耕地上的一朵紫颂花长到第 2 格时，整株（底座 `chorus_plant` + 顶花）一起结算、原位回填一朵 `age = 0` 的紫颂花。与「截顶」的区别：紫颂的第 1、2 格是两种不同方块，且结算时底座也一起拆。
  ```
  并在「已知限制」里补一句：紫颂横向分枝不处理。
- [x] `.scratch/compressed-agriculture/spec.md` 的「多格植物截顶」一节里补紫颂这个例外
- [x] `docs/manual-checklist.md` 补手工验收项：紫颂花在压缩耕地上能长高、长高后整株被收、原位回填一朵花、产物是紫颂果/花

## 完成判据

- [x] `./gradlew build --no-daemon` 成功
- [x] `runGameTestServer` 全绿
- [x] 提交一个 commit

## 备注

- `FakePlayerFactory` 在 `net.neoforged.neoforge.common.util.FakePlayerFactory`，`getMinecraft(ServerLevel)` 即可。不要用真实玩家、不要 new 一个 `Player`。
- 虚拟实体只用于让战利品表条件通过，**不要**拿它去触发任何事件或做权限判断。
- 如果 worker 认为注入虚拟实体有更干净的替代方案（例如给 `chorus_flower` 直接构造 1 个 `ItemStack` 而不查表），**先在报告里提出、不要私自换方案**——「产物由战利品表决定」是这个仓库的原则，换方案需要主会话拍板。

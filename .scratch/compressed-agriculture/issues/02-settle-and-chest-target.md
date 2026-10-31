# 02: 统一入口 `settle` 与 `ChestTarget`

**What to build:** 本模组的核心纯函数。给定一份掉落清单、一个等级 `L`、一个货仓抽象，它决定「乘完 `9^L` 之后能不能全写进去」，能就全写、不能就一个都不写。这一步不接触世界、不读方块、不认识任何具体作物——所有对世界的依赖都挡在 `ChestTarget` 这道接口后面，所以它可以完全脱离游戏被 JUnit 测试。

**Blocked by:** 01（工程骨架）

**Status:** ready-for-agent

## 验收标准

- [ ] `settle(drops, L, target)` 返回三值枚举 `SUCCESS` / `INSUFFICIENT_SPACE` / `INVALID_INPUT`
- [ ] `ChestTarget` 提供 `capacityPerSlot()` / `tryReserve(item, amount)` / `commit()` / `rollback()` 三阶段契约（ADR-0005 要求全有或全无，而不是「写不进就回退已写的部分」）
- [ ] 测试侧有 `FakeChest` 内存实现，固定容量、记录写入，JUnit 直接可用
- [ ] 工程已加 JUnit 依赖并能跑起来（模板 `build.gradle` 没有这项）
- [ ] 以下用例全绿：
  - [ ] 单物品 × `9^1` → `SUCCESS`，数量 ×9
  - [ ] 单物品 × `9^9` → `SUCCESS`，数量 387,420,489
  - [ ] 计数 × `9^9` 溢出 `long` → `INSUFFICIENT_SPACE`，`target` 无任何变化
  - [ ] 多物品其中一个放不下 → `INSUFFICIENT_SPACE`，**所有**物品都没写入
  - [ ] 空掉落清单 → `INVALID_INPUT`
  - [ ] `chestCount = 0` → 每格上限 64
  - [ ] `chestCount = MAX_CHEST_COUNT` → `stackLimit` 不溢 `int`
  - [ ] 同物品优先同槽 → 占一个槽而不是两个
- [ ] `9^L` 用 `long` 预先算好；乘算用 `Math.multiplyExact` 捕获溢出，**不静默截断**
- [ ] `stackLimit = 64 × (1 + chestCount)` 全程 `long`（ADR-0003）
- [ ] `settle` 内部没有任何 `Blocks.xxx` / 方块 ID 判断

## 备注

- 测试只测外部行为：测「返回什么、target 里最终存了什么」，不测内部几个循环、调了几次 `tryReserve`。判据是**重构内部实现时测试不该改**
- `INVALID_INPUT` 的 WARN 日志与限流（ADR-0007）在 04 接真调用方时落地，本票只要返回正确的枚举
- 等级 `L` 由调用方传入，入口内不反查坐标（ADR-0006）

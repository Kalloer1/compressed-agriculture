# 02: 统一入口 `settle` 与 `ChestTarget`

**What to build:** 本模组的核心纯函数。给定一份掉落清单、一个等级 `L`、一个货仓抽象，它决定「乘完 `9^L` 之后能不能全写进去」，能就全写、不能就一个都不写。这一步不接触世界、不读方块、不认识任何具体作物——所有对世界的依赖都挡在 `ChestTarget` 这道接口后面，所以它可以完全脱离游戏被 JUnit 测试。

**输入类型已定（ADR-0017）**：掉落清单是 `List<DropEntry>`，`DropEntry` = `(ResourceLocation itemId, long count)`；**不用 `ItemStack`**。`ItemStack` 构造会触发 `BuiltInRegistries` 静态初始化，普通 JUnit 里直接 `Not bootstrapped`，补 `Bootstrap.bootStrap()` 又因 NeoForge `FeatureFlagLoader` 需要 `LoadingModList` 而失败。调用方负责 `ItemStack → ResourceLocation` 的转换。

**Blocked by:** 01（工程骨架）

**Status:** done

## 验收标准

- [x] `settle(List<DropEntry> drops, int level, ChestTarget target)` 返回三值枚举 `SUCCESS` / `INSUFFICIENT_SPACE` / `INVALID_INPUT`
- [x] `ChestTarget` 提供 `capacityPerSlot()` / `tryReserve(ResourceLocation itemId, long amount)` / `commit()` / `rollback()` 三阶段契约（ADR-0005 要求全有或全无，而不是「写不进就回退已写的部分」）
- [x] `level` 不在 `1..9` 时返回 `INVALID_INPUT`（不做任何预留）
- [x] **`settle` 用 `try/finally` 保证 `commit` 或 `rollback` 二选一必然发生**，任何异常路径都不留下未决的预留状态
- [x] `ChestTarget` 的契约文档写明：预留状态只在**单次 `settle` 调用内**有效，不跨调用存活
- [x] 测试侧有 `FakeChest` 内存实现，固定容量、记录写入，JUnit 直接可用
- [x] 工程能跑 `test` 任务。**已确认**：NeoGradle 7.1.38 的 `testJunit` 是空壳（`testClassesDirs = []`、只依赖 `compileJava`），不能用于 `src/test`。正解是用标准 `test` 任务 + JUnit 5：
  ```groovy
  dependencies {
      testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
      testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
  }
  tasks.named('test') { useJUnitPlatform() }
  ```
  实测该配置可用（首次 `./gradlew test` 约 1m40s，之后数秒）
- [x] 以下用例全绿：
  - [x] 单物品 × `9^1` → `SUCCESS`，数量 ×9
  - [x] 单物品 × `9^9` → `SUCCESS`，数量 387,420,489
  - [x] 计数 × `9^9` 溢出 `long` → `INSUFFICIENT_SPACE`，`target` 无任何变化
  - [x] 多物品其中一个放不下 → `INSUFFICIENT_SPACE`，**所有**物品都没写入
  - [x] 空掉落清单 → `INVALID_INPUT`
  - [x] `chestCount = 0` → 每格上限 64
  - [x] `chestCount = MAX_CHEST_COUNT` → `stackLimit` 不溢 `int`
  - [x] 同物品优先同槽 → 占一个槽而不是两个
  - [x] 同一物品的两条掉落条目先合并再乘倍率（合并后仍按一个物品算）
  - [x] `level = 0` / `level = 10` → `INVALID_INPUT`
- [x] `9^L` 用 `long` 预先算好；乘算用 `Math.multiplyExact` 捕获溢出，**不静默截断**
- [x] `stackLimit = 64 × (1 + chestCount)` 全程 `long`（ADR-0003）
- [x] `settle` 内部没有任何 `Blocks.xxx` / 方块 ID 判断

## 备注

- 测试只测外部行为：测「返回什么、target 里最终存了什么」，不测内部几个循环、调了几次 `tryReserve`。判据是**重构内部实现时测试不该改**
- `INVALID_INPUT` 的 WARN 日志与限流（ADR-0007）在 04 接真调用方时落地，本票只要返回正确的枚举
- 等级 `L` 由调用方传入，入口内不反查坐标（ADR-0006）
- 所有算术（合并、乘倍率）必须在**第一次 `tryReserve` 之前**全部完成；算术溢出也按 `INSUFFICIENT_SPACE` 返回，且 `target` 不得被预留过
- 包结构建议：`com.kalloer.compressedagriculture.settle` 放 `Settle` / `SettleResult` / `ChestTarget` / `DropEntry`；`src/test/java` 同包放 `FakeChest` 与 `SettleTest`

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

- 新增 `settle/ChestCapacity`（`MAX_CHEST_COUNT` + `stackLimit(long)`），ticket 03 的 BE 复用它，不另声明常量
- `./gradlew test` → 10 tests, 0 failures；`./gradlew build` 出 jar
- 下游注意：`ResourceLocation` 构造入口是 `ResourceLocation.fromNamespaceAndPath(ns, path)`（1.21.1 构造器为 private）

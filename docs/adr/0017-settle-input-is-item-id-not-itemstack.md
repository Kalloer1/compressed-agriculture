# 0017 · `settle` 的输入用「物品 ID + 数量」而不是 `ItemStack`

- 状态：已采纳（2026-10-31）
- 影响面：§9 统一写入入口的输入类型、ticket 02 / 03 / 04
- 修正：方案 v2.0 §9「输入：掉落清单 `List<ItemStack>`」

## 背景

方案 §9 把 `settle` 的输入定义为 `List<ItemStack>`。ticket 02 要求这 8 个用例在 JUnit 里跑通，且明说「它可以完全脱离游戏被 JUnit 测试」。

实测（1.21.1 / NeoForge 21.1.235 / NeoGradle 7.1.38）：

1. NeoGradle 的 `testJunit` 任务是空壳：`testClassesDirs = []`，只 `dependsOn compileJava`，不编译也不执行 `src/test`。
2. 普通 `test` 任务可用（JUnit 5 能跑，`ResourceLocation` 正常）。
3. `new ItemStack(Items.WHEAT)` 在普通 JUnit 里直接 `IllegalArgumentException: Not bootstrapped`。
4. 补 `Bootstrap.bootStrap()` 仍然失败：NeoForge 的 `FeatureFlagLoader.loadModdedFlags` 要求 `LoadingModList` 非空，也就是必须起整套 FML mod 加载环境。

即：**只要 `settle` 的签名里出现 `ItemStack`，ticket 02 的 8 个用例就只能靠起游戏环境来跑**，与「纯函数、脱离游戏可测」直接冲突。

## 决策

`settle` 的输入改为纯值对象：

```java
record DropEntry(ResourceLocation itemId, long count) {}
SettleResult settle(List<DropEntry> drops, int level, ChestTarget target);
```

`ChestTarget.tryReserve(ResourceLocation itemId, long amount)` 同理用 `ResourceLocation`，不用 `Item` / `ItemStack`。

调用方负责边界转换：`BuiltInRegistries.ITEM.getKey(stack.getItem())`。

## 理由

- **货仓本来就用 `ResourceLocation` 存物品**（§10 `ResourceLocation[] slotItems`）。用 ID 做结算的身份键，结算与存储是同一个键，不需要在中间层做映射。
- **`ResourceLocation` 是可构造的纯值对象**，不触发注册表、不需要 bootstrap，实测在普通 JUnit 里可直接使用。
- **纯函数的边界更干净**：`settle` 连 `Item` 类型都不认识，更不可能去查方块/物品注册表，「入口内不含方块 ID 判断」这条约束变成类型层面的保证。
- 转换点只有一处（04 把 `getDrops` 的 `ItemStack` 列表转成 `DropEntry` 列表），成本一次性的、可测的。

## 备选方案

1. **保留 `ItemStack`，改用 NeoGradle 的 `junit` run 类型起 FML 环境跑测试**：可行但未验证；构建链更重、测试更慢，且与本票「纯逻辑」的定位不匹配。放弃。
2. **保留 `ItemStack`，测试里用 mock**：`ItemStack` 是 final 且构造即触碰注册表，mock 成本高、脆弱。放弃。
3. **用 `Item` 而不是 `ResourceLocation`**：`Item` 实例只能从 `Items.*` 拿到，仍会触发 `BuiltInRegistries` 静态初始化。放弃。

## 后果

- ticket 02 的 `ChestTarget`、`settle`、`FakeChest` 全部以 `ResourceLocation` 为身份键。
- ticket 03 的 `CompressedBlockEntity.tryReserve` 直接接收 `ResourceLocation`，与 `slotItems` 字段同型。
- ticket 04 在调用 `settle` 前把 `ItemStack` 转成 `DropEntry`；`count <= 0` 的掉落条目应在转换处丢弃。
- 同一物品的多条掉落（例如树拆出多个原木）由 `settle` 内部按 `itemId` 合并后再乘倍率。

# 0016 · 瓜类茎需要 Mixin `StemBlock.mayPlaceOn`，`#dirt` 标签只解决果实放置

- 状态：已采纳（2026-10-31）
- 影响面：ADR-0010 的前提、ticket 07 的可行性、ticket 03 的标签清单
- 性质：**补充 ADR-0010**，不推翻其结论

## 背景

ADR-0010 认定：压缩耕地必须加入 `#minecraft:dirt`，否则 `StemBlock.randomTick` 的 `FARMLAND || DIRT` 检查恒 false，瓜类路径是死代码。

该结论正确，但**不完整**。核查 `StemBlock` 源码后发现瓜类路径上有**两道**独立检查，ADR-0010 只覆盖了第二道。

## 问题

`net/minecraft/world/level/block/StemBlock.java`：

```java
// 第 77 行 —— 决定「能不能种下去」
@Override
protected boolean mayPlaceOn(BlockState $$0, BlockGetter $$1, BlockPos $$2) {
    return $$0.is(Blocks.FARMLAND);
}

// 第 93 行 —— 决定「果实能不能放在旁边」
if ($$1.getBlockState($$7).isAir() && ($$8.is(Blocks.FARMLAND) || $$8.is(BlockTags.DIRT))) {
```

`mayPlaceOn` 由 `BushBlock.canSurvive` 调用（`BushBlock.java:34`），而 `BushBlock.updateShape` 又调 `canSurvive`（`BushBlock.java:28`）。`StemBlock extends BushBlock`。

因此：**压缩耕地不在 `Blocks.FARMLAND` 里（它是另一个方块），瓜种根本种不下去。**

加 `#minecraft:dirt` 标签只让第 93 行为真。第 77 行用 `is(Blocks.FARMLAND)`，是方块身份比较，**不读任何标签**，标签方案对它完全无效。

后果链条：种不下去 → 不存在茎 → `randomTick` 永不执行 → 第 93 行的代码是死的。ADR-0010 修的是第二道门，第一道门仍锁着。

## 决策

**用 Mixin 改 `StemBlock.mayPlaceOn`**，使压缩耕地被接受。

```
@Mixin(StemBlock.class)
public abstract class StemBlockMixin {
    @Inject(method = "mayPlaceOn", at = @At("HEAD"), cancellable = true)
    private void compressedagriculture$acceptCompressedFarmland(BlockState state, ...) {
        if (state.getBlock() instanceof CompressedFarmlandBlock) cir.setReturnValue(true);
    }
}
```

判据用 `instanceof CompressedFarmlandBlock`，不用方块 ID 列表——符合核心原则。

## 为什么不选别的方案

| 方案 | 为什么不行 |
|---|---|
| 让 `CompressedFarmlandBlock extends FarmBlock` | `is(Blocks.FARMLAND)` 比较的是方块身份，子类不等于父类实例，仍然 false |
| 加入 `#minecraft:dirt` | 第 77 行不读标签，无效（这正是本 ADR 要澄清的） |
| 用 `BlockTags.FARMLAND` 之类标签 | 原版 `StemBlock` 源码里没有这样的标签读取，我们无法让它生效 |
| 只支持普通耕地种瓜 | 违背压缩农业的核心玩法，压缩耕地是主场景 |

## 与 ADR-0010 的关系

ADR-0010 有一句：

> 不给压缩耕地单独 Mixin 改这个条件：那会变成第二个注入点，且行为与标签方案完全等价，复杂度更高。

这句话**只针对第 93 行**——那里标签方案确实等价且更简单，结论仍然成立。

`mayPlaceOn`（第 77 行）是 ADR-0010 未考察的第二个检查点，标签方案在这里**不等价**（无效）。因此不构成矛盾，是本 ADR 补充的新情形。

## 后果

- ticket 07 的 Mixin 注入点从 2 个变成 3 个：`randomTick`、`performBonemeal`、`mayPlaceOn`
- ticket 03 的 `#dirt` 标签清单**仍然必需**（负责第 93 行），不可因为本 ADR 而删除
- 需要一条验证：压缩耕地上能正常种下西瓜/南瓜种，且茎能长到 `AGE=7`
- 模组茎（通过 `melon_like_stems` 标签接入）如果继承 `StemBlock`，同一个 Mixin 自动覆盖；如果自己实现了种植判断，需各自处理

## 未验证的延伸

`mayPlaceOn` 的 Mixin 是否会影响其他继承 `StemBlock` 的模组方块，取决于那些方块是否覆写 `mayPlaceOn`。覆写过的不会走本注入。这属于兼容性风险，实现时记录，不在本 ADR 预设结论。

## 修正（2026-10-31，`javap` 复核 NeoForge 21.1.235）

`StemBlock.mayPlaceOn` 在 NeoForge 版里已经不是 `state.is(Blocks.FARMLAND)`，而是
`state.getBlock() instanceof FarmBlock`。结论不变（压缩耕地不是 `FarmBlock` 子类，仍然为 false，Mixin 仍必需），
但注入判据应按 `instanceof CompressedFarmlandBlock` 写，理由见 ADR-0018「证据 B」。
`StemBlock.randomTick` 的 `instanceof FarmBlock || is(BlockTags.DIRT)` 未变，ADR-0010 的 `#dirt` 要求依然成立。

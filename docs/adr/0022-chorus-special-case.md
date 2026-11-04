# 0022 · 紫颂特例：整株结算 + 原位回填一朵 `age = 0` 的花

- 状态：已采纳（2026-11-04）
- 影响面：`topcut/TopCutModule` 增加紫颂分支、新增 `topcut/ChorusHarvestGameTest`。不新增 mixin、不新增 `ServerTickEvent.Post` 监听、不新增方块标签
- 关系：依赖 ADR-0021（共用 `onPlace` 记录 + tick 末处理这条管线）、依赖 ADR-0020（通用地基：紫颂花在压缩耕地上能活、能长）。ADR-0021 明确把紫颂排除在「同方块」规则之外，本文是那个例外

## 背景

紫颂是这批植物里唯一「不是作物」的东西，形态和生长方式都与甘蔗 / 仙人掌 / 竹子不同：

| 事实 | 后果 |
|---|---|
| 没有「成熟」状态，靠 `ChorusFlowerBlock.randomTick` 向上长 | 不能套用「熟了收、重置年龄」那套 |
| 长高时**第 1 格变成 `chorus_plant`、头顶出现一朵新的 `chorus_flower`** | 第 1、2 格是**两种不同方块**，ADR-0021 的「同方块」判定抓不到它 |
| `AGE` 范围 0–5，5 是「死花」，`isRandomlyTicking` 只在 `AGE < 5` 时为真 | 回填时不把年龄压回 0，下一轮会从半死状态起步 |
| 掉落表：`chorus_plant` → `chorus_fruit` `uniform 0..1`；`chorus_flower` → 自身 1 个，但带 `entity_properties(entity = "this")` 条件 | 花的掉落**必须带实体**才不是空清单（见下） |

不处理的话，压缩耕地上会竖着一根紫颂茎，货仓永远是空的。

## 决策

**压缩耕地上的一朵紫颂花长到第 2 格时，整株（底座 `chorus_plant` + 顶花）一起结算，产物乘 `9^L` 写进货仓，然后在底座原位回填一朵 `AGE = 0` 的紫颂花，让循环继续。**

五条子决策：

1. **复用 ADR-0021 的管线，只加分支**。记录仍在 `BlockBehaviourOnPlaceMixin` → `TopCutModule.onBlockPlaced`，处理仍在 `TopCutModule.onServerTick`。不复制一份 mixin、不再注册一个 `ServerTickEvent.Post` 监听——紫颂和多格植物是同一件事的两个分支，不是两条管线。
2. **判定**：新方块是 `ChorusFlowerBlock`、脚下那格是 `ChorusPlantBlock`、脚下 2 格是带 BlockEntity 的 `CompressedFarmlandBlock`。
3. **整株结算**：底座那格和顶花那格**都**查战利品表，合并成一份清单再交给 `settle(...)`。
4. **回填免费**：不从产物里扣（同 ADR-0015 的蘑菇回填），先拆后填。
5. **回填状态显式 `setValue(ChorusFlowerBlock.AGE, 0)`**。

## 为什么不能走「同方块」规则

ADR-0021 的判定是「第 2 格与第 1 格是同一方块」，用来挡「树苗 → 树干」「蘑菇 → 菌柄」。紫颂正好是反例：它长高时第 1 格被**替换成另一种方块**（`chorus_plant`），第 2 格才是新的花。

于是紫颂有两种接法：

- 放宽 ADR-0021 的判定 → 会把树木 / 蘑菇模块刚生成的结构一起砍掉，等于废掉两个模块。
- 单开一支 → 只多一个 `if`，其余全部复用。

选后者。判定用**方块类型**（`instanceof ChorusFlowerBlock` / `instanceof ChorusPlantBlock`）而不是方块 ID 或新标签：

- 这两个类本身就是原版给紫颂的专用类型，模组复用这两个类时自动生效；
- 代码里因此不出现任何具体方块 ID（GLOSSARY「核心原则」）；
- 回填也用**收下来那朵花的状态** `setValue(AGE, 0)`，不写死 `Blocks.CHORUS_FLOWER`；
- 只为一对固定方块新建两个标签文件，收益抵不上多出来的数据文件。

## 为什么整株结算而不是只掉花

只拆顶上的花的话，产物是「一堆不能吃的紫颂花」——紫颂花唯一的用途是再种，玩家拿一堆花没有任何意义。

底座那格 `chorus_plant` 拆掉才掉**紫颂果**（能吃、能做爆裂紫颂果），那才是这条产线的价值所在。所以两格一起查表、合并成一份掉落清单：紫颂花 1 个 + 紫颂果 0–1 个，整体乘 `9^L`。

压缩耕地上紫颂茎**只有 1 格**（长到第 2 格就被收），所以「底座那格」就是全部茎，不需要像树木模块那样做连通性枚举。

## 为什么必须注入虚拟实体

`chorus_flower` 的掉落表（`data/minecraft/loot_table/blocks/chorus_flower.json`，1.21.1）：

```json
{
  "type": "minecraft:block",
  "pools": [{
    "rolls": 1.0,
    "entries": [{
      "type": "minecraft:item",
      "name": "minecraft:chorus_flower",
      "conditions": [
        { "condition": "minecraft:survives_explosion" },
        { "condition": "minecraft:entity_properties", "entity": "this", "predicate": {} }
      ]
    }]
  }]
}
```

`entity_properties` 的目标是 `this`（`LootContextParams.THIS_ENTITY`）。没有这个参数时条件恒 false，**整张表返回空清单**。

后果不是「少掉一个物品」，而是整条链断掉：掉落清单为空 → `Settle.settle` 返回 `INVALID_INPUT` → 按 ADR-0007 不拆不回填、只打 WARN → 紫颂停在原地**永久卡死**（下一次生长要等它自己再长一格，而它已经长不动了）。

所以两次查询都带虚拟实体：

```java
new LootParams.Builder(level)
        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
        .withOptionalParameter(LootContextParams.THIS_ENTITY, FakePlayerFactory.getMinecraft(level))
        .withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(pos))
```

- 用 `FakePlayerFactory.getMinecraft(ServerLevel)`（`net.neoforged.neoforge.common.util`），不 new 玩家、不用真实玩家。
- 虚拟实体**只**用来让战利品表条件通过：不触发事件、不做权限判断、不进统计。
- `chorus_plant` 那张表没有实体条件，本来也能查；两格用同一个实体只是为了代码统一。

## 为什么回填免费

与 ADR-0015（蘑菇树拆完凭空回填一个同种蘑菇）同一个理由：回填的是**下一轮的种子**，不是产物。从产物里扣的话，产物是 `9^L` 的倍数而扣的是 1 个，语义会变得莫名其妙（等级越高越划算），而且一旦产物恰好只有 1 个（`9^L` 为 1 时）就会扣成 0 个，循环直接断。

回填免费 → 机器「种一次就不用管」，与树木 / 蘑菇模块一致。

## 为什么回填的 AGE 必须置 0

原版长高时新花的年龄**沿用**底座那朵（`placeGrownFlower(level, above, age)` 传的是当前 `AGE`，不是 `age + 1`）。也就是说收下来的那朵花可能是 `AGE = 4`。

`AGE = 5` 是「死花」，`isRandomlyTicking` 只在 `AGE < 5` 时为真。如果回填时照抄收下来的状态，下一次循环可能直接从「快死了」开始，再下一轮就变成死花、永远不再长。

显式置 0 = 每一轮都是完整的新循环，与「重置幼苗」（`age = 0`）语义一致。

## 为什么紫颂花在压缩耕地上能长

ADR-0020 让压缩耕地的 `canSustainPlant` 对一切植物返回 TRUE。NeoForge 给 `ChorusFlowerBlock` 打的补丁（`neoforge-21.1.235-userdev.jar` 的 `patches/net/minecraft/world/level/block/ChorusFlowerBlock.java.patch`）在 `canSurvive` 和 `randomTick` 两处都先问脚下的地基：

```java
TriState soilDecision = blockstate.canSustainPlant(level, pos.below(), Direction.UP, state);
if (!soilDecision.isDefault()) flag = soilDecision.isTrue();
else if (blockstate.is(Blocks.END_STONE)) { ... }   // 原版的末地石判断退到 else
```

所以压缩耕地上的紫颂花既能活（`canSurvive`）、也能长（`randomTick` 的 `flag`），不需要为本模组再写 mixin。这是 ADR-0020 的顺带收益，不是本文新加的。

## 停摆语义

`INSUFFICIENT_SPACE` → **整株原样保留**：不拆、不回填、不写货仓，打一次 WARN（每个坐标一次）。与 ADR-0021 的停摆一致，只是粒度从「第 2 格」变成「整株」——拆一半再失败会让玩家既丢产物又丢植株。

`INVALID_INPUT`（掉落清单为空，例如数据包把两张表都改成空表）→ 不拆不回填，WARN 带坐标与方块状态（ADR-0007）。

## 已知限制

**横向分枝不处理。** 紫颂花头顶被挡住时（`allNeighborsEmpty` 或 `isEmptyBlock(pos.above(2))` 不成立），原版会往**水平方向**分枝：新花悬空放在侧面，下方是空气。分枝出来的花不在「压缩耕地正上方第 2 格」这个位置上，命中不了本规则，会一直留在那里（它自己也长不动，因为下方既不是紫颂茎也不是末地石）。

正常开阔的田地上永远不触发——只有玩家自己给田盖顶才会出现。接受这个限制，不额外处理。

**同一格上手动堆花也会被收。** 玩家往压缩耕地的紫颂茎上手动放一朵花，与自动生长无法区分（旧状态都是空气），那格会被立刻收走——与 ADR-0021 的已知副作用同一性质。

## 验证

`ChorusHarvestGameTest` 覆盖：

1. 长高 → 整株被拆、底座原位回填 `AGE = 0` 的花、货仓里是 9 的倍数（花恒 9，果 0 或 9）
2. 货仓塞满 → 整株保留（茎 + 花都还在、不回填）；腾出两格并重新触发一次 → 恢复
3. 普通末地石上的紫颂长高 → 不被拆（只对压缩耕地生效）
4. 压缩耕地上只放一朵花（还没长高）→ 不触发
5. 回填的那朵花再调一次 `randomTick` → 再长高、又被整株收走，货仓里两轮共 18 朵（证明循环真的能继续）

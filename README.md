# 压缩农业（Compressed Agriculture）

Minecraft **1.21.1** / **NeoForge 21.1.235**。

把耕地压缩：9 个方块合成 1 个压缩方块，等级 `L`（1–9）决定收获倍率 `9^L`。作物、树、瓜、蘑菇长熟后自动收进压缩方块自己的 27 格货仓，产物直接乘倍率——**一格产出很多，不是一格种很多**。

## 玩法一览

| 内容 | 说明 |
|---|---|
| 压缩方块 | 2 形态（泥土 / 耕地）× 9 等级 = 18 个方块，各带 27 格货仓 |
| 倍率 | `9^L`：L=1 收 9 份，L=9 收 387,420,489 份 |
| 货仓 | 27 格，每格一种物品 + `long` 计数；每格上限 `64 × (1 + chestCount)` |
| 扩容 | 1 个压缩方块 + 8 铁锭 → 同一个方块，`chestCount + 1`（上限 10 亿） |
| 界面 | 右键 `chestCount > 0` 的压缩方块打开；未扩容打不开但收获照常写入 |
| 通用地基 | 压缩耕地什么都能种：下界疣、蘑菇、甘蔗、仙人掌、竹子、花、树苗、所有作物都活；例外可用禁种表排除 |
| 多格植物 | 甘蔗 / 仙人掌 / 竹子在压缩耕地上**只留 1 格**，长到第 2 格自动截顶进货仓；货仓满则停摆 |
| 紫颂 | 紫颂花在压缩耕地上长到第 2 格 → 整株（茎 + 花）一起结算、原位回填一朵新花，循环继续 |
| 合成 | 9 泥土 → L1；9 个同形态低一级方块 → 高一级（容量档位相加，内容清空） |

## 支持的收获

- **普通作物**：小麦 / 胡萝卜 / 马铃薯 / 甜菜 / 瓶子草 / 下界疣，以及任何在 `#compressedagriculture:compressible_crops` 里的方块
- **树木**：树苗长成真实的树 → 连通性枚举 → 逐方块取真实掉落 → 整棵拆除 → 原地回填树苗
- **瓜类**：西瓜 / 南瓜茎，果实直接进货仓、茎退回 0 龄
- **蘑菇**：骨粉催成蘑菇树 → 整棵拆掉 → 回填同种蘑菇；手动挖蘑菇也收
- **多格植物**：甘蔗 / 仙人掌 / 竹子在压缩耕地上只留 1 格，长到第 2 格就截顶
- **紫颂**：紫颂花长到第 2 格 → 整株（`chorus_plant` + 顶花）一起结算，原位回填一朵 `age = 0` 的紫颂花

## 数据包可改的标签

| 标签 | 默认 |
|---|---|
| `#compressedagriculture:compressible_crops` | `#minecraft:crops` + 下界疣 |
| `#compressedagriculture:compressible_saplings` | `#minecraft:saplings` |
| `#compressedagriculture:mushrooms` | 红蘑菇、褐蘑菇 |
| `#compressedagriculture:melon_like_stems` / `pumpkin_like_stems` | 空（接模组茎） |
| `#compressedagriculture:slimy_logs` / `tree_attachments` / `tree_roots` | 空 / 空 / `mangrove_roots` |
| `#compressedagriculture:not_plantable_on_compressed_farmland` | 空（禁种表：列入的植物在压缩耕地上退回原版判断） |
| `#compressedagriculture:multi_block_plants` | 竹子、仙人掌、甘蔗（多格植物：压缩耕地上只留 1 格） |

代码里不出现任何具体作物 / 树苗 / 果实的方块 ID——能种什么、能收什么全由标签与方块行为决定。

## 构建

```bash
JAVA_HOME=D:/tools/jdk21 GRADLE_USER_HOME=E:/mods/.gradle-home ./gradlew build
# 产物：build/libs/compressedagriculture-1.0.0.jar
```

**JEI 联动是可选的**：把 `jei-1.21.1-neoforge-*.jar` 放进 `libs/` 才会编译 `src/jei/java` 的插件源码；没有该 jar 时这部分不参与编译，构建照常成功，jar 里也不含 JEI 插件类。`libs/` 已 gitignore。

## 验证

```bash
./gradlew test              # JUnit 33 个用例（settle / 货仓 / 限流 / 取出）
./gradlew runGameTestServer # 34 条 GameTest
```

> 跑 `runGameTestServer` 前先 `rm -rf run/gameTestServer/world`。上一次运行被强杀会留下半初始化的世界，下一次会卡在 batch 永不结束（进度条停在 `[ ++++]`、日志每秒一条 `Gathered mod list`）。

## 文档

- 术语表：`GLOSSARY.md`（代码命名与注释统一用这套词）
- 决策记录：`docs/adr/0001`–`0020`
- 方案：`docs/plan-v2.0.md`；spec：`.scratch/compressed-agriculture/spec.md`
- 任务票：`.scratch/compressed-agriculture/issues/`（15 张，全部 done）
- 手工验收清单：`docs/manual-checklist.md`（headless 环境验不了的游戏内行为）

## 授权

All Rights Reserved。映射名来自 Mojang 官方映射，见 [Mojang.md](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md)。

# 12: 压缩耕地成为通用地基

**What to build:** 让压缩耕地通过重写 `canSustainPlant` 对所有植物「放行」——下界疣、紫颂花、蘑菇、甘蔗、仙人掌、竹子、花、树苗、所有作物都能直接种在压缩耕地上并存活。留一个默认为空的反向黑名单标签做逃生口。顺手删掉因为这一改动而变成死代码的三处 `mayPlaceOn` 注入。

**Blocked by:** 11（删除草地形态）

**Status:** ready-for-agent

## 背景与已定决策

- 决策：**默认放行所有植物**，只留反向黑名单（`#compressedagriculture:not_plantable_on_compressed_farmland`，默认空）。这样模组植物自动获得支持，遇到崩坏行为可以用数据包一键关掉。
- 决策：**接受两个副作用**——(a) 压缩耕地上的甘蔗**不需要临水**；(b) 所有作物（`CropBlock`）在压缩耕地上**不需要光照**，黑暗地窖里也能种。
- 已知保留的原版约束：**仙人掌的侧面检查**（四邻不能是实心方块）发生在 `canSustainPlant` 之前，不受本改动影响。
- `#minecraft:dirt` 标签**必须保留**：瓜类果实的落位判断是 `blockstate.getBlock() instanceof FarmBlock || blockstate.is(BlockTags.DIRT)`（`StemBlock.java:93`），不读 `canSustainPlant`。

## 关键源码事实（已核对 NeoForge 21.1.235 实际运行源码）

`net.neoforged.neoforge.common.extensions.IBlockExtension` 的 default 方法（`IBlockExtension.java:393`）：

```java
default TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing, BlockState plant) {
    return TriState.DEFAULT;
}
```

`Block` 已经 implements 了 `IBlockExtension`，所以**直接 override 即可，不需要任何 mixin**。

各植物 `canSurvive` 的第一步都是问它，返回非 DEFAULT 就采纳、后面整段硬编码跳过：

| 植物 | 位置 | 被绕过的原版检查 |
|---|---|---|
| `CropBlock`（含 `NetherWartBlock`、`PitcherCropBlock`） | `CropBlock.canSurvive` 第一行 | `hasSufficientLight`（光照 ≥ 8） |
| `SugarCaneBlock` | `SugarCaneBlock.canSurvive` 第一行 | 下方必须是 `#dirt`/`#sand` + **相邻有水** |
| `CactusBlock` | 侧面检查**之后** | 下方必须是沙子/仙人掌 |
| `BambooStalkBlock` | `canSurvive` 与 `getStateForPlacement` | `#bamboo_plantable_on` |
| `ChorusFlowerBlock` | `canSurvive` 第一行；`randomTick` 的 `flag` 判定也问它 | 下方必须是末地石 |
| `MushroomBlock` | 在 `#mushroom_grow_block` 检查之后、光照与满形状之前 | 光照 < 13、下方 `isSolidRender`（压缩耕地形状是 15/16，恒为 false——这就是蘑菇原本种不上的真正原因） |

## 验收标准

- [ ] `CompressedFarmlandBlock` override `canSustainPlant(BlockState, BlockGetter, BlockPos, Direction, BlockState)`：
  - `plant` 的方块属于 `#compressedagriculture:not_plantable_on_compressed_farmland` → 返回 `TriState.DEFAULT`（交回原版判断）
  - 其余一律返回 `TriState.TRUE`
  - 方法带完整 javadoc：说明这是「通用地基」的唯一实现点、两个副作用、以及仙人掌侧面检查不受影响
- [ ] 新建标签文件 `data/compressedagriculture/tags/block/not_plantable_on_compressed_farmland.json`，内容为 `{"values": []}`（默认空），并在文件里用注释或 ticket 说明用途
- [ ] 删除三处已变成死代码的 `mayPlaceOn` 注入（`canSurvive` 第一步就被 `canSustainPlant` 接管，永远走不到）：
  - `mixin/CropBlockMixin.java` 里 `mayPlaceOn` 那部分
  - `mixin/PitcherCropBlockMixin.java`（如果整个文件只做 `mayPlaceOn`，整文件删除并从 `compressedagriculture.mixins.json` 摘掉）
  - `mixin/StemBlockMixin.java` 里 `mayPlaceOn` 那部分（保留瓜类自动收获逻辑与 `StemBlockAccessor`）
- [ ] **保留** `CropBlockMixin` 里两个 `getGrowthSpeed` 的 `@Redirect`：`CropBlock.getGrowthSpeed` 读的是 `blockstate.is(Blocks.FARMLAND)`，**不走 `canSustainPlant`**，删了作物在压缩耕地上会按 3.0 慢速生长
- [ ] 新增 GameTest `block/UniversalSubstrateGameTest.java`，至少覆盖：
  - 下界疣能种在 1 级压缩耕地上且不弹掉；随机刻长到 age 3 后货仓里出现 9 的倍数的下界疣、方块回到 age 0（验证与 `CropModule` 的闭环）
  - 蘑菇能种在 1 级压缩耕地上、白天光照下**不**弹掉（验证光照与满形状两道都被绕过）
  - 甘蔗能种在**没有水**的 1 级压缩耕地上且存活
  - 仙人掌在压缩耕地上、**侧面贴着另一个压缩方块时仍然弹掉**（验证侧面检查未被绕过）
  - 把某个方块临时加进禁种表后它种不上（可选；若 GameTest 里改标签成本高，改为在报告里说明未覆盖）
- [ ] `docs/adr/0019-universal-substrate.md`：新 ADR，记录「压缩耕地对所有植物放行」这条决策，含上面「关键源码事实」表、两个副作用、禁种表逃生口、以及 `#minecraft:dirt` 为什么仍然必须保留
- [ ] `docs/adr/0010-...`：补一句「dirt 标签的保留理由已从『瓜类种植』变成『瓜类果实落位』（`StemBlock.java:93` 不读 `canSustainPlant`）」
- [ ] `GLOSSARY.md` 新增两个词：**通用地基**、**禁种表**（见 ticket 11 之后的术语表；措辞见下）
- [ ] `README.md` 玩法表补一行「压缩耕地 = 通用地基，什么都能种」

### 术语表措辞（照抄）

```
**通用地基**（universal substrate）
压缩耕地的新角色：`canSustainPlant` 对未被禁种表排除的植物一律返回 TRUE，因此任何植物都能种在压缩耕地上并存活。代价是失去原版的部分地基约束（甘蔗不需要临水、作物不需要光照）。

**禁种表**（not_plantable_on_compressed_farmland）
`#compressedagriculture:not_plantable_on_compressed_farmland`，默认空。列入其中的植物方块在压缩耕地上会退回原版判断。用途：某个模组植物在耕地上行为崩坏时，用数据包一键关掉而不改代码。
```

## 完成判据

- [ ] `./gradlew build --no-daemon` 成功
- [ ] `runGameTestServer` 全绿（基线 = ticket 11 的 23 + 本票新增用例数）
- [ ] 手动确认：`compressedagriculture.mixins.json` 里不再有被删的 mixin 条目
- [ ] 提交一个 commit

## 备注

- 只 override `canSustainPlant`，**不要**去实现 `IPlantable` 或 `canSustainPlant` 之外的地基接口。
- 不要动 `CompressedFarmlandBlock` 的 15/16 形状（`SHAPE` 同时用于碰撞盒）；蘑菇靠 `canSustainPlant` 绕过 `isSolidRender`，不需要改形状。
- 如果 worker 在实机上发现某个原版植物因为 `canSustainPlant` 返回 TRUE 而出现明显崩坏（例如花在耕地上立刻消失），**先记进报告、不要私自改回 DEFAULT**，由主会话决定是否加进禁种表。

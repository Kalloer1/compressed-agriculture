# 04: 作物模块

**What to build:** 玩家在压缩耕地上种一株小麦，什么都不用做。长熟的那一刻产物自动乘上 `9^L` 写进货仓，幼苗自己重置回 0 龄接着长下一轮。骨粉催熟的作物也一样被收走。货仓满了就保留成熟态不收、也不重置，等玩家腾出空间再来。

这是整个模组第一条**玩家能看见玩法**的竖切。

**Blocked by:** 02（`settle`）、03（方块与货仓）

**Status:** done

## 验收标准

### 种植与生长速度（ADR-0018）

- [x] Mixin 注入 `CropBlock.mayPlaceOn`：`instanceof CompressedFarmlandBlock` → 返回 true。不改写则小麦/胡萝卜/马铃薯/甜菜**根本种不下去**
- [x] Mixin 注入 `CropBlock.getGrowthSpeed`：压缩耕地按耕地参与计算（`is(Blocks.FARMLAND)` 的那处判断对压缩耕地返回 true，随后读 `MOISTURE`）
- [x] Mixin 注入 `PitcherCropBlock.mayPlaceOn`：同上（瓶子草不继承 `CropBlock`，需单独一点）
- [x] 判据一律 `instanceof CompressedFarmlandBlock`，不列方块 ID
- [ ] 游戏内验证：压缩耕地能种小麦，未浇水时生长速度与普通耕地一致；普通耕地行为不受影响 —— **headless 环境未验证，待手工进游戏确认**
- [x] 若 `getGrowthSpeed` 的注入在压缩耕地上抛 `IllegalStateException`，说明 03 的 `CompressedFarmlandBlock` 漏了 `MOISTURE` 属性——回去修 03，不要用 try/catch 绕过

### 识别

- [x] 可压缩作物 = 属于 `#compressedagriculture:compressible_crops`，**或**是 `CropBlock` 子类且 `isMaxAge(state) == true`。二者取**或**
- [x] 识别前先排除：不是树苗、不是可可豆、**不是 `instanceof StemBlock`**（ADR-0009，茎交给 07）
- [x] 作物脚下必须是压缩方块才走本模组路径，否则放行原版
- [x] `L` 取作物**正下方**那个压缩方块的等级（ADR-0006）

### 路径 A：随机刻

- [x] 挂 `CropGrowEvent.Post`，检查 `getNewState()` 是否成熟，成熟则调 `settle`
- [x] 成功 → 重置幼苗到 `age = 0`

### 路径 B：骨粉

- [x] 挂 `BonemealEvent`，**只记录** `(ResourceKey<Level>, BlockPos.asLong())` 进本 tick 待观察集合
- [x] **不取消事件、不调 `setSuccessful`**（两者互斥，见 ADR-0001）
- [x] 监听器开头挡掉非 `ServerLevel`——`BonemealEvent` 客户端也会触发
- [x] 在 `ServerTickEvent.Post` 遍历 `server.getAllLevels()`，对每个待观察位置读当前 `BlockState`：成熟 → 结算；未成熟 → 不动；已不是作物 → 不动
- [x] 待观察集合**每 tick 无条件清空**
- [x] 同一 tick 内同一位置多次骨粉只结算一次

### 失败语义（ADR-0005 / ADR-0007）

- [x] `INSUFFICIENT_SPACE` → 不写入、不生成 `ItemEntity`、**保留成熟态**，等下次事件驱动重试
- [x] `INVALID_INPUT` → 不写入、不重置，记 **WARN**，内容含方块 ID、维度、坐标、当前 `BlockState`
- [x] WARN 按 `(维度, 坐标)` 限流，同位置每 600 tick 最多一次；限流表与待观察集合**分开维护**
- [x] 成功 → 重置幼苗

### 边界

- [x] 成熟作物不进入随机刻（`isRandomlyTicking` 返回 `!isMaxAge`），因此保留成熟态**不会**造成 tick 风暴
- [x] 空的战利品表不会让植株被破坏

## 备注

- 重试时机不在这张票里，留给 08。本票只要「失败后状态正确保留」
- 测试策略：事件时机的对错不写自动化测试（spec Seam 3 已说明），靠进游戏手动验证
- 茎必须确认不被本票重复结算——需要一条「`melon_stem` 放在压缩耕地上成熟后只由瓜类路径结算」的验证，具体自动化形式留给 07

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

- `./gradlew test` → 28 用例全绿（新增 `WarnThrottleTest` 6）；`./gradlew build` 出 jar
- `./gradlew runServer` → 两个 Mixin 成功 apply，`Done (4.8s)`，无 injection 报错
- **偏离任务书（有证据）**：NeoForge 21.1.235 已把 `CropBlock.getGrowthSpeed` 改成 `canSustainPlant → TriState` + `isFertile`，首参从 `Block` 变 `BlockState`，方法体内没有 `is(Blocks.FARMLAND)`。实际注入点是这两个 INVOKE 的 Redirect（见 ADR-0018「证据 B」）
- 事件 API 实际名是 `CropGrowEvent.Post.getState()`（新状态）+ `getOriginalState()`，不是 `getNewState()`
- `trySettle` 目前是 `private`；08 的重试逻辑应把它抽成公开入口复用，不要复制粘贴

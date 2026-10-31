# 04: 作物模块

**What to build:** 玩家在压缩耕地上种一株小麦，什么都不用做。长熟的那一刻产物自动乘上 `9^L` 写进货仓，幼苗自己重置回 0 龄接着长下一轮。骨粉催熟的作物也一样被收走。货仓满了就保留成熟态不收、也不重置，等玩家腾出空间再来。

这是整个模组第一条**玩家能看见玩法**的竖切。

**Blocked by:** 02（`settle`）、03（方块与货仓）

**Status:** ready-for-agent

## 验收标准

### 识别

- [ ] 可压缩作物 = 属于 `#compressedagriculture:compressible_crops`，**或**是 `CropBlock` 子类且 `isMaxAge(state) == true`。二者取**或**
- [ ] 识别前先排除：不是树苗、不是可可豆、**不是 `instanceof StemBlock`**（ADR-0009，茎交给 07）
- [ ] 作物脚下必须是压缩方块才走本模组路径，否则放行原版
- [ ] `L` 取作物**正下方**那个压缩方块的等级（ADR-0006）

### 路径 A：随机刻

- [ ] 挂 `CropGrowEvent.Post`，检查 `getNewState()` 是否成熟，成熟则调 `settle`
- [ ] 成功 → 重置幼苗到 `age = 0`

### 路径 B：骨粉

- [ ] 挂 `BonemealEvent`，**只记录** `(ResourceKey<Level>, BlockPos.asLong())` 进本 tick 待观察集合
- [ ] **不取消事件、不调 `setSuccessful`**（两者互斥，见 ADR-0001）
- [ ] 监听器开头挡掉非 `ServerLevel`——`BonemealEvent` 客户端也会触发
- [ ] 在 `ServerTickEvent.Post` 遍历 `server.getAllLevels()`，对每个待观察位置读当前 `BlockState`：成熟 → 结算；未成熟 → 不动；已不是作物 → 不动
- [ ] 待观察集合**每 tick 无条件清空**
- [ ] 同一 tick 内同一位置多次骨粉只结算一次

### 失败语义（ADR-0005 / ADR-0007）

- [ ] `INSUFFICIENT_SPACE` → 不写入、不生成 `ItemEntity`、**保留成熟态**，等下次事件驱动重试
- [ ] `INVALID_INPUT` → 不写入、不重置，记 **WARN**，内容含方块 ID、维度、坐标、当前 `BlockState`
- [ ] WARN 按 `(维度, 坐标)` 限流，同位置每 600 tick 最多一次；限流表与待观察集合**分开维护**
- [ ] 成功 → 重置幼苗

### 边界

- [ ] 成熟作物不进入随机刻（`isRandomlyTicking` 返回 `!isMaxAge`），因此保留成熟态**不会**造成 tick 风暴
- [ ] 空的战利品表不会让植株被破坏

## 备注

- 重试时机不在这张票里，留给 08。本票只要「失败后状态正确保留」
- 测试策略：事件时机的对错不写自动化测试（spec Seam 3 已说明），靠进游戏手动验证
- 茎必须确认不被本票重复结算——需要一条「`melon_stem` 放在压缩耕地上成熟后只由瓜类路径结算」的验证，具体自动化形式留给 07

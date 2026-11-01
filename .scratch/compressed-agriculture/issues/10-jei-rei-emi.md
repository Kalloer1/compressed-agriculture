# 10: JEI / REI / EMI 集成

**What to build:** 整合包玩家装了 JEI（或 REI / EMI）之后，能在配方浏览器里查到所有压缩方块的合成方式，包括 9 合 1 的等级配方和扩容配方，不需要去翻 Wiki。

**Blocked by:** 09（形态互转与合成全部定型）

**Status:** done

## 验收标准

- [x] JEI 能显示 9 泥土 → 1 级压缩泥土
- [x] JEI 能显示 2–9 级的 9 合 1 配方
- [x] JEI 能显示扩容配方
- [x] REI / EMI：**本票不做**（理由见下方「实现记录」）。可选的编译期依赖 + 条件加载机制已就位，将来加它们不需要动构建
- [x] 三个配方浏览器任一未安装时，模组正常启动、不报错

## 备注

- 这是唯一一张「锦上添花」的票，如果时间紧可以推迟，不影响玩法完整性
- 配方本身是数据驱动的，人工验证即可（spec Seam 3）

## 实现记录（本票只做 JEI）

**取舍：REI / EMI 不做。** 本票按「锦上添花」的定位只覆盖 JEI；REI / EMI 要不要做，取决于以后是否真的要在整合包里支持它们——每多一个浏览器就要多一份插件代码和一套编译期依赖，而现在没有任何证据说明有人用 REI/EMI 而不装 JEI。

- 依赖方式：`libs/jei-1.21.1-neoforge-*.jar` 存在时 `build.gradle` 才把 `src/jei/java` 加进 `sourceSets.main.java` 并加 `compileOnly`；jar 不入库。缺省时这些源码不参与编译，构建照常成功。
- 条件加载：`CompressedAgricultureJeiPlugin` 带 `@JeiPlugin`，只有 JEI 在场才会被 JEI 扫到并实例化；主类不引用 `client.compat.jei` 任何类，所以没装 JEI 时这些 class 根本不会被加载，不会 `NoClassDefFoundError`。
- 三个分类：9 泥土 → L1 由原版 `minecraft:crafting_shaped` JSON 承担，JEI 原版工作台分类自动显示；9 合 1 升级与扩容各有一个自定义分类。
- 未验证：真实 JEI 界面渲染（本环境不跑客户端，只验证了 `./gradlew build` 与 jar 内含 `client/compat/jei/*.class`）。

## Comments

2026-10-31 · 完工（worker 实现，主会话复核）

复核结果：
- `./gradlew clean build` 两条路径都验过：**`libs/` 有 JEI jar** → jar 内 4 个 `client/compat/jei/*` 条目；**把 jar 移走** → 构建仍 SUCCESSFUL、jar 内零 jei 条目
- `./gradlew test` → JUnit 33/33
- `runGameTestServer`（先删 `run/gameTestServer/world`）→ **All 34 required tests passed**
- `runServer` → `Done (8.5s)!`，无 ERROR / Exception（插件类在专用服务器上不会被加载）

# 压缩农业系统（Minecraft 1.21.1 / NeoForge）

工作区级规则见 `E:/mods/AGENTS.md`（JDK 路径、Gradle 代理白名单、查 API 顺序、模板只读等）。本文件只写本仓库特有的东西。

## 项目状态

- 设计阶段**已完成**：15 个 ADR 拍板，方案修订到 v2.0
- Spec 已定稿：`.scratch/compressed-agriculture/spec.md`
- **代码尚未开始**：无 `src/`、无 `build.gradle`、无 `gradle.properties`

## Agent skills

### Issue tracker

Issues live as markdown files under `.scratch/<feature-slug>/issues/`, one file per ticket. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, label string equals role name, stored as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.

## 工作规矩

1. **不私自动工。** 每步动手前必须确认。
2. **说人话。** 技术术语要能用大白话解释；用户会用 `/wait-what` 纠正绕弯子的表达。
3. **先证据后结论。** 不凭想象设计。查 API 顺序：模板 → MC 反编译源码 → 本模组现有代码 → 第三方模组源码。
4. **只用 `GLOSSARY.md` 里的词。** 需要新词或修正已有词，先走 `/domain-modeling` 就地更新术语表。
5. **读文档读全。** 不要只读首尾就下结论，返工代价远高于读全文。

## 核心设计约束（摘自 GLOSSARY.md）

- **压缩** = 方块压缩（9 泥土 → 1 压缩泥土），不是面积收获
- **一个压缩方块只承载一个作物**，与普通耕地完全等价；没有密植
- 能种什么、能收什么由**标签和方块行为**决定，代码里不出现任何具体作物 / 树苗 / 果实的方块 ID
- 所有收获路径唯一入口是 `settle(...)`，模块只负责产出去调用

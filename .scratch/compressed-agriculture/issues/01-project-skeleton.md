# 01: 工程骨架

**What to build:** 一个能构建、能进游戏的空压缩农业模组。玩家装上这个 jar 后，游戏正常启动，模组列表里能看到「压缩农业」，但还没有任何方块和物品。这一步的唯一目的是让后面所有的 vertic 切片有一个能跑 `gradlew build`、能跑 `gameTestServer`、能进游戏的落脚点。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## 验收标准

- [ ] 以 `E:/mods/templates/mdk-1.21.1-neoforge` 为起点复制到本项目根目录，模板目录本身保持只读、未被改动
- [ ] 7 类占位符全部替换（`examplemod` / `com.example.examplemod` 无一残留），判据是 `grep -ri examplemod` 在源码与资源目录里零命中
- [ ] 包目录名、`assets/<mod_id>/` 目录名本身已改名，不是只改了内容
- [ ] `JAVA_HOME=D:\tools\jdk21` 下 `./gradlew build` 成功，产出 jar
- [ ] jar 内 `META-INF/neoforge.mods.toml` 的 `modId` 是 `compressedagriculture`
- [ ] `gameTestServer` 运行配置可用（能启动到等待测试阶段，此时无测试也可）
- [ ] `.gitignore` 覆盖 `build/`、`.gradle/`、`run/`、`.agent_tmp/`
- [ ] 仓库有首个 commit

## 备注

- 构建环境细节（JDK 路径、Gradle 代理白名单、`--no-build-cache` 的适用场景）见 `E:/mods/AGENTS.md`，不要重新摸索
- 构建验证若需要在临时目录试跑，用 `E:/mods/.agent_tmp/`，不要在模板目录里动
- 这一步不写任何业务代码，`settle` 之类全部留给 02

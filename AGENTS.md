# AGENTS.md — 更多生物（yg-more-mobs）开发规范

> 本包是 yunxigames 系列的玩法包之一。系列总览、公共约定与全系列踩坑速查见
> [yunxigames 文档仓库](https://github.com/Yunqingqingxi/yunxigames) 的 AGENTS.md（必读）。
> 本文件是本仓库开发者（人类与 AI）的入口，开工前通读。

## 1. 本包是什么

**更多生物**：「苦力怕幻翼」——幻翼保留原生翅膀 / 尾巴 / 飞行姿态 / 眼睛层，头与躯干换成苦力怕；
俯冲命中引发苦力怕爆炸（服务端 mixin），俯冲开始播自定义音效（bz.ogg）。
**全系列唯一例外包**：外观改造是纯客户端资源 + 渲染器（无自定义网络包），玩家原版客户端仍可直连。

- mod id：`yg_mobs`，jar：`yg-mobs-<版本>.jar`，配置：`config/yg-mobs.json`，入口 `YunxiGamesMobs`

### 类地图

| 类 | 职责 |
| --- | --- |
| `MobsConfig` | 本包全部配置项 + `validate()` 钳制（含 scaleBounds 缩放） |
| `mobs/PhantomSound` | 自定义音效的懒加载解析与播放（俯冲开始） |
| `mobs/mixin/PhantomCreeperMixin` | **服务端**：幻翼俯冲命中引发苦力怕爆炸（挂 `Mob#doHurtTarget` + `instanceof Phantom` 过滤） |
| `mobs/mixin/PhantomSweepSoundMixin` | **服务端**：俯冲开始播自定义音效（挂内部类 `Phantom$PhantomSweepAttackGoal`） |
| `src/client/…`（client 源集） | 混合模型 / 渲染器 / 客户端注册（`YunxiGamesMobsClient`） |
| `MobSelfTest` | 本包自检（外观只能目视，自检查资源在不在 / 契约坏没坏） |

### 三条设计底线 / 向后兼容承诺

1. 只在服务端做判定——**本包外观部分是系列唯一例外**（纯客户端，无自定义网络包）；
2. 一局制、零持久化；3. 物品不凭空消失。
mod id / jar 名 / 配置文件名 / lang key 永不改；配置字段只增不删；删字段 / 改默认行为升 major；
语义化版本 + GitHub Release 附 jar。

## 2. 环境（硬性）

| 组件 | 版本 |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.159.0+26.2 |
| **JDK** | **25**（本机 `D:\Java\jdk-25`，runServer/build 必须显式指定） |

一切 gradle 命令加 `--offline`。客户端编译要带上 `:compileClientJava`：
`./gradlew :compileJava :compileClientJava --offline`（本仓库单包，直接 `./gradlew compileJava compileClientJava --offline`）。

## 3. 常用命令

```bash
./gradlew compileJava compileClientJava --offline   # 本包有 client 源集，两边都要编
./gradlew test --offline                            # 三层 JUnit 测试
./gradlew smokeTest --offline                       # 只跑冒烟
JAVA_HOME='D:\Java\jdk-25' ./gradlew runServer --offline > selftest-<版本>.log 2>&1
JAVA_HOME='D:\Java\jdk-25' ./gradlew build --offline
```

- runServer 工作目录是本仓库自己的 `run/`（首次跑改 `run/eula.txt` 为 `eula=true`）；
- 自检前把 `run/config/yg-mobs.json` 的 `selfTestRolls` 改成 `200`，跑完**改回 `0`**；
- 自检完 runServer 不自退，手动结束 java 进程，否则 `run/` 被锁。

## 4. 代码规范

1. 一个功能一个类，类头 javadoc 写「是什么 + 为什么」；
2. 一切数值进本包 `MobsConfig`，带中文注释，每个功能独立开关（「爽但不劝退」）；
3. 新配置项必须在 `validate()` 钳制：`!(x >= lo && x <= hi)` 顺带治 NaN；
4. 中文注释 / 文案 / lang 键值；
5. 26.2 API 不确定：**先查反混淆 jar，别猜**。

## 5. 测试节奏

- 三层 JUnit（Smoke / Unit / Regression）+ runServer 自检；批量开发期只跑 `compileJava`；
- **新增功能必须同步新增自检项**并更新本包 README 的自检表；
- **画面类改动自检覆盖不到**：外观（头身对位、缩放）只能进游戏目视确认，
  自检只能查「贴图在不在 jar 里、有没有误覆盖原版资源、部件字段还在不在」。

## 6. 本包专属坑（全系列公共坑见系列仓库 AGENTS §7，本包是坑密度最高的包）

- **client 源集**：`loom { splitEnvironmentSourceSets() }` + mods 声明两个源集 +
  `jar { from sourceSets.client.output }` —— **客户端资源不会自动进 jar，漏了就静默缺贴图**；
- **永远不覆盖 `assets/minecraft/**`**（曾经的 phantom 贴图事故，会连原版资源一起改掉），
  自定义资源一律放 `assets/yg_mobs/**`；
- **Mixin `@Shadow` 不吃继承字段**；改父类字段会黑屏 → 正确姿势是**继承原版渲染器** +
  `EntityRendererRegistry.register`，别自己从 LivingEntityRenderer 白手起家；
- `ModelPart.skipDraw = true` 只藏自身方块（子部件照常）；`visible = false` 连子一起藏；
- **26.2 渲染延迟提交**：renderer 覆写 `submit()` 可多趟 `submitModel` / `submitModelPart`
  （双贴图混合模型就是这么做的）；
- **内部类 mixin**：`@Shadow @Final Phantom this$0`，可见性与目标一致；
- 音效播放走 `PhantomSound` 懒加载（入口期 SoundEvent 注册表时序不可靠，首次用时再解析）；
- mobkit 独立构建，靠 `../build/libs` 的 `yg-mobs-<mobs_version>.jar` 拿模型类——
  **升本包版本要同步 mobkit 的 mobs_version**。

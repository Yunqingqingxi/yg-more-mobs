# yg-mobkit — 生物外观预览工具包

> 开发期工具，**不随发行版发布**，不参与任何玩法包 jar。
> 独立 gradle 构建（放在 more_mobs/ 里，但不是它的子项目）；
> 靠 `../build/libs/yg-mobs-<mobs_version>.jar` 拿模型类，所以跑之前先在上层
> `more_mobs/` 里 `./gradlew build --offline` 出一次 jar（more_mobs 升版本要同步
> 改本目录 `gradle.properties` 的 `mobs_version`）。

做新生物、或者改已有生物的外观时，不必启动客户端就能看到"它长什么样"：
一条命令出多视图 PNG。

```bash
cd more_mobs/mobkit
JAVA_HOME='D:\Java\jdk-25' ./gradlew shot --offline
# 产物：more_mobs/mobkit-out/yg-mobs-{front,side,top,back}.png
```

---

## 一、为什么能不起客户端

关键在于分清「渲染」里哪一半需要 GPU：

| 环节 | 在哪 | 需要 GPU 吗 |
| --- | --- | --- |
| 模型几何与 UV | **客户端 jar 的代码**（`PhantomModel.createBodyLayer()` 之类，全是硬编码） | 否 |
| 烘焙成 `Cube`/`Polygon`/`Vertex` | `LayerDefinition.bakeRoot()`，**公开方法**，纯数学 | 否 |
| 部件变换与姿态 | `PoseStack` / `ModelPart`，纯 JOML 矩阵 | 否 |
| 把顶点画成像素 | `RenderType` + 着色器 | **是** |

所以只有最后一格需要替换。本项目用一个软件光栅化器替掉它，
**前三个环节全部用原版真实代码**，贴图采样也逐顶点用 `ModelPart.Vertex` 里那份原版 UV。

代价是：光照是近似的（固定主光 + 环境光下限）、没有雾与附魔光效、没有各向异性过滤。
收益是：**一次几秒**（启动客户端要一分多钟），完全确定性，可脚本化、可进 CI。

> 早期还试过「起客户端 + 自动摆机位截图」的方案。它能出真实画面，
> 但要下资源包、造/复制世界、绕开 HUD 与第一人称手臂，自动化链路又长又脆，
> 而且每次一分多钟。作为**日常改外观的反馈回路**，太慢。

## 二、架构

| 类 | 职责 |
| --- | --- |
| `ModelWalker` | 把真实 `ModelPart` 树按原版规则展开成面：变换顺序（位移→Z→Y→X→缩放）、可见性（`visible` / `skipDraw`）、逐顶点 UV |
| `Rasterizer` | 软件光栅化：正交投影 + 深度缓冲 + 最近邻采样 + 近似光照。多部件共用深度缓冲，所以**绘制顺序 = 游戏里"多趟提交"的先后** |
| `Texture` | 贴图加载与采样（注意 UV 是 **0~1 归一化**，不是像素坐标） |
| `ModelShot` | 核心 API：`View`（相机）/ `Part`（部件+贴图+姿态修正）/ `Preview`（一份预览定义）+ `render` |
| `Previews` | 各生物的预览定义（当前：苦力怕幻翼） |
| `PoseSearch` | 姿态搜索：枚举旋转组合、渲染后按像素自动打分，用来排查"朝向不对" |

### 最小用法

```java
ModelPart root = PhantomModel.createBodyLayer().bakeRoot();       // 1. 烘焙真实模型
ModelShot.Preview preview = new ModelShot.Preview("我的生物", 900, 700,
        List.of(ModelShot.Part.of(root, Texture.load("/assets/mymod/textures/entity/x.png"))),
        34.0F);                                                    // 2. 定义预览
ModelShot.render(preview, Path.of("out"));                        // 3. 出图
```

多个 `Part` 按列表顺序绘制 —— 先画的会被后画的正确遮挡，
这直接对应多趟渲染的模组（例如「苦力怕幻翼」：第一趟幻翼、第二趟苦力怕）。

## 三、加一个新生物的预览

1. 在 `Previews` 里加一个方法，烘焙它的 `LayerDefinition`；
2. 贴图放 `assets/<modid>/textures/entity/...`，用 `Texture.load("/assets/...")` 读；
3. 组 `Preview` 并 `render`；
4. 需要调姿态就加 `Part` 的 `rotX/rotY/rotZ` 与 `offsetX/Y/Z`（模型单位，16 = 1 格）。

## 四、踩过的坑（都写进代码注释了）

- **`ModelPart.getAllParts()` 返回的表里第一个元素是部件自身**，拿它递归会自己调自己
  → `StackOverflowError`。要递归请用内部 `children`（本项目反射读）。
- **UV 是 0~1 归一化**，不是「1/16 像素」的纹理像素坐标。按像素单位采样会一直打在
  左上角那个透明像素上，现象是**几何全对、着色像素 0**（本项目实际踩过）。
- **变换顺序是位移 → Z → Y → X → 缩放**，不是 X→Y→Z。顺序错了姿势全错。
- **`skipDraw` 只跳过自身方块、子部件照常**；`visible=false` 会连子部件一起不显示。
  这是"藏躯干、保翅膀"的关键。
- **单一旋转做不到「躯干横躺 + 头正立朝前」**：把躯干放平必然把头也翻倒，
  两者在同一个旋转下互相矛盾。头必须作为子部件**单独再转**（见 `Previews`）。
- **`xRot +=` 会累积**：一个 `ModelPart` 实例被多次渲染时，姿态修正要基于原始姿态算，
  否则第二次渲染就翻倍了。

## 五、当前状态

- ✅ 工具本身可用：真实模型烘焙 + 软件渲染 + 多视图输出，编译与运行都已实测通过。
- ⚠️ 「苦力怕幻翼」的**姿态常量仍在调**：躯干已能横躺、头已在前端，
  但头的**正立朝向**（脸朝前且不歪）还没最终确认。
  调参入口集中在 `Previews` 顶部几个常量，改完直接 `:mobkit:shot` 就能看。

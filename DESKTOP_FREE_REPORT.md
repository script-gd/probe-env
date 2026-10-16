# 去除 gdcc 的 java.desktop 依赖

## 1. 结论

上一轮 [JRE_REPORT.md](JRE_REPORT.md) 的结论针对“原样保留应用依赖、只做标准模块级 jlink 裁剪”。放宽到 **重建 JDK 模块或修改 FreeMarker** 后，可以进一步缩小运行时，而且不需要 native-image，也不需要修改 `../gdcc`。

本次实现四条路线，并以相同的 gdcc 编译输出做对照：

| 路线 / 产物标识 | FreeMarker | JDK 处理 | 用途与代价 |
| --- | --- | --- | --- |
| `beans-module` | 原版 2.3.34 | 将 `java.desktop` 重建为 230 个类的精简模块 | 最大限度保留原有内省逻辑；需要维护定制运行时，模块名称仍是 `java.desktop` |
| `beans-patch` | 原版 2.3.34 | 不链接 desktop，将同一批类通过 `--patch-module` 放入 `java.base` | 模块列表没有 desktop，但启动依赖特殊 JVM 参数，不是首选分发方案 |
| `freemarker-fork` | 官方 GAE 版 + 自写反射内省 | 普通 jlink，不改 JDK 模块 | 不需要 desktop/beans，但已实证存在 JavaBeans 行为差异，仅作实验候选 |
| `freemarker-openbeans` | 官方 GAE 版 + OpenBeans 类型替换 | 普通 jlink，不改 JDK 模块 | 避免自写内省规则；依赖较老的 Harmony 衍生库，仍是私有 FreeMarker fork |

四条路线均保留 CLI 和 `gdcc serve`。除精简模块路线包含名为 `java.desktop` 的小模块外，其余路线实际只链接：

```text
java.base,java.logging,java.xml,jdk.httpserver
```

没有默认替换上一轮的保守分发方案。实验通过不等于所有 FreeMarker/JavaBeans 功能兼容；正式采用前仍需完整 gdcc 回归和依赖维护评估。

## 2. 真正的依赖闭包

### 2.1 不只是 JavaBeans

标准 FreeMarker 2.3.34 有两组独立的 desktop 引用：

- `BeansWrapper` / `ClassIntrospector` 使用 `java.beans.Introspector`、`BeanInfo`、各类 property/method descriptor。
- `TemplateElement` 实现 `javax.swing.tree.TreeNode`，`Template` 的旧树查询接口引用 `TreePath`。它们是模板 AST 的一部分，普通解析流程就可能加载，不只是 GUI 调试界面。

JDK 自己也有隐藏依赖：`java.beans.BeanDescriptor` 的构造过程读取 `javax.swing.SwingContainer` 注解。因此直接复制 `java.beans/**` 并不构成可用闭包。

本次精简 JDK 路线保留：

```text
java/beans/**                         151 classes
com/sun/beans/**                       76 classes
javax/swing/SwingContainer.class
javax/swing/tree/TreeNode.class
javax/swing/tree/TreePath.class
```

共 230 个类，没有 AWT/Swing 的 GUI 实现和 native 图形库。它是 **gdcc 使用路径所需的 JavaBeans 子集运行时**，不是完整、可替代所有 desktop API 的实现。

### 2.2 为什么换 wrapper 不够

`new Configuration(...)` 内部就会创建默认 wrapper，早于调用者的 `setObjectWrapper(...)`。此外 `SimpleObjectWrapper` 继承 `DefaultObjectWrapper`，后者又继承 `BeansWrapper`，不是不使用 JavaBeans 的替代实现。

官方 `freemarker-gae:2.3.34` 移除了模板核心对 Swing tree API 的依赖，但仍使用 JavaBeans。本次以不带 desktop 的 JRE 执行未经修改的 GAE JAR，确认它仍然失败：

```text
FMSEM_CONFIG=FAIL java.lang.NoClassDefFoundError: java/beans/IntrospectionException
```

所以 GAE 是两种 FreeMarker 改造路线的起点，不是单独的解决方案。

## 3. 精简 JavaBeans 模块

实现：`scripts/beans-runtime.py`、`probes/beans-module/module-info.java`。

### 3.1 为什么仍叫 java.desktop

在原版 JDK 25 中，新建一个名为 `gdcc.beans` 或 `java.beans` 的模块并放入 `java.beans` 类，并不能直接使用。它被分配到 application class loader，启动阶段拒绝定义 `java.*` 包。脚本在每个平台构建并运行反例，要求 boot layer 初始化因 prohibited package name 失败。Linux/macOS 的具体错误为：

```text
java.lang.LayerInstantiationException:
Class loader ... 'app' tried to define prohibited package name: java.beans
```

Windows 同样失败，但具体报出的包名为 `java.beans.beancontext`。

保留 `java.desktop` 名称，则可以使用 JDK 固定的内置 loader 映射，以及 `java.base` 对该模块的内部包 qualified exports。`Introspector` 和 `com.sun.beans.TypeResolver` 确实依赖这些内部包，不能把类随意改装成普通应用模块。

精简描述符为：

```java
module java.desktop {
    requires java.base;
    exports java.beans;
    exports java.beans.beancontext;
    exports javax.swing.tree;
}
```

移除原描述符中的 prefs/datatransfer 依赖，XML 由应用直接加入。最终镜像为 5 个模块，而不是原先的 7 个。

### 3.2 JMOD 与哈希校验

Temurin 基线 JDK 没有内置 `jmods/`，但 **同一 release 提供单独的、匹配 OS/架构的官方 JMOD 下载**。这纠正了初步探索中“没有 JMOD 所以无法重建”的过强结论。

构建步骤：

1. 下载 Temurin `25.0.2+10` 对应平台的 JMOD，校验固化的 SHA-256。
2. 提取 `java.desktop.jmod`，保留上面的 230 个类及原始 legal notices。
3. 用新的模块描述符重建小型 `java.desktop.jmod`。
4. 原 `java.base` 描述符中的 `ModuleHashes` 绑定了原 desktop 的内容，新模块会触发 hash mismatch。本实验从 **工作副本** 中移除该 attribute，重建 base JMOD，其余类和 native 内容不改。
5. 将工作副本的 base、精简 desktop 和其余匹配的原版 JMOD 放在完整 module path 上，进行 jlink。明确提供 base JMOD，避免 JEP 493 runtime-image 模式优先选回原版 desktop。
6. 检查实际模块描述符、230 类计数、native 库清单，并执行真实内省、gdcc、Godot 和 RPC 测试。

`ModuleHashes` 处理是定制模块的真实维护成本，不能隐瞒为“只跑一个普通 jlink 命令”。外部输入 SHA 校验仍保留，但不等于恢复了 JDK 原模块间的运行时哈希绑定。

### 3.3 限制

保留的少数类仍有未执行的 `java.awt` / applet / Swing 引用，例如图标、GUI property editor、XML 持久化委托、Customizer 等。它们不在本次 gdcc 路径中，调用这些功能可能失败。保留所有 beans 类也不意味着实现了完整 JavaBeans 规范。

这是定制 OpenJDK 镜像，未作 TCK 或厂商支持验证；不能把本次测试当作 Java SE 兼容认证。升级 JDK 时必须重新取得匹配 JMOD、重建并验证。

## 4. 将 beans 注入 java.base

`beans-patch` 使用普通 4 模块镜像，把同一批 JDK 类单独放进 `jre/beans-patch.jar`，启动时增加：

```sh
--patch-module java.base=/absolute/path/to/beans-patch.jar \
--add-exports=java.base/java.beans=ALL-UNNAMED \
--add-exports=java.base/javax.swing.tree=ALL-UNNAMED
```

这样类由 bootstrap loader 加载，并能访问 base 内部实现，不需要清除 base JMOD 的哈希 attribute，也不修改安装的 JDK。但它改变了实际解析到的 base 内容，仍是特殊运行时配置。

产物启动器已携带这些参数，并经过含空格路径搬迁验证。**不能把这个 patch 随意加到仍解析完整 `java.desktop` 的普通 JDK 上**，否则可能产生 split-package 启动失败。单独的 JRE archive 必须配合相同参数或整合包内启动器使用，不能把 `beans-patch.jar` 当作普通 classpath 依赖。

原 desktop 的 legal notices 已补入 patch 镜像。复制的 JDK 类仍适用其原许可证，不能把“没有 desktop 模块”误解为“没有再分发 OpenJDK desktop 代码”。

## 5. 修改 FreeMarker

### 5.1 反射内省候选

构建：`scripts/freemarker-no-desktop.py`；源码差异：`patches/freemarker-no-desktop.patch`。

基于官方 GAE 二进制/源码，只修改 `ClassIntrospector`、`BeansWrapper`、`MethodSorter`，增加一个私有 `_BeanPropertyDescriptor`。通过 `Class.getMethods()` 发现 getter、boolean reader、indexed reader，保留原来的方法调用、重载和安全策略等后续逻辑。

`javac --release 8 -implicit:none` 限制编译范围；检查输出类名前缀，确认原 JAR 中未替换的条目字节不变。本次实际替换 11 个已有 class 条目，增加 1 个类，没有重新编译整个 FreeMarker。

该候选通过常用语义对照和 gdcc 流程，但 **明确不等价于 JDK Introspector**：

- 自定义 `FooBeanInfo` 被忽略，测试实证原版可见的重命名属性在 fork 中缺失。
- boolean getter 冲突、继承、indexed/array getter 类型合并等规则只覆盖已测情形，未复现全部 JavaBeans 规范。
- 较老兼容配置中的 `treatDefaultMethodsAsBeanMembers=false` 不保持原行为。
- `MethodAppearanceDecision` 的 property descriptor 类型变化，不能向其他 FreeMarker 消费者声称二进制兼容。
- 带命名属性的 object-builder setting 仍调用原版 `_ObjectBuilderSettingEvaluator` 的 JavaBeans setter 路径。在无 desktop JRE 上，`setSetting("object_wrapper", "DefaultObjectWrapper(2.3.34, exposeFields=true)")` 已实测失败；gdcc 当前使用 typed setters，不经过此路径。
- JSP、调试树等可选部分未全部删除，不能声称整个 JAR 静态零 desktop 引用。

因此它适合说明技术可行性，不是本次首选的工程化方案。

### 5.2 OpenBeans 候选

构建：`scripts/freemarker-openbeans.py`。

使用 `me.champeau.openbeans:openbeans:1.0.2`，包名为 `com.googlecode.openbeans`，源于归档的 Apache Harmony/Google Code OpenBeans。该坐标可从 Maven Central 下载；并非仍由 Apache 维护的新库。

在官方 GAE 源码中，将以下 4 个源文件的 `java.beans.*` 引用改为 OpenBeans：

```text
freemarker/ext/beans/ClassIntrospector.java
freemarker/ext/beans/BeansWrapper.java
freemarker/ext/beans/MethodSorter.java
freemarker/core/_ObjectBuilderSettingEvaluator.java
```

这里不自写 getter 发现算法，继续使用成熟但较老的 Harmony 内省实现；同时覆盖 named-setting 的 setter 路径。只重新编译上述类及 nested classes，实际替换 22 个 class 条目，其他类保持官方 GAE 字节不变。

分发时维持 gdcc 原来引用的 `lib/freemarker-2.3.34.jar` 文件名，并添加同目录的 `openbeans-1.0.2.jar`。修改 FreeMarker manifest：

```text
Class-Path: openbeans-1.0.2.jar
```

因此 gdcc 主 JAR 无需修改，`java -jar` 仍能找到新依赖。测试中不把 OpenBeans 显式加入 `-cp`，并移走 sibling JAR 做负例，确认是 manifest 解析而不是宿主 classpath 掩盖了依赖。

此方案不包含 `java.beans`，不修改 JDK，也不使用 `--patch-module` / `--add-exports`。但仍有边界：

- 它是私有 FreeMarker fork；公开扩展点的 descriptor 类型变为 OpenBeans 类型。
- 自定义 BeanInfo/PropertyEditor 需实现 OpenBeans 的接口，不会自动兼容 JDK `java.beans` 实现。
- Harmony 与当前 JDK 的全部内省规则未证明等价。Java 8 default methods / records 在本次已测模型中由 FreeMarker 自身处理并得到一致结果，不代表所有模型兼容。
- OpenBeans 的 icon、GUI editor、持久化等可选代码仍引用 AWT，不能把整个依赖声明为任意路径 desktop-free。
- 必须维护这个老依赖及其安全/兼容性回归，不应仅因“少量 import 替换”就直接用于正式发布。

两个 FreeMarker 改造方案均保留源文件、补丁或替换记录、输入/输出 SHA-256、许可证信息供复核。OpenBeans 候选还保留原始 source JAR/POM；正式再分发仍需独立许可证审计。

## 6. 验证与体积

验证基线仍为 Temurin `25.0.2+10`、gdcc 已固化编译输出 `457262e8`、FreeMarker `2.3.34`、官方 Godot `4.5.2`。没有修改 gdcc 源码或主 JAR。

脚本 `scripts/desktop-free.py` 对 baseline 加所有候选执行：

1. 精确检查实际模块集合。
2. 对照 URL acronym 属性、boolean getter 冲突、indexed getter、继承 default method、record 属性/方法、重载、varargs、class getter、static method 的模板输出。
3. 调用真实 gdcc 后端编译全部 5 个 C TU，验证冷/热对象缓存，并让 Godot 执行动态库。
4. 从 GDScript 经完整 CLI 生成 C 并编译，比较全部 49 个 `.c` / `.h` 文件的相对路径和 SHA-256，要求与原版 baseline 完全相同。
5. 再次由 Godot 加载 CLI 生成的库，验证整数、字符串、布尔值结果。
6. 实际解压整合分发包至含空格的新路径，通过包内启动器重新编译并运行。
7. 从解压后的 JRE 启动 HTTP RPC 服务并验证 `server.ping` 返回 `pong`。
8. 对 GAE 单独使用、新模块包名、自定义 BeanInfo、named-setting、OpenBeans sibling 缺失进行负例或行为差异验证。

RPC 是服务初始化和一次 ping，不是完整 RPC 编译任务回归。C/header 一致仅覆盖本次 fixture，不代表整个 gdcc 测试全集。JavaBean GUI、复杂 coroutine/HRX 运行行为也不在本次保证范围。

最终 CI：[38863325209](https://github.com/script-gd/probe-env/actions/runs/38863325209)，提交 `5906a0e7c0e7c0a5d7adcd418bec5639f93eda8d`。**Linux x86_64、macOS ARM64、Windows x86_64 的全部 4 个候选均通过**上述核心语义、生成源码对照、后端/CLI/Godot、搬迁分发和 RPC 测试。

首批三个候选的先前运行 [38860272980](https://github.com/script-gd/probe-env/actions/runs/38860272980) 也成功；共用 helper 的改动同时通过了原标准 JRE 工作流 [38860272854](https://github.com/script-gd/probe-env/actions/runs/38860272854) 的三平台回归。

### 6.1 实测体积

单位 MiB = 1,048,576 字节。所有配置都包含 RPC 模块，不能把这里的 4 模块结果与上一轮去掉 RPC 的 CLI 配置混为一谈。标准 baseline 展开体积为 Linux **60.30**、macOS **50.55**、Windows **48.06** MiB。

| 平台 | 候选 | JRE 展开 | JRE 压缩 | 整合分发包压缩 |
| --- | --- | ---: | ---: | ---: |
| Linux x86_64 | beans-module | 45.88 | 25.44 | 32.52 |
| Linux x86_64 | beans-patch | 45.95 | 25.51 | 32.58 |
| Linux x86_64 | freemarker-fork | 45.57 | 25.21 | 32.32 |
| Linux x86_64 | freemarker-openbeans | 45.57 | 25.21 | 33.04 |
| macOS ARM64 | beans-module | 36.29 | 21.04 | 28.12 |
| macOS ARM64 | beans-patch | 36.36 | 21.11 | 28.18 |
| macOS ARM64 | freemarker-fork | 35.98 | 20.81 | 27.92 |
| macOS ARM64 | freemarker-openbeans | 35.98 | 20.81 | 28.64 |
| Windows x86_64 | beans-module | 36.20 | 22.43 | 29.84 |
| Windows x86_64 | beans-patch | 36.27 | 22.50 | 29.90 |
| Windows x86_64 | freemarker-fork | 35.92 | 22.20 | 29.64 |
| Windows x86_64 | freemarker-openbeans | 35.92 | 22.20 | 30.36 |

展开采用与上一轮相同的 `image_bytes` 口径：文件逻辑长度之和，符号链接按目标文件长度计入，不是磁盘块占用。macOS legal notices 存在符号链接；若仅统计 tar 中 regular file payload，上表的 beans-module/patch 分别应为 **36.20 / 36.28** MiB，两个 FreeMarker 路线为 **35.92** MiB。压缩包长度不受这一统计口径影响，均为实际文件长度。

OpenBeans 与反射 fork 使用完全相同的 JRE；整合包稍大是因为额外携带 OpenBeans JAR、源 JAR/POM 和审计材料，而不只是运行所需文件。它们在此测试下比标准 baseline 少约 **12 至 15 MiB** 展开体积。精简模块与完全移除 desktop 的 JRE 差距只有约 **0.3 MiB**，没有理由仅为这点差异牺牲不愿维护的兼容性。

### 6.2 产物与核验

已下载三个 artifact，核验全部 **24 个 `.tar.gz`** 的长度和 SHA-256 与 `results.json` 一致，并检查模块内容、启动器、许可证、依赖 sibling/manifest 以及实际运行日志。

- [Linux x86_64 产物](https://github.com/script-gd/probe-env/actions/runs/38863325209/artifacts/12029597981)
- [macOS ARM64 产物](https://github.com/script-gd/probe-env/actions/runs/38863325209/artifacts/12030072186)
- [Windows x86_64 产物](https://github.com/script-gd/probe-env/actions/runs/38863325209/artifacts/12029819165)

每个 artifact 包含 4 个 `jre-<candidate>-<platform>.tar.gz`、4 个 `gdcc-<candidate>-<platform>.tar.gz`、结果 JSON、日志和 provenance。保留期 30 天，下载可能要求 GitHub 登录。

OpenBeans 替换后的 FreeMarker JAR 在三个平台的 SHA-256 一致：

```text
05fc93478b1e0add11b6962ee4ff358ec17c77592f8ae91e5a1b7c98856cd5d0
```

reflection fork 的 JAR 在 Linux/macOS 是 `0b1c3eba3ee4ce46ae76ad47fb98955c7a324a34ab1d15846635ce72539de20c`，Windows 是 `240e435b021a7c1c18ff8ae775b56a5dee75e48c1609aaed065062ceb171d177`。逐项解压比较确认 class/resource 内容相同，区别仅为新增加类条目的 ZIP `version made by` 宿主标记。故本次不声称所有 JAR 或完整分发包跨 OS bit-for-bit 一致；精确校验应使用对应平台的 provenance / results。

## 7. 复现

完整构建、对照和打包：

```sh
python3 scripts/desktop-free.py --jdk /path/to/temurin-25.0.2+10 \
  --godot /absolute/path/to/Godot
```

Windows 同样用 `python`，参数传本机 JDK 和 Godot `.exe` 路径。构建阶段需要 Python 3.12、Git、目标架构 Temurin JDK；Linux/macOS native TinyCC 构建工具和系统头文件/SDK 要求与上一轮报告相同。只在工作目录中下载和构建，不安装系统级软件。

也可分别构建：

```sh
python3 scripts/beans-runtime.py --jdk /path/to/jdk --out build/my-beans --negative-test
python3 scripts/freemarker-no-desktop.py --jdk /path/to/jdk --out build/my-fm-reflection
python3 scripts/freemarker-openbeans.py --jdk /path/to/jdk --out build/my-fm-openbeans
```

所有构建拒绝覆盖已有输出。`beans-runtime.py` 为保证 JMOD/类实现匹配，固定检查 Temurin `25.0.2+10`；升级时必须更新 pin 和重新验证，不能任意混合不同补丁版本。该历史版本仅为研究基线，不是长期锁住旧安全补丁的建议。

FreeMarker 两条路线使用标准运行时：

```sh
jlink --add-modules java.base,java.logging,java.xml,jdk.httpserver \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output jre
```

命令行运行继续使用 `--enable-native-access=ALL-UNNAMED --illegal-native-access=deny`。省略 RPC 时可去掉 `jdk.httpserver`；本次交付和测量的所有候选保留该模块。

工作流为 `.github/workflows/desktop-free.yml`，输出目录 `build/desktop-free/<platform>/`。每个候选提供独立 JRE archive 和包含 gdcc、依赖、TinyCC、启动器、审计材料的整合 archive。`beans-patch` 的原始 JRE archive 仍需特殊启动参数，通常应直接使用其整合包启动器。

## 8. 选择建议

1. **要求不改变 FreeMarker 内省实现**：优先继续评估 `beans-module`，换取明确的定制 JDK 维护成本。它虽然名称仍是 desktop，实际已经移除 GUI/native 图形部分。
2. **要求不修改 JDK、允许私有应用依赖 fork**：优先进一步验证 `freemarker-openbeans`，再决定维护旧 OpenBeans、移植必要修复或向上游推进可插拔内省机制。它比本次手写反射候选更值得工程化，但尚未达到正式发布准入。
3. **仅作可行性或尺寸下界对照**：保留 `beans-patch` 和 `freemarker-fork`。前者额外侵入 JVM 启动配置，后者已知兼容性差异更明显。
4. 若节省的十余 MiB 不足以覆盖上述维护成本，上一轮完整 desktop 的标准 jlink 方案仍是最保守的选择。

本次没有将任何实验补丁应用到 `../gdcc`，也没有改变原始 `runtime-input`。原有 Linux/macOS C 头文件、CRT/SDK 依赖仍然存在；JRE 再瘦身不等于 TinyCC 工具链完全无宿主依赖。

## 9. 依据

- [FreeMarker 官方下载与 GAE 变体](https://freemarker.apache.org/freemarkerdownload.html)
- [FreeMarker BeansWrapper 手册](https://freemarker.apache.org/docs/pgui_misc_beanwrapper.html)
- [jmod 文档与 module hash 机制](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jmod.html)
- [JEP 493：runtime image linking](https://openjdk.org/jeps/493)
- [Temurin 25.0.2+10 JDK/JMOD release](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.2%2B10)
- [OpenBeans 源码归档](https://github.com/melix/openbeans)、[Maven Central 坐标](https://central.sonatype.com/artifact/me.champeau.openbeans/openbeans)
- 实际 class/source、module descriptor、输入校验、负例和运行日志均由提交内脚本产生；不依赖子代理对“不可行”或“兼容”的未核验断言。

# gdcc 最小 JRE 调研

后续关于精简 JavaBeans 模块、`java.base` patch 和 FreeMarker 去依赖的实测研究见 [DESKTOP_FREE_REPORT.md](DESKTOP_FREE_REPORT.md)。本报告保留原版依赖、标准模块级裁剪的基线结果。

## 1. 目标与结论边界

目标是在 **不使用 native-image、不复制整个 gdcc 工程、不要求用户安装完整 JDK** 的条件下，运行 gdcc 的 TinyCC 后端，并将完整的 gdcc C 工程编译成动态库，交给真实 Godot 执行。

方案是 **Temurin Java 25 HotSpot + jlink 模块裁剪 + 原样保留的 gdcc JAR/依赖 + 对应平台的 libtcc bundle**。这不是 Android ART/D8 方案，也不是 Java 源码启动模式。

本报告的“最小”指针对当前编译产物和已验证用途的、使用标准 `jlink` 模块裁剪得到的精简配置，不声称在任意 gdcc 功能、任意 JDK 实现或任意按 class 删除的非标准方案中达到理论最小值。

| 配置 | 根模块 | 用途 |
| --- | --- | --- |
| `base` | `java.base` | 通过 gdcc 的真实 `TinyCcCompiler` API 编译已经生成的完整 C 工程；不能运行完整 GDScript 代码生成 CLI |
| `cli` | `java.base,java.desktop,java.logging` | GDScript 解析、语义处理、FreeMarker C 代码生成、TinyCC 编译链接；不包含 HTTP RPC 服务 |
| `full` | `java.base,java.desktop,java.logging,jdk.httpserver` | CLI 加 `gdcc serve`，也是可直接运行的整合分发包采用的配置 |

`cli` 实际包含 6 个模块：`java.base,java.datatransfer,java.desktop,java.logging,java.prefs,java.xml`。`full` 再增加 `jdk.httpserver`，共 7 个。`java.lang.foreign` 已属于 `java.base`，不需要 `jdk.incubator.foreign`，也不需要为运行 gdcc 携带 `javac` 或 `jdk.compiler`。

## 2. 输入与可追溯性

本次分支：`research/minimal-jre`。未修改 `../gdcc`，未运行其 Gradle 构建，没有复制它的 Java 源码、测试源码、Gradle 工程或 Zig 启动器。

使用的编译输出已固化在 `runtime-input/`：

- 主 JAR 由现有 `build/classes/java/main`、`build/resources/main` 和 `build/tmp/jar/MANIFEST.MF` 直接重新封装，没有重新编译 `.java`。
- 8 个运行依赖 JAR 从现有 `build/libs/lib` 原样复制，包括 gdparser、tree-sitter、FreeMarker、Gson、picocli、SLF4J 等。
- Windows libtcc bundle 是现有 `build/tinycc-bundle/windows-x86_64` 的编译产物，含头文件、运行库、import definitions、许可证和完整性清单。
- Linux/macOS libtcc 从本仓库已有的 `vendor/tinycc` 在对应原生 runner 上构建，不需要克隆 gdcc。TinyCC pin 为 `43c7708b85681a2fd4451c8a541af4494a8919b2`。
- `runtime-input/snapshot.json` 记录输入文件 SHA-256；`.gitattributes` 禁止 Windows 对这些输入进行换行转换。

**不能把采集时的 checkout HEAD 当作 JAR 构建版本。** 采集时 gdcc HEAD 是 `3136c45182c4c327024ec677d150ba21e0588771`，而现有较新 class/resource 输出的内嵌版本是 `457262e8`，两者分别记录。最初检查到的 `build/libs/gdcc-0.0.2.jar` 内嵌版本为 `8e18a1ca`，缺少 macOS 支持和对象缓存，已排除。通过实际 class 文件检查和冷/热缓存测试确认采用的封装包含新后端能力，而不是只查看当前源码后假定 JAR 与之相同。

重新封装的主 JAR SHA-256：

```text
b90b75f8351542dae019ee10121a87d847b849b306c8f57c78dbd3c0b06fcebf
```

## 3. 为什么不是只保留 java.base

FFM、文件操作、线程、SHA-256 和 libtcc 下调用本身只需要 `java.base`。实测 `base` 可以通过真正的 gdcc 后端编译全部 5 个 C 翻译单元，并让 Godot 成功调用生成类的方法。

但完整 gdcc CLI 还运行 FreeMarker 模板生成逻辑。对照测试实际得到：

```text
# 省略 java.logging：
NoClassDefFoundError: java/util/logging/Logger
  at freemarker.log._JULLoggerFactory.getLogger(...)

# 保留日志和 XML，但省略 java.desktop：
NoClassDefFoundError: java/beans/IntrospectionException
  at freemarker.ext.beans.BeansWrapper.<init>(...)
```

这里的 `java.desktop` 不是因为 gdcc 需要 GUI，而是 FreeMarker 默认 object wrapper 需要 JavaBeans。模块的传递依赖同时带入 `java.datatransfer`、`java.prefs` 和 `java.xml`。因此不能用 `--help`、`--version` 能启动，来证明 JRE 能运行代码生成流程。

初步推测的 `java.base,java.xml,jdk.httpserver` 已经实际运行过，也因缺失日志模块失败，不作为推荐配置。`cli` 去掉 HTTP 模块后仍需完整编译和 Godot 验证；只有服务端模式需要再补上 `jdk.httpserver`。

没有通过替换 FreeMarker wrapper、修改 gdcc、删除 jar 内的类、切换日志实现或建立自定义 OpenJDK 来追求更小体积。这些会改变问题范围，需要另行验证。尤其不能因为“headless”就直接删除整个 `java.desktop`。

## 4. jdeps 与模块化陷阱

gdcc 的 `module-info.class` 依赖只在编译期提供、未随运行依赖发布的 `org.jetbrains.annotations`，并涉及第三方自动模块。直接拿应用模块去 `jlink` 或 `java -m` 不适合这个现有分发形态。

本方案只把 **JDK 模块** 链入 JRE，应用和依赖保持 `java -jar` / classpath 启动，不修改这些 JAR。

为取得可复查的静态分析证据，脚本将主 JAR 的 class 文件解到临时分析目录，排除模块描述符，再用运行依赖 classpath 执行 `jdeps`。原始分发 JAR 保持不变。扫描结果为：

```text
java.base,java.compiler,java.desktop,java.rmi,java.sql,jdk.httpserver
```

它不是最终必须逐一加入的模块列表：部分第三方功能是可选路径，`java.logging`、`java.xml` 又可在传递模块闭包中出现。完整 CLI 实测不需要打包 `java.compiler`、`java.rmi` 或 `java.sql`。反射、服务发现和动态 native 库加载也不可能仅凭静态扫描完全验证。

结论来自 `jdeps` 加真实编译/运行和缺模块对照，不能只来自 `jdeps --ignore-missing-deps` 的一次输出。

## 5. 实际验证内容

脚本：`scripts/minimal-jre.py`；真实后端入口探针：`probes/GdccRuntimeProbe.java`。

每种 JRE 执行以下后端测试，不使用上一轮独立实现的 `Compile.java` 代替 gdcc：

1. 在完整 JDK 中将很小的测试驱动预编译为 Java 25 class；JRE 只执行 class，不现场编译 Java。
2. 从现有 `fixture/` 复制完整生成项目，调用 JAR 中的 `TinyCcCompiler.compile()`。
3. 编译 `entry.c`、`godot_binding.c`、`minicoro.c`、`gdcc_coroutine.c` 和 `gdcc_hrx.c`，包含完整运行时头文件。
4. 使用隔离对象缓存，确认首轮 `0 hits, 5 misses`，第二轮 `5 hits, 0 misses`。
5. 在 Godot 4.5.2 官方 headless 二进制中加载该动态库，确认注册类存在，执行 Variant 字符串格式化，验证整数、字符串和布尔值三种输出。

`cli` / `full` 另外执行：

1. 直接调用同一 JRE 的 `java -jar ... --backend tcc`，从 `fixture/source.gd` 重新生成完整 C 工程并编译。
2. 将新生成的动态库放进独立 Godot 工程，再次加载运行，不复用后端探针的动态库。
3. 仅当进程退出码为 0 且日志存在完整独立行 `GDCC_PROJECT_PASS` 时判为通过。

`full` 整合分发包另外执行：

1. 打包为 `.tar.gz` 后，实际解压到名称含空格的新路径。
2. 从解压后的 JRE 检查版本，再通过包内 `gdcc.sh` / `gdcc.cmd` 启动器进行真实 CLI 编译。
3. 将搬迁后编译产生的动态库再次交给 Godot 验证。
4. 使用搬迁后 JRE 启动 `gdcc serve`，向 loopback `/rpc` 发送 `server.ping`，断言 JSON-RPC 返回 `pong`。

所有 Java 应用测试使用：

```text
--enable-native-access=ALL-UNNAMED --illegal-native-access=deny
```

因此 native 权限测试不是依靠 Java 25 当前“警告后继续”的默认行为。包内两个启动器也使用相同选项。镜像没有 `javac`；运行时不查找系统 Java，也不使用 Zig 编译生成项目。

关键成功标记：

```text
JRE_GDCC_BACKEND_PASS::round=0::tu=5
JRE_GDCC_BACKEND_PASS::round=1::tu=5
GDCC_CASE_PASS::value=42
GDCC_CASE_PASS::value=ARM64
GDCC_CASE_PASS::value=true
GDCC_PROJECT_PASS
JRE_GDCC_RPC_PASS
MINIMAL_JRE_SUITE_PASS::<platform>
```

这验证的是一个完整的小型生成工程，不是整个 gdcc 单元测试全集，也不代表已经执行 coroutine 挂起恢复或复杂 HRX 热重载。

## 6. 跨平台结果与体积

固定基线：Temurin `25.0.2+10`、Godot `4.5.2-stable`。CI 的 `setup-java` 版本字符串须写作 `25.0.2+10.0.LTS`；初次使用 `25.0.2+10` 时 action 的版本匹配失败，没有进入应用测试。Godot 下载按工作流中固化的官方 SHA-512 校验。

完整验证运行：[38786229549](https://github.com/script-gd/probe-env/actions/runs/38786229549)，代码提交 `11e78b5f632dc30f188dd283946d78cd5dc6dbc4`。**三个原生平台 job 全部成功**，不是交叉编译后只检查文件格式。

| 平台 | Runner | 真实后端 + 冷/热缓存 + Godot | 6 模块 CLI + Godot | 7 模块 CLI + Godot | 解压搬迁启动器 + Godot / RPC |
| --- | --- | --- | --- | --- | --- |
| Linux x86_64 | `ubuntu-24.04` | PASS，含 `base` | PASS | PASS | PASS / PASS |
| macOS ARM64 | `macos-15` | PASS，含 `base` | PASS | PASS | PASS / PASS |
| Windows x86_64 | `windows-2025` | PASS，含 `base` | PASS | PASS | PASS / PASS |

体积按 **MiB = 1,048,576 字节**，`展开 / 压缩` 中压缩指 `.tar.gz`。展开大小是文件逻辑长度之和，不是 `du` 的块占用。最后一列包含完整 `full` JRE、gdcc、8 个依赖 JAR、libtcc bundle 和启动器，不包含 Godot。

| 平台 | `base` JRE 展开 / 压缩 | `cli` JRE 展开 / 压缩 | `full` JRE 展开 / 压缩 | 整合 gdcc 包压缩 |
| --- | --- | --- | --- | --- |
| Linux x86_64 | 41.15 / 21.08 | 60.14 / 36.05 | 60.30 / 36.16 | 43.23 |
| macOS ARM64 | 31.50 / 16.67 | 50.34 / 31.12 | 50.55 / 31.25 | 38.32 |
| Windows x86_64 | 31.54 / 18.07 | 47.92 / 31.60 | 48.06 / 31.72 | 39.12 |

实际多保留 HTTP RPC 支持只增加约 0.14 至 0.21 MiB 展开体积，因此一般分发建议使用 `full`，不必为了这点差异牺牲编辑器服务能力。

已下载三个 artifact，逐一核对全部 12 个 `.tar.gz` 的长度和 SHA-256，并检查 archive 内确实有 `bin/java`、`lib/modules`、`legal/`，没有 `javac` / `native-image`。三个分发包都没有 gdcc Java 源码。日志中的三种方法输出、冷/热缓存、搬迁编译、Godot PASS 和 JSON-RPC `pong` 也已核对，不仅依靠 workflow 的绿色状态。

可下载产物：

- [Linux x86_64](https://github.com/script-gd/probe-env/actions/runs/38786229549/artifacts/11997823041)
- [macOS ARM64](https://github.com/script-gd/probe-env/actions/runs/38786229549/artifacts/11998496465)
- [Windows x86_64](https://github.com/script-gd/probe-env/actions/runs/38786229549/artifacts/11997992702)

各 artifact 的 `build/minimal-jre/<platform>/` 下包含 3 个独立 JRE archive、1 个整合分发 archive、`results.json` 和测试日志。GitHub 下载可能要求登录，artifact 保留 30 天。

`jre-cli.tar.gz` / `jre-full.tar.gz` 的 SHA-256：

```text
linux-x86_64 cli  7cd4301dc9ccd9d49e433725f2077a5ec9fbeb39d130e78889d725fffccb0e3d
linux-x86_64 full dfd6f549b3842cebc9834d3e636024fa7c8dfbe21a22056b134d324463f4732e
macos-aarch64 cli  c4f7077987ac8d904d247a1fce84b6878bcbd2e9b8fa2a99928d1dc69792bbfc
macos-aarch64 full 91d212c651d77cc3dd99677f1a0187922dfd53351d74374f702c83c9db1db611
windows-x86_64 cli  00a1dc2e8bf3bd2a4f5c7f2d9815e97b721acbcf0c6e76451e2d7ef712315a42
windows-x86_64 full 520415370d59b351548164274617a802d8f6c288968ea57eefb46aa87a37f8df
```

## 7. 复现与使用

### 7.1 只构建 JRE

在目标 OS/架构的 Temurin JDK 25 上执行，不需要 gdcc 源码：

```sh
# 完整 CLI 使用的精简 JRE
jlink --add-modules java.base,java.desktop,java.logging \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output jre-cli

# 若还要支持 gdcc serve
jlink --add-modules java.base,java.desktop,java.logging,jdk.httpserver \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output jre-full

# 仅通过 API 编译既有 C 工程的最小后端运行时
jlink --add-modules java.base \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output jre-base
```

不要加 `--strip-native-commands`，否则会连 `bin/java` 一起去掉。保留 `legal/`、`release`、安全配置、native 库和 `bin/java`。没有手工删除这些内容。

Temurin 基线启用了 JEP 493，可从自身 runtime image 链接而没有 `jmods/`。不要强行添加一个不存在的 `$JAVA_HOME/jmods` 路径。`jlink --help` 可以确认是否支持该能力；其他 JDK vendor 可能仍需要自己的 JMOD。原生 runner 分别构建可避免跨 OS/架构拼装错误。

### 7.2 构建并测试全部产物

使用本分支已提交的输入，不需要访问 `../gdcc`：

```sh
# Linux/macOS：JAVA_HOME 指向相应架构的 Temurin 25 JDK，另有 Python 3.12 和 Godot
python3 scripts/minimal-jre.py build --godot /absolute/path/to/Godot

# Windows PowerShell
python scripts/minimal-jre.py build --godot C:\tools\Godot_v4.5.2-stable_win64.exe
```

脚本也支持 `--jdk <JDK目录>`。Linux 打包阶段需要本机 C 编译器、make、nm、file、ldd 和 sha256sum；macOS 需要 Xcode CLT/SDK，以及 clang、make、nm、otool、codesign 和 shasum。这些是 **打包/验证阶段** 工具。Windows 使用固化的已编译 bundle，不需要 MSVC、MinGW 或 Zig 来运行本探针。

输出在 `build/minimal-jre/<platform>/`，包括 `jre-base.tar.gz`、`jre-cli.tar.gz`、`jre-full.tar.gz`、`gdcc-<platform>.tar.gz`、模块列表、`jdeps` 输出、Godot 日志和 `results.json`。已有输出目录时脚本拒绝覆盖；复跑可显式移除这一本次生成目录。Linux/macOS bundle 构建也拒绝覆盖外部指定输出路径。

也可在 GitHub 上触发 **Minimal gdcc JRE**，其矩阵是 Linux x86_64、macOS ARM64、Windows x86_64。对应 workflow 为 `.github/workflows/minimal-jre.yml`。artifact 保留 30 天，过期后仍可从提交内输入和脚本重新构建。

```sh
gh workflow run minimal-jre.yml --ref research/minimal-jre
gh run download 38786229549 --name minimal-jre-linux-x86_64 --dir build/jre-ci-artifacts
```

### 7.3 使用分发包

解压 `gdcc-<platform>.tar.gz`，目录形态为：

```text
gdcc-<platform>/
  gdcc.sh / gdcc.cmd
  gdcc-0.0.2.jar
  lib/*.jar
  jre/bin/java[.exe]
  jre/lib/...
  jre/legal/...
  tinycc/bin/libtcc.so|libtcc.dylib|libtcc.dll
  tinycc/include/...
  tinycc/libtcc1.a              # Linux/macOS
  tinycc/lib/libtcc1.a,*.def    # Windows
  tinycc/VERSION,MANIFEST,COPYING,RELICENSING
  GDCC-LICENSE
```

```sh
./gdcc.sh --backend tcc -o /path/to/output/demo /path/to/input.gd
./gdcc.sh serve --host 127.0.0.1 --port 6099
```

```bat
gdcc.cmd --backend tcc -o C:\output\demo C:\project\input.gd
gdcc.cmd serve --host 127.0.0.1 --port 6099
```

只取 `jre-cli.tar.gz` 时，把解压后的 JRE 与 `runtime-input/app` 及对应 bundle 组合，并设置 `GDCC_TINYCC_HOME`，使用 `jre/bin/java --enable-native-access=ALL-UNNAMED --illegal-native-access=deny -jar ...` 启动即可。没有额外依赖 Python 或完整 JDK。

## 8. 分发限制与后续建议

- **JRE 自带不等于 C 工具链 sysroot 自带。** Linux bundle 的系统头文件、glibc/CRT 和搜索路径仍依赖兼容的宿主开发环境；macOS bundle 依赖构建时探测的 Xcode CLT/SDK 路径。CI runner 已安装这些内容。本次没有声称在干净、无开发包的新机器上做到完全独立编译。
- macOS 的 libtcc 强制重新链接为 two-level namespace，并检查 `otool` 和 ad-hoc 签名。这是避免其全局符号在 JVM 中误绑定的必要步骤。生成/搬迁在同一台 runner 上验证，不等于跨 SDK 或 Gatekeeper notarization 的发布验证。
- 当前 gdcc TinyCC 平台列表包含 Linux x86_64/AArch64、Windows x86_64、macOS AArch64。此次三平台矩阵不意味着支持 macOS x86_64、Windows ARM64 或 Android；这些不能只靠换一个 JRE 就获得支持。
- 所有第三方 JAR 均保留，没有通过去掉 Linux/macOS/Windows parser native 资源来缩小应用。解析器可能在可写的 `lib/native` 或临时目录解压 native 库；构建脚本在工作副本中运行，避免污染提交的 snapshot。
- 使用普通 classpath 保留动态加载和反射行为，不需要 native-image 的 reachability 配置。JRE 升级、依赖升级或新增 gdcc 功能后应重跑真实编译与 RPC 测试，不能仅复用旧模块列表。
- 当前固定 JDK 用于可复现研究，不代表应在正式发布中长期锁住旧安全补丁。生产分发应升级到受维护的 Java 25 补丁版本并重新验证。
- `--strip-debug` 会影响 Java 栈的行号信息；要更好的诊断可不使用它，代价是体积增加。没有移除 HotSpot JIT 或改用定制 minimal VM；启动时间、峰值 RSS 和编译吞吐不在本次结论内。
- JRE 的 `legal/` 及 gdcc/TinyCC 的许可证均保留。正式发布仍应审计所有依赖的 notices、LGPL/GPL 对应源码与获取方式、JDK vendor 再分发要求；本探针不是完整的许可证合规认证。
- archive 元数据归零并记录了 SHA-256，但不同 runner、C 工具链、JDK 打包内容和代码签名可能产生不同字节。这里提供的是可复现步骤与功能验证，不宣称整个分发包跨 runner bit-for-bit 相同。

## 9. 参考依据

- [jlink Java 25 文档](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html)：模块闭包、裁剪、服务绑定；压缩选项以实测 `jlink --help` 的 `zip-6` 为准，旧页面仍列出已过时数字别名。
- [jdeps Java 25 文档](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeps.html)：静态依赖分析与缺依赖处理。
- [JEP 454](https://openjdk.org/jeps/454)：FFM 位于 `java.base`，支持 native linker、downcall/upcall。
- [JEP 472](https://openjdk.org/jeps/472)：native access 的显式授权与限制方向。
- [JEP 493](https://openjdk.org/jeps/493)：无 JMOD 的 runtime linking、vendor 开关及跨平台限制。
- 原始 gdcc 文件核验：`build.gradle.kts`、`module-info.java`、`TinyCcCompiler.java`、`TinyCcBundle.java`、`CProjectBuilder.java`、`TemplateLoader.java`；实际产物另用 class 检查和运行结果复核，未将当前源码视为旧 JAR 行为的证据。

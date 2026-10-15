# TinyCC / gdcc Android ARM64 调研与实测

日期：2026-10-15。实验分支：`research/android-arm64-tinycc`。

## 1. 结论

**可以，本轮在 Android 模拟器的 ARM 转译环境中完成了验证。gdcc 固定版本的 TinyCC 能将一个完整的 gdcc C 输出项目编译为 Android ARM64 动态库，并在官方 Godot Android 编辑器中注册类、实例化并正确执行。** 同一项目也能在 Android 编辑器的 app 进程内，由 Android-hosted libtcc 重新编译、链接、`dlopen` 后执行，不必通过启动编译器子进程实现。

但必须区分三个层面：

| 问题 | 本轮结论 |
| --- | --- |
| TinyCC 的 ARM64 代码生成与 Android ELF 链接能否工作 | 已实测通过，无需新增 TinyCC 编译器源码补丁 |
| 完整生成项目及 C helper 能否在 Android Godot 编辑器中运行 | 已实测通过，包括真实 `@tool EditorPlugin`，不是桌面 Godot，也不是仅导出的游戏 |
| 当前 gdcc Java TinyCC 后端是否已经支持 Android | **没有**。平台门禁、bundle、编译选项、宿主/目标选择和 Android 调用桥仍需接入 |
| 是否已验证原生 ARM64 手机和 16 KB 页设备 | **没有**。ARM64 APK 在 x86_64 Android 模拟器的 ARM 转译层执行；实际页大小为 4096。16 KB 仅验证 ELF 对齐 |

**最终通过的 CI：** [Android TinyCC / 38735984841](https://github.com/script-gd/probe-env/actions/runs/38735984841)，测试代码提交 `5c96b7810bf36f47263a40151ca9bb3edab47bf7`。`android (arm64-v8a)` 和 `android (x86_64)` 两个 job 均成功。

没有安装本地 Android SDK/NDK、模拟器或系统包；重工具链及运行测试全部在 GitHub Actions 的临时 runner 内完成。没有修改 `../gdcc`，没有复制整个 gdcc 项目，没有修改本轮的 `vendor/tinycc` 或 `fixture`。

## 2. 输入与验证范围

### 2.1 固定输入

| 项目 | 版本 / 来源 |
| --- | --- |
| 被审查的当前 gdcc | `3136c45182c4c327024ec677d150ba21e0588771` |
| vendored TinyCC | `0.9.28rc`，upstream pin `43c7708b85681a2fd4451c8a541af4494a8919b2`，保留 gdcc 的 `u8` 补丁 |
| TinyCC 导出来源 | 上轮已裁剪的导出，gdcc `13035f94751dbb1b54205040ff4681cea0d2167d`；见 [vendor/README.md](vendor/README.md) |
| C 项目 | 上轮生成并保留的完整 `fixture/`，源脚本为 `fixture/source.gd`，Godot C binding 为 4.5.1 |
| Android NDK | r28b，SDK package `28.1.13356709` |
| 编译目标 API | 28，显式 `__ANDROID_API__=28`，使用该 API 的 NDK CRT 和链接 stub |
| Android 编辑器 | 官方 `Godot_v4.5.2-stable_android_editor.apk`，`4.5.2.stable.official.6ce3de25a` |
| APK 元数据 | 实测 `minSdk=24`、`targetSdk=35`；本实验没有验证扩展库在 API 24 至 27 上运行 |
| 最终运行系统 | Android 15 / API 35，Google APIs x86_64 模拟器，4 GB RAM，SwiftShader，真实 GUI editor 模式 |
| ARM64 执行方式 | `adb install --abi arm64-v8a`，`primaryCpuAbi=arm64-v8a`，`libndk_translation.so`，版本 `0.2.3` |

本轮不是重新跑 Java 前端生成 C，而是重编译已有的完整输出。五个编译单元全部输入 TinyCC，没有用手写 `hello.c` 代替生成项目：

```text
fixture/entry.c
fixture/include/godot/godot_binding.c
fixture/include/gdcc/minicoro.c
fixture/include/gdcc/gdcc_coroutine.c
fixture/include/gdcc/gdcc_hrx.c
```

生成类 `StringFormatVariantOperand` 继承 `Node`，`render(value)` 使用真实 Godot Variant / String 运算返回 `"value=%s" % value`。测试覆盖整数 `42`、字符串 `"ARM64"`、布尔值 `true`，包含类注册、对象创建、GDScript 到原生方法调用、返回值和对象释放。

只覆盖 64 位 ABI。现有 `godot_builtin_sizes.h` 对 32 位指针直接报错，不能把本结果外推到 armeabi-v7a；单个完整小项目也不等于 gdcc 全语言测试集已经通过 Android 验证。

**与当前 gdcc helper 的差异已核验：** 对比 `fixture/include` 与当前 `src/main/c/codegen/include_451`，只有 `gdcc_call.h`、`gdcc_hrx.c`、`minicoro.h` 三个文件不同。前两个差异只有注释；`minicoro.h` 除注释外，fixture 对所有 TinyCC 非 Windows 目标启用 pthread-key TLS，当前 gdcc 仅对 aarch64 启用。**因此 ARM64 路径语义一致；x86_64 成功不能原样外推为当前 gdcc 的 Android x86_64 helper 已兼容。**

### 2.2 两条独立编译路径

**路径 A：桌面交叉编译。** 用宿主 `cc` 构建“运行于 Linux x86_64、目标固定为 Android ARM64”的 libtcc 和 CLI。`probes/android_compile.c` 调用 libtcc API，依次加入完整五个 TU，输出 Android `.so`。NDK Clang 不编译这个项目。

**路径 B：Android app 进程内编译。** 用 NDK Clang 构建 Android-hosted libtcc，以及小型 GDExtension bootstrap 测试桥。官方编辑器加载 bootstrap 后，桥调用同一个编译驱动，把完整 fixture 编译到 app 内部 cache，再 `dlopen` 并转交生成库的真实 `gdextension_entry`。最终仍由编辑器插件验证生成类的行为。

路径 B 的 bootstrap 静态链接 `libtcc.a`；脚本另外产出 Android `libtcc.so` 和 CLI，但**没有在此路径验证 Java FFM 加载 `libtcc.so`**。Clang 只负责构建编译器和测试桥；两个路径中的生成项目都由 TinyCC 编译和链接，没有 Clang fallback。

## 3. 实测证据

### 3.1 Android ARM64 编辑器，桌面交叉编译产物

最终 artifact `android-arm64-v8a/logcat-cross.txt`：

```text
12:39:29.241 I godot : Copying library /storage/emulated/0/probe-env/bin/libprobe.so
12:39:29.271 I godot : Opening library /data/data/org.godotengine.editor.v4/cache/dynamic_libraries/storage/emulated/0/probe-env/bin/libprobe.so
12:44:01.944 I godot : ANDROID_FEATURES::Android::arm64::editor_hint=true
12:44:01.946 I godot : GDCC_CASE_PASS::value=42
12:44:01.947 I godot : GDCC_CASE_PASS::value=ARM64
12:44:01.947 I godot : GDCC_CASE_PASS::value=true
12:44:01.947 I godot : ANDROID_EDITOR_RESULT::PASS
```

插件是 `@tool extends EditorPlugin`，由项目设置自动启用，在 `_enter_tree()` 中执行断言，并要求 `Engine.is_editor_hint()` 为真。没有通过在桌面 Godot 运行或改成非 editor 模式来取得这个结果。

### 3.2 Android ARM64 编辑器，设备进程内重新编译

同一 artifact `logcat-device.txt`：

```text
12:44:31.780 I godot : ANDROID_HELPER_PASS::coroutine::32
12:44:31.786 I godot : ANDROID_HELPER_PASS::hrx::1
12:44:33.028 I godot : ANDROID_DEVICE_COMPILE_PASS
12:46:51.973 I godot : ANDROID_FEATURES::Android::arm64::editor_hint=true
12:46:51.974 I godot : GDCC_CASE_PASS::value=42
12:46:51.974 I godot : GDCC_CASE_PASS::value=ARM64
12:46:51.975 I godot : GDCC_CASE_PASS::value=true
12:46:51.975 I godot : ANDROID_EDITOR_RESULT::PASS
```

`ANDROID_DEVICE_COMPILE_PASS` 只在 `tcc_output_file()` 成功后产生；仅此标记不会使测试通过，还必须有生成类的三个断言和 editor PASS。

helper 的额外测试也在同一个 Android app 进程内执行：

- `libcoroutine.so` 由 TinyCC 编译，每轮两个 pthread 并发执行协程，验证 `mco_running()` 隔离、resume/yield 和销毁，进行 32 轮 `dlopen` / 调用 / `dlclose`。
- `libhrx.so` 由 TinyCC 编译，使用已有 fake-engine 测试，执行真实 RW 分配、RX 切换、指令缓存刷新及可执行 thunk 调用，验证模拟代际切换后的函数指针稳定性。
- 这不是“真实 Godot 中跨多个 DSO 的完整 hot reload”测试，也没有证明每次 `dlclose` 后映像一定已被系统卸载。本轮不继承上轮在其他操作系统上的卸载结论。

x86_64 Android 对照组两条路径及两个 helper 测试也全部通过，日志中为 `Android::x86_64::editor_hint=true`。

### 3.3 ELF 检查

ARM64 `libprobe.so` 的实测结果：

```text
Class: ELF64
Type: DYN
Machine: AArch64
PT_LOAD align: 0x4000 (所有 LOAD)
DT_NEEDED: libm.so, libc.so
DT_SONAME: libprobe.so
DT_TEXTREL: 不存在
PT_TLS: 不存在
RWE LOAD: 不存在
```

`libcoroutine.so` 也断言无 `PT_TLS`。主库的 SHA-256：

```text
62fb60a4182864083a29613e72508d81df222606c409a801ed6a190e0ec33f98
```

完整 ELF headers / relocations / symbols 在 artifact 的 `arm64-v8a/elf.txt`。`getpagesize@LIBC`、`pthread_key_*@LIBC`、`mprotect@LIBC` 均通过 Bionic 解析，没有 `libc.so.6`、`libpthread.so`、`ld-linux` 或桌面 NDK 路径作为运行时依赖。

这些 ELF 检查针对桌面交叉生成的主库。设备重新生成的库已成功加载执行，但当前脚本没有从官方非 debuggable APK 的私有 cache 导出该文件另做离线 ELF 检查。

## 4. TinyCC 工具链所需调整

### 4.1 宿主和目标必须分别配置

`--cpu=arm64 --targetos=Android` 选择目标代码生成器与 Android CRT 协议；`--cc=cc` 或 `--cc=<NDK aarch64 clang>` 决定编译器自身在哪个平台运行。

**“libtcc 的目标在构建时固定”不等于“TinyCC 不能交叉编译”。** 本实验已通过桌面交叉编译；不能做的是拿现有 Linux-target libtcc，在 `TCCState` 中简单切换成 Android-target。gdcc 当前的 host-native 限制是其 bundle/驱动策略，不是 TinyCC 完全没有交叉编译能力。

Android 目标需要的核心配置为：

```text
TCC_TARGET_ARM64=1
TARGETOS_ANDROID=1
CONFIG_TRIPLET="aarch64-linux-android"
CONFIG_TCC_ELFINTERP="/system/bin/linker64"
CONFIG_TCC_SWITCHES="-Wl,-section-alignment=0x4000"
```

本轮没有修改 `arm64-gen.c`、`arm64-link.c` 或 `tccelf.c`。固定源码已包含 Android CRT、ARM64 GOT/PLT 和所需 ELF 动态重定位支持。

### 4.2 NDK sysroot、TinyCC 头文件与 CRT 都要带齐

Android 的 Bionic 与桌面 Linux glibc 不是可互换的 libc。不能沿用上轮 `aarch64-linux-gnu` CRT、`libc.so.6` 或 `/usr/include`。

最小工具链数据面：

| 内容 | 用途 |
| --- | --- |
| TinyCC `include/` | `stddef.h`、`stdarg.h` 等编译器内建头文件，不能只带 NDK headers |
| NDK `sysroot/usr/include/` 及架构子目录 | Bionic、公用系统头文件和目标相关类型 |
| `crtbegin_so.o`、`crtend_so.o` | Android shared-library CRT |
| API 对应的 `libc.so`、`libm.so`，需要时 `libdl.so` | 链接 stub，**不是可部署的 Android libc 实现** |
| 目标架构的 `libtcc1.a` | TinyCC 编译器运行时辅助函数 |

设备 bundle 的相对布局：

```text
bundle/
  include/                 # NDK headers 与 TinyCC headers 合并，TinyCC 同名头优先
    aarch64-linux-android/
  libtcc1.a
  lib/
    crtbegin_so.o
    crtend_so.o
    libc.so
    libm.so
    libdl.so
```

实际配置 `SYSINCLUDEPATHS={B}/include:{B}/include/aarch64-linux-android`、`LIBPATHS={B}/lib:{B}`、`CRTPREFIX={B}/lib`。必须先 `tcc_set_lib_path(bundle)`，再设置 DLL 输出类型，因为 `tcc_set_output_type()` 已经会查找 CRT。

为便于复现，实验复制了完整 NDK include 树；没有把整套 NDK 或 gdcc 放进项目。生产打包还可以裁剪头文件，但应使用依赖清单验证，而不是凭猜测删文件。

### 4.3 交叉构建编译器的 bootstrap 陷阱

直接用 NDK Clang 执行 `make all` 不够：上游 make 会运行新生成的 `c2str.exe`，并用新生成的 `tcc` 编译 `libtcc1.a`；交叉生成的 Android 程序不能直接在普通 Linux 宿主上执行。

本轮用两步解决，不改 TinyCC makefile：

1. 构建宿主可执行、Android-target 的 TinyCC，先生成目标 `libtcc1.a`。
2. 用宿主 `cc` 单独构建 `c2str.exe`，再用 NDK Clang 只构建 Android `tcc` / `libtcc.so` / `libtcc.a`，复制第一步的 runtime archive。

Android 没有独立的 pthread 实现库，函数由 `libc.so` 提供。构建 Android libtcc 时覆盖 make 的 `LIBS='-lm -ldl'`，不要把桌面默认的 `-lpthread` 生搬过去。

另外，向 `configure` 传 `--tcc-switches=` **不能可靠清空默认值**，脚本的 default 逻辑会重新填入 Android 的 sysroot rpath。此处显式给 `--tcc-switches=-Wl,-section-alignment=0x4000`，避免把构建机路径烘进产物。

### 4.4 16 KB、TEXTREL 与 API

**默认值依目标架构而异，不能泛称 TinyCC 默认只有 4 KB 对齐。** 本次固定源码的 `arm64-link.c:15` 定义 `ELF_PAGE_SIZE=0x10000`，即 64 KB，本身已满足至少 16 KB 的对齐条件；`x86_64-link.c:16` 才是 `0x1000`。实验统一显式传 `-Wl,-section-alignment=0x4000`，使两种目标都输出恰好 16 KB 对齐；对 ARM64 是从 64 KB 下调，不是修复一个默认 4 KB 的缺陷。正式集成可保留 ARM64 的 64 KB 默认值，但应验证实际产物且不允许被其它配置下调到 4 KB。用 Clang 构建的 Android 编译器 / bootstrap 也传 `-Wl,-z,max-page-size=16384`。只改 compiler 自身的对齐，不会自动修正它后来输出的库。

64 位 Android 不接受 text relocations，较新系统也拒绝可写可执行 ELF segment。不能仅根据 TinyCC 文档的“非 PIC”警告推断 ARM64 共享库必定失败，也不能只加一个 TinyCC 可能忽略的 `-fPIC` 就认为安全。应检查真实产物，本项目实测没有 `TEXTREL` 或 RWE LOAD。

`__ANDROID_API__`、NDK CRT/stub 的 API 目录及对外声明的最低 API 必须一致。实验固定为 28，不是“所有 Android 版本均支持”的声明。ELF 对齐检查通过也不等于已经在 16 KB 内核上执行验证；APK/AAB 打包还有 ZIP alignment 这一独立要求。

## 5. gdcc C Helper 的结论与陷阱

### 5.1 必须保留 pthread-key TLS 回退

`vendor/tinycc/arm64-link.c` 的 TLSLE 实现使用固定 `+16` 偏移并标注 glibc。无论 Android API 29+ 已支持 ELF TLS 与否，都不能把这种 local-exec TLS 当成可任意 `dlopen` 的 DSO TLS；TCC 也没有因此获得 Android 的正确 TLS 模型。

当前 gdcc 的 aarch64 helper 已在 `minicoro.h` 使用 `pthread_once` / `pthread_key_create` / `pthread_getspecific` / `pthread_setspecific`，并在 DSO 析构时删除 key。本轮 ARM64 无需额外改动。不能改成普通全局变量，也不能用 `MCO_NO_MULTITHREAD` 掩盖问题。

Android x86_64 要另行处理：当前 gdcc 保留 `__thread`，而 fixture 已有更广的 pthread 回退。本实验选的 API 28 也早于 Bionic ELF TLS 支持起点 API 29。因此若正式接 Android x86_64，应让 Android TinyCC 明确走 pthread-key 分支，并单独验证，而不是仅复制本轮“x86_64 PASS”的标签。

### 5.2 协程栈和 ARM64 ABI

现有 aarch64 minicoro 汇编与 Android AAPCS64 路径在本次测试中工作，双线程 resume/yield 测试通过。已有 `.type ..., %function` 修复必须保留。Android 保留的 `x18` 也不应被协程或手写 thunk 当临时寄存器使用；核查当前 TinyCC `reg_classes` 未把 x18 放入通用 `RC_INT`，现有协程汇编没有写 x18。

这不是启用所有 Android 安全插桩组合后的认证：ShadowCallStack、MTE、BTI/PAC 及不同厂商实现仍需另测，不能以本次普通构建保证它们的组合兼容性。

### 5.3 HRX、可执行内存和缓存刷新

当前 HRX helper 先申请 RW 匿名页，写入后改成 RX，并按运行时 `getpagesize()` 分配。Android ARM64 TinyCC 走 `__arm64_clear_cache`。本轮在 app 进程中实际执行了缓存刷新与 thunk，没有照搬 macOS 的 `sys_icache_invalidate` 分支，也没有为了模拟器替换为 no-op。

`getpagesize()` 不是此处需要修复的缺失符号：NDK 28 stub 与实际 Bionic 均已解析成功。它比硬编码 `4096` 更适合 16 KB 设备。若 OEM/策略禁止 execmem，应保留 HRX 的 fail-closed 行为，不能改成 RWX 或悄悄使用不可重载的悬空函数指针。

非 editor 模式走 direct/non-reload 路径，不足以验证 editor 的 executable-thunk 行为；这也是本轮额外跑 helper probe 和真实 editor 插件的原因。

## 6. Godot Android 加载与集成

### 6.1 外部存储不能直接当动态库目录

项目放在 `/sdcard/probe-env`。首次 `dlopen` 被 namespace / 外部存储执行限制拒绝；Godot 4.5.2 的 `OS_Android::open_dynamic_library()` 将库复制到内部 `cache/dynamic_libraries/...` 后重试，日志已确认该路径。

因此，“`.so` 位于项目的 res://bin”可作为编辑器项目布局，但不能在自己的 JNI/C 桥里直接对同一个 `/sdcard/...so` 做 `dlopen` 并指望成功。设备端编译结果要写入适合加载的 app 内部目录，或者复用 Godot 的加载器。

`targetSdk>=29` 对 app 私有可写目录中程序的 `execve` 限制，不应混同为“所有 cache `.so` 都禁止 `dlopen`”。本次官方 `targetSdk=35` APK 的内部 cache 加载和进程内编译已通过；这也支持优先使用嵌入式 libtcc，而不是把 CLI 解压到 app 目录再启动。

测试桥的包名、用户 0 cache 路径和项目路径都是固定实验值，不适合直接作为生产 API。正式实现应从 Godot/JNI 获取 cache 路径，并管理并发、文件发布、代际命名与错误诊断。

### 6.2 元数据、依赖与启动

`.gdextension` 同时提供 `android.debug.arm64` 和 `android.debug.x86_64`，不是 `linux.debug.arm64`，也不是只提供 release 键。官方 Android editor 是 debug + editor 构建。

本项目主库只有 `libc.so` / `libm.so` 公共依赖，所以不需要部署自定义第三方库。生产若拆出其它 helper `.so` 或动态 `libtcc.so`，必须处理 SONAME、Godot `[dependencies]`、namespace 与预加载顺序；复制文件不等于 Android 会自动搜索该目录。不要依赖修改 `LD_LIBRARY_PATH` 来修复已经运行的 app。

CI 用真正的 Android Activity 启动：

```text
org.godotengine.editor.v4/org.godotengine.editor.GodotEditor
--esa command_line_params --editor,--path,/sdcard/probe-env,--rendering-method,gl_compatibility,--verbose
```

`command_line_params` 是 `String[]` extra，故使用 `--esa`，不是普通字符串 `--es`。通过 Android 的 appops 测试接口授予编辑器的 all-files-access 后再推送项目，避免停在权限 UI。

### 6.3 需要改 gdcc，而不是只加平台枚举

| 位置 | 正式接入所需工作 |
| --- | --- |
| `TinyCcCompiler.java:70-77,151-155` | Android 不在支持集，且当前拒绝 host != target。需要明确支持 Android 原生编译、桌面交叉编译，还是两者 |
| `TinyCcBundle.java:282-301` | 新 Android bundle / resource key 和正确的宿主库，不能把 glibc libtcc.so 分发给 Bionic |
| `build-script/build-tinycc-*`、`gradle/tinycc.gradle.kts` | 增加 NDK 驱动、两步 bootstrap、API 固定、MANIFEST 与相对 sysroot 布局，CI 覆盖 Android |
| `TinyCcCompiler.setupState():450-479` | 当前只有 `-std=c11`；加入 Android API 宏和 16 KB 链接配置，处理 libc/libm 等依赖 |
| `TinyCcObjCache.java:63-89` | 已含 target、bundle VERSION 和 include hash；扩展 Android 配置时还需保证 NDK/API/选项变更确实使缓存失效，不能复用旧 glibc 或不同 API 的对象 |
| Android 调用层 | 当前依赖 Java 25 和 `java.lang.foreign`；ART 没有公开的 Java 25 FFM API。新增的最小验证用 Android DEX + JNI 调用 libtcc，但还不是 gdcc 的生产 Java/JNI backend |
| 协程与 HRX | 保留已存在的 ARM64 pthread TLS、ASM、icache 和 runtime page-size 路径；若接 Android x86_64，扩展 TLS 回退条件 |

桌面方案可让现有 Java 前端继续生成 C，再加载 Linux-hosted / Android-target 的专用 libtcc。设备本地编译方案则优先把 libtcc 嵌入原生插件。本轮 Java 25 探针进一步证明最小 Java 类可在 ART 中经 JNI 调用 libtcc；**没有证明整个 gdcc Java 应用、Java 25 HotSpot 或 FFM 已能在 Android 编辑器中运行**。

## 7. 失败实验与核验

本轮使用 explore / scout 并行调研，但未将它们的判断直接视为事实：

- “libtcc 目标固定，所以不能交叉编译”：通过宿主可执行、Android-target libtcc 的成功实验纠正。
- “Android 的 `getpagesize` 可能缺失，需要改 helper”：通过 stub 符号、真实加载及 HRX 执行排除，没有做多余修改。
- “固定 x86_64 模拟器不能安装 arm64 APK”：实际 API 30 和 35 Google APIs 镜像包含 ARM native bridge；安装结果、`primaryCpuAbi`、系统属性和 Godot architecture 日志已核验。转译成功仍不是原生手机成功。
- “bundle 相对 CRT 路径有误”：核对 `{B}` 展开逻辑与设备生成的 `config.h`，并由实际设备端编译排除；没有按未经证实的审查意见改坏路径。
- “TinyCC 默认只有 4 KB ELF 对齐”：直接核查目标后端常量，ARM64 默认实际是 64 KB，x86_64 才是 4 KB；报告中的对齐建议已按目标架构区分。

真实遇到的失败：

| 实验 | 结果与处理 |
| --- | --- |
| SDK setup 默认安装 `tools` | 该过时 package 已不在仓库，CI 在编译前失败；改为仅安装 platform-tools 和固定 NDK |
| 未复制 TinyCC 自带 headers | 完整项目报 `stddef.h not found`；补齐 `{B}/include`，不是改生成的 C |
| API 30 ARM 转译 GUI | 库已经加载，后续 `ndk_translation 0.2.2` 报 `Undefined instruction 0x5ea1b842` / SIGILL，未到插件 PASS，见 [38734181068](https://github.com/script-gd/probe-env/actions/runs/38734181068) |
| API 35 + `--headless` Android editor Activity | x86_64 发生 SIGSEGV，ARM 测试中断，见 [38735067902](https://github.com/script-gd/probe-env/actions/runs/38735067902)；没有把桌面 headless 使用习惯当成 Android app 保证 |
| 最终 API 35 + GUI + 4 GB RAM | 两种 ABI 全通过。镜像、内存、headless 多个因素先后变更，不能声称已单独定位此前崩溃的全部根因 |

本轮没有靠修改 TinyCC 指令生成来迎合转译器；最终是真实 GUI editor 插件通过，而非 headless 的替代结果。

## 8. 复现与交付

仓库新增的主体文件：

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/android.yml` | 固定 NDK、两种目标 ABI、API 35 模拟器、两轮官方 editor 测试及 artifact |
| `scripts/android-build.sh` | 构建两种宿主的编译器、完整生成库、协程/HRX 库与 ELF 断言 |
| `scripts/android-editor.sh` | 安装指定 ABI APK、授权、推送、启动 editor、核验标记与保存证据 |
| `probes/android_compile.c` | 两端共用的 libtcc API 编译驱动 |
| `probes/android_bootstrap.c` | 仅用于实验的 Android 进程内编译/加载桥及 helper 回归入口 |
| `android/project/` | `.gdextension`、编辑器 `@tool` 插件和最小 Godot 项目 |

推荐直接运行 [Android workflow](.github/workflows/android.yml)。已有隔离 NDK 与 adb 设备时，从仓库根目录执行：

```bash
ABI=arm64-v8a ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28b \
  bash scripts/android-build.sh

APK_ABI=arm64-v8a LIBPROBE_SO="$PWD/build/android/arm64-v8a/libprobe.so" \
  bash scripts/android-editor.sh

APK_ABI=arm64-v8a DEVICE_COMPILE=1 \
  LIBPROBE_SO="$PWD/build/android/arm64-v8a/libprobe.so" \
  bash scripts/android-editor.sh
```

build 脚本当前针对 Linux x86_64 工具链宿主，固定实验 API 为 28；editor 脚本使用已经连接的 adb 设备。会替换设备上的 `/sdcard/probe-env` 实验目录及同包名 Godot editor APK，勿把该目录用作实际工作项目。

最终 CI 的 artifact 为 `android-arm64-v8a` / `android-x86_64`，包含 `build.log`、`config.h`、ELF 清单、编译库、摘要、两轮 logcat、设备属性与 APK dumpsys。已下载到本机 `build/evidence-38735984841/`，该目录按惯例不纳入 Git。`bash -n`、`git diff --check` 与真实 CI 构建均通过；IntelliJ MCP build 接口返回 schema 错误，因此没有把它作为验证成功依据。

剩余最重要的验证是：在原生 ARM64 手机上重复测试，再在真正 16 KB 页设备上重跑；将当前单一类/单一字符串改动的重载探针扩展为 gdcc 实际生成的 HRX Callable、协程状态和 schema 变化热重载套件。除此之外，本轮已建立可复核的 Android 编译、链接、编辑器执行闭环，不再只是基于 Linux ARM64 的可行性推断。

## 9. Java 25 JAR、ART、JNI 与 Godot 热重载

### 9.1 结论

**最小端到端路径可行，但不是把 Termux 的 OpenJDK 25 直接嵌进 Godot。** Java 25 编译的测试 class/JAR（major version 69）经 D8 转成含 `classes.dex` 的 JAR 后，由 Godot Android Editor 进程内的 ART `DexClassLoader` 加载；Java 类通过 JNI 桥调用 Android-hosted libtcc，编译完整五 TU gdcc fixture 的第二个版本；Godot 插件随后执行真实 `GDExtensionManager.reload_extension()`，并观察到新实现返回 `java25=42`。

运行时是 Android 自带 ART，不是 OpenJDK 25 HotSpot。DEX 不是 Java 25 classfile；D8 会将 classfile 改写为 ART 指令。因此这证明的是“Java 25 编译产物可经 D8 转换、在 ART 执行一段兼容 Android API 的 Java 逻辑”，不是 ART 支持直接运行 major 69 classfile、Java 25 新 API 或 `java.lang.foreign`。

### 9.2 实验链路

1. GitHub runner 使用 Temurin JDK 25 执行 `javac --release 25`，打包原始 classfile JAR，并用 `javap` 断言 `major version: 69`。
2. 固定 R8/D8 `9.5.23`（SHA-256 固定在构建脚本），以 `--min-api 24` 将 classfile JAR 转为 DEX，再把 `classes.dex` 放入供 Android `DexClassLoader` 加载的 DEX JAR。
3. NDK 构建 `libgdcc_java25_bridge.so`，它静态链接 Android-target `libtcc.a`，导出 Java native 方法并复用 `android_compile_project()`。原始生成的 `libprobe.so` 先由 Editor 加载，插件确认 `render(42) == "value=42"`。
4. GDScript 把 DEX JAR 与 JNI `.so` 复制到 app 私有 `user://` 目录、设为只读，再创建 `DexClassLoader`。只读是 Android 14+ safer dynamic-code-loading 的要求；输出 DEX 优化目录仍可写。
5. `Class.forName(..., true, loader)` 初始化 Java 测试类。它在后台线程把生成 C 的 `entry.c` 中一个字面量改为 `java25=%s`，并通过 JNI 将完整五个 TU 交给 libtcc 编译到 `libprobe-v2.so`。
6. Editor 插件将 v2 放到原 GDExtension 源路径，调用 `GDExtensionManager.reload_extension("res://probe.gdextension")`，重新实例化同一 gdcc 生成类，并断言 `render(42) == "java25=42"`。

这是实际 Godot Android Editor 的 unload/reopen/reinitialize，不只是 `dlopen()` 两个文件的宿主模拟。Godot 4.5.2 `platform/android/os_android.cpp` 的 `copy_dynamic_library()` 会在每次 Android 外部库加载时删除已有的 cache copy 并从项目路径重拷，因此本测试使用同一个源路径重载也会读到新文件。该结论针对本次锁定的 Godot 4.5.2 源码；若升级 Godot，应重新核对此行为。

但热重载覆盖面仍很窄：替换的是 gdcc 已生成 C 项目的一个方法字面量，其余四个 TU 和 wrapper ABI 不变。没有在 Android 上遍历 gdcc 的跨代 HRX Callable 重绑定、协程迁移、schema 兼容/不兼容、活对象字段重建等完整回归；这些不能用本次 `reload_extension()` PASS 代替。

### 9.3 为什么不直接装 Termux JDK

Termux OpenJDK 25 是为 Termux 包前缀构建的，不是可搬进另一个 APK 的独立 runtime。当前包构建配置把 `$TERMUX_PREFIX` 写入 rpath/路径配置，并链接 Termux 提供的 `libandroid-shmem`、`libandroid-spawn` 等依赖；默认前缀在 Termux 私有目录。普通 Godot app 不能读取 `com.termux` 私有数据。直接复制 APK 或 JAR 不会解除前缀、依赖及 Android linker namespace 限制。

即使另行构建可重定位的 OpenJDK 25，Godot Android Editor 自身是 ART app。不能再创建第二个 ART VM；`JNI_CreateJavaVM` 不是在现有 Android app 中启动另一个 ART 的支持接口。Pojav 类启动器通过预加载 Android HotSpot JRE 的库并调用 `JLI_Launch`，证明“另一个 HotSpot VM 共存”技术上可探索，但这不等于受支持的嵌入式 JVM：需要自构建/重定位整套 JRE 和依赖，并处理 linker namespace、JIT/W^X、信号链、16 KB 页及线程/崩溃隔离。本轮没有集成或验证该路线。

Godot 4.5 Android Editor 文档说明不支持项目 Gradle build，也不支持把自定义 Android plugin/AAR 安装进现有 Editor APK。编辑器 APK 已提供 `JavaClassWrapper`；因此本探针没有重打包宿主，而是使用 Android 公共 `DexClassLoader` 在 app 私有目录加载转换后的 DEX。**原始 Java 25 classfile JAR 不能直接交给 ART；D8 是必需转换步骤。** gdcc 现有 FFM binding 不能原样运行在 ART 上，本探针以一小段 JNI wrapper 明确替代 FFM boundary。

若产品必须执行 Java 25 HotSpot/FFM 代码，可选方案为：在用户已安装并授权的 Termux 中运行 Java 25 JAR，通过显式 IPC/共享文件把编译结果交给 Editor；或研究自包含、重定位的 HotSpot JRE 并接受第二 VM 的非官方支持成本。前者是跨 app 进程而非编辑器内调用，后者体积和运行时风险都显著高于本次 ART + JNI 路径。若只需 Java 逻辑，建议在 ART 上使用 Java/Kotlin 兼容 API，再通过稳定 JNI/GDExtension 边界访问 libtcc。

### 9.4 复现与证据

完整路径由 `.github/workflows/android.yml` 中 x86_64 ABI job 运行；Android 目标仍是 API 35 Google APIs x86_64 模拟器，Java classfile 由 JDK 25 构建，Editor 运行在其 app 进程内的 ART。新增入口：

| 文件 | 作用 |
| --- | --- |
| `scripts/java25-probe-build.sh` | 编译 major 69 classfile JAR、验证哈希固定的 R8、生成 DEX JAR |
| `scripts/java25/src/gdcc/probe/Java25TccProbe.java` | 最小 Java 编译器入口；生成不同内容的 fixture 并触发 JNI 编译 |
| `probes/java25_jni_bridge.c` | Java native 方法到 libtcc C 驱动的 JNI 桥 |
| `probes/android_compile.c` | 新增可替换第一个 TU 的参数形式；仍编译完整五 TU 项目 |
| `scripts/android-java25-editor.sh` | 部署实验项目、启动官方 Editor、保存 logcat 并核验热重载标记 |
| `android/project/addons/probe_runner/probe_runner.gd` | DEX classloader、JNI 结果轮询、真实 GDExtension reload 与 v2 行为断言 |

可单独构建 Java/D8 输入：

```bash
bash scripts/java25-probe-build.sh
javap -verbose build/java25/classes/gdcc/probe/Java25TccProbe.class
```

Android 端脚本由 workflow 在构建 Android libtcc 和 JNI bridge、准备 API 35 模拟器后调用。CI 证据包含 `java25-logcat.txt`、`java25-cache.txt`、原始 major 69 classfile JAR 和 DEX JAR。该测试只在 x86_64 editor ABI 运行，不修改本机 SDK、NDK、Termux 或 Godot Editor APK。

## 10. 参考资料

- [Godot 4.5.2 Android 动态库加载与 cache 复制源码](https://github.com/godotengine/godot/blob/4.5.2-stable/platform/android/os_android.cpp)
- [GodotActivity 的 StringArray command-line extra](https://github.com/godotengine/godot/blob/4.5.2-stable/platform/android/java/lib/src/org/godotengine/godot/GodotActivity.kt)
- [Godot Android editor 文档](https://docs.godotengine.org/en/stable/tutorials/editor/using_the_android_editor.html)
- [Godot 4.5 JavaClassWrapper / AndroidRuntime 文档](https://docs.godotengine.org/en/4.5/tutorials/platform/android/javaclasswrapper_and_androidruntimeplugin.html)
- [Godot 4.5.2 Android 外部动态库复制/重载实现](https://github.com/godotengine/godot/blob/4.5.2-stable/platform/android/os_android.cpp)
- [Godot 4.5 GDExtensionManager.reload_extension](https://docs.godotengine.org/en/4.5/classes/class_gdextensionmanager.html)
- [D8 官方文档：classfile 到 DEX](https://developer.android.com/tools/d8)
- [Android 14 safer dynamic code loading](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)
- [Termux OpenJDK 25 package build](https://github.com/termux/termux-packages/blob/master/packages/openjdk-25/build.sh)
- [Termux binary non-portability policy](https://github.com/termux/termux-app/discussions/2459)
- [Pojav JRE launcher: preload HotSpot and use JLI_Launch](https://github.com/PojavLauncherTeam/PojavLauncher/blob/v3_openjdk/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java)
- [JEP 454: Java 22 finalized FFM API](https://openjdk.org/jeps/454)
- [GDExtension 配置与 feature tags](https://docs.godotengine.org/en/4.5/tutorials/scripting/gdextension/gdextension_file.html)
- [Bionic linker 变更：TEXTREL、SONAME、RUNPATH、W+X、ELF TLS](https://android.googlesource.com/platform/bionic/+/refs/heads/main/android-changes-for-ndk-developers.md)，本轮直接核对了其 [AOSP 镜像正文](https://github.com/aosp-mirror/platform_bionic/blob/master/android-changes-for-ndk-developers.md)
- [Android 16 KB 页兼容指南](https://developer.android.com/guide/practices/page-sizes)
- [Android 10 app 私有目录 execve 限制](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Android linker namespace 说明](https://source.android.com/docs/core/architecture/partitions/linker-namespace)
- [本实验固定 TinyCC configure](vendor/tinycc/configure)、[Android CRT 处理](vendor/tinycc/tccelf.c)、[ARM64 TLS relocation](vendor/tinycc/arm64-link.c)
- [上轮 Linux/macOS 报告，作为历史基线而非 Android 证据](REPORT.md)

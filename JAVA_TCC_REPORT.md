# gdcc vendored TinyCC 的 Java 重写可行性调研

日期：2026-10-17。实验分支：`research/java-tinycc-rewrite`（干净孤儿分支）。

## 1. 结论

**技术上可行，但属于大型工程；本轮已完成可行性验证与首个里程碑。**

- **范围可以收敛**：gdcc 只用 libtcc 的 10 个 API、固定 `-std=c11`、只产出 `.o` 与共享库，不用 JIT/内存执行/交叉编译。删掉 JIT、32 位后端、调试信息后（**保留 ELF + Mach-O + PE 三种格式**以覆盖 Linux/Android/macOS/Windows），需要移植的 C 代码约 **3.1 万行**（含 x86_64+arm64 双后端、内联汇编与 Mach-O/PE 共享库产出），而非完整 tinycc 的约 6.9 万行。
- **本轮 PoC 已验证最难的单点之一**：用 Java 重写的词法器+预处理器（`jtcc/`，约 1000 行）在完整 gdcc 输出项目（5 个编译单元，含 glibc 系统头与全部 GNU 扩展）上与 vendored tcc 的 `tcc -E` 输出**逐 token 完全一致（合计 856,800 个 token）**，并通过 gdcc 的 u8 字面量全部边界用例。
- **最小保留集已实证自洽**：本分支 `vendor/tinycc/` 只保留移植必需文件，用 `ONE_SOURCE=0` + 26 个调试桩函数即可构建出能工作的 tcc；在 Linux 上把完整 fixture 编译链接为可 `dlopen` 的 x86-64 `.so`，在 Apple Silicon macOS 上产出通过 `codesign -v` 且可 `dlopen` 的 arm64 `.dylib`，并以 Linux 交叉构建的 PE tcc 产出在 Windows 上 `LoadLibrary` 成功的 x86-64 `.dll`（[CI #39052382088](https://github.com/script-gd/probe-env/actions/runs/39052382088)，Linux + macOS + Windows 三端绿）。
- **主要风险**：解析器+通用代码生成器 `tccgen.c`（9049 行）是最大单体；`minicoro.h` 的协程上下文切换在 Linux x86_64/arm64 与 macOS arm64 上依赖**文件级 `__asm__` 块**，Java 版不能砍掉汇编器，或需改用预编译字节 blob（Windows 路径已有先例）；Mach-O 的 dyld bind/rebase 编码与导出 trie 是独立的一套链接器逻辑（与 ELF 的 `.dynsym` 模型不同）；aarch64 ELF 使用 `SHT_REL` 隐式加数重定位，与 x86_64 的 `SHT_RELA` 不同。
- **架构上 Java 版有一个真实优势**：C 版 tcc 通过 `tcc.h:366-393` 的 `#include` 机制只能编译进**单一目标后端**（gdcc 因此拒绝交叉编译）；Java 版可同时持有 x86_64 与 arm64 两个后端，一次运行按目标分发——这正是 gdcc 接入 Android 所需要的。

**建议**：可行但应按阶段推进（见 §8），先落地 x86_64 ELF 单后端打通端到端，再补 arm64 ELF、arm64 Mach-O 与 x86_64 PE（Mach-O 增量约 2.4k、PE 增量约 2.1k 行 C 移植量，详见 §6.2/§6.3）。若目标只是"在 Android 上编译 gdcc 输出"，另有一个量级更小的备选方案见 §9。

## 2. 输入与验证范围

| 项目 | 版本 / 来源 |
| --- | --- |
| 被调研的 gdcc | 工作区 `../gdcc`（与上一轮相同的 `3136c451` 时代码结构） |
| vendored TinyCC | `0.9.28rc`，upstream pin `43c7708b85681a2fd4451c8a541af4494a8919b2` + gdcc `u8"..."` 补丁 |
| 完整 C 项目 fixture | 复制自上一轮分支的 `fixture/`（5 个编译单元，约 4.2 万行 C/H） |
| 本轮新增 PoC | `jtcc/`（Java 词法器+预处理器）、`scripts/`（构建与校验）、`vendor/tinycc/`（最小保留集） |
| 验证环境 | GitHub Actions `ubuntu-latest`（gcc + Temurin JDK 21）与 `macos-14`（Apple Silicon）；本地仅做开发迭代（所有重活在 CI） |
| 本轮**未做** | 解析器/代码生成/链接器的完整移植（属于后续阶段，见 §8） |

## 3. gdcc 实际如何使用 libtcc（已核验）

FFM 绑定在 `../gdcc/src/main/java/gd/script/gdcc/backend/c/build/TinyCcLibrary.java:86-95`，进程级共享 Arena。**只绑定 10 个函数**：

`tcc_new` / `tcc_delete` / `tcc_set_lib_path` / `tcc_set_error_func` / `tcc_set_options` / `tcc_set_output_type` / `tcc_add_include_path` / `tcc_add_file` / `tcc_compile_string` / `tcc_output_file`

生产路径（`TinyCcCompiler.java`）：

1. 每个编译单元：新建 `TCCState` → `set_options("-std=c11")` → `TCC_OUTPUT_OBJ` → `tcc_add_file(源文件)` → `tcc_output_file(临时 .o)`（带内容寻址缓存）。
2. 链接：新建 `TCCState` → `TCC_OUTPUT_DLL` → 逐个 `tcc_add_file(.o)` → `tcc_output_file(lib*.so)`。
3. 诊断只走 error 回调；`tcc_compile_string` 仅测试用；**从不使用** `tcc_run`/`tcc_relocate`/`tcc_get_symbol`/`tcc_add_library`，**从不使用** `TCC_OUTPUT_MEMORY`，**不做** `-target` 交叉编译（`targetPlatform != host` 直接拒绝，提示用 zig 后端）。

Java 重写要复刻的对外行为因此只有：**多次"源文件→.o"** 和 **一次"多个 .o→.so"**，外加 include 路径、`-std=c11` 语义和诊断回调。无 JIT、无运行时符号解析、无系统库链接器脚本之外的特性。

## 4. 最小保留集分析（已实证）

完整 vendored 树 68,695 行。下表为 x86_64+arm64 ELF 编译+链接的保留集（`vendor/tinycc/` 已按此落地）：

| 模块 | 文件 | 行数 | 说明 |
| --- | --- | ---: | --- |
| 预处理+词法 | `tccpp.c` | 3,972 | ✅ 本轮已有 Java 等价物 |
| 解析+通用代码生成 | `tccgen.c` | 9,049 | 最大单体，移植主战场 |
| ELF 输出+链接器 | `tccelf.c` | 4,215 | `.o`/`.so` 写出、GOT/PLT、重定位；同时是 Mach-O 的共享链接核心（见 §6.2） |
| Mach-O 输出+加载 | `tccmacho.c` | 2,477 | dylib 写出（dyld bind/rebase、导出 trie、LC_ID_DYLIB）、`.dylib`/`.tbd` 加载 |
| PE 输出+加载 | `tccpe.c` | 2,304 | dll 写出（导入/导出表、`.reloc`、`.tls`、`.pdata`）、`.def`/`.dll` 导入解析 |
| 驱动/API/分配器 | `libtcc.c` | 2,253 | Java 版大部分可用 OO 状态重写 |
| 汇编器（共享） | `tccasm.c` | 1,525 | **必需**（见 §6.1），子代理原判 OPTIONAL 已修正 |
| x86_64 后端 | `x86_64-gen.c` + `x86_64-link.c` | 2,690 | |
| x86_64 汇编 | `i386-asm.c` + `x86_64-asm.h` + `i386-tok.h` | 2,641 | minicoro 需要 |
| arm64 后端 | `arm64-gen.c` + `arm64-link.c` | 2,687 | |
| arm64 汇编 | `arm64-asm.c` + `arm64-tok.h` | 3,116 | minicoro 需要 |
| 头文件 | `tcc.h`/`libtcc.h`/`tcctok.h`/`elf.h`/`dwarf.h`/`stab.h`/`stab.def` | 6,591 | `elf.h` 3,325 行多为常量，Java 只需用到的子集 |
| 运行时（数据） | `include/*.h`（11 个）+ `lib/` 的 `libtcc1.a` 源（10 个文件） | ~5,400 | **不重写**，作为资源嵌入；`libtcc1.a` 可预编译为 `.o` 数据 |
| Windows 数据 | `win32/include/`（CRT/winapi 头，1.5MB）+ `win32/lib/` 的 5 个 `.def` 与 `dllcrt1.c`/`dllmain.c`/`crtinit.c`/`chkstk.S` | — | **不重写**，作为资源嵌入（与 gdcc 现行 bundle 一致） |

**已删除**：`tccrun.c`（JIT）、`tccdbg.c`（DWARF/stabs，留 26 个空桩）、`tcccoff.c`（C67 专用，与 PE 无关——`libtcc.c:47` 仅在 `TCC_TARGET_C67` 下包含它）、`tcc.c`/`tcctools.c`（CLI/ar，Java 驱动替代）、`il-gen.c`、i386/arm/c67/riscv64 全部后端、`win32/` 的可执行 CRT（`crt1.c`/`crt1w.c`，DLL-only 不需要）、`lib/bcheck.c`/`bt-*`/`tcov.c`/`runmain.c`（仅 JIT/调试用）。**`tccmacho.c` 与 `tccpe.c` 保留**（§6.2/§6.3）。

可移植净量：**核心 19,489 + Mach-O 2,477 + PE 2,304 + 双后端 5,377 + 双汇编器 5,757 ≈ 35.4k 行 C**（不含头文件常量表与数据文件）；若先只做 x86_64 ELF 单后端 ≈ **25.8k 行**。本分支用 `ONE_SOURCE=0` + 桩文件从该集合构建出 Linux x86_64、macOS arm64、Windows x86_64 三个目标的可用 tcc 并全部通过 fixture 全量编译——证明集合无遗漏。

关键接口（移植时的模块边界）：

- 词法→解析：`next()`/`unget_tok()`/`tok`/`tokc`/`parse_flags`，解析器会用 `begin_macro/end_macro` **重放已保存的 token 流**（内联函数、VLA 数组、初始化器、语句表达式），`TokenString` 是跨模块接口。
- 解析→后端：后端契约仅 **22 个函数**（`load/store/gfunc_*/gjmp/gen_op*/gen_cvt_*` 等）+ 目标数据（`reg_classes`、`target_machine_defs`）。
- 后端→链接器：**5 个函数**（`code_reloc/gotplt_entry_type/create_plt_entry/relocate_plt/relocate`）。
- 全局状态：`TCCState` 之外还有大量解析器/词法器全局变量（`tccpp.c:30-62`、`tccgen.c:32-78`）和 `USING_GLOBALS` 宏别名；错误处理是 `setjmp/longjmp`（`tcc_compile` 一处 setjmp，`error1` longjmp），Java 用异常+try/finally 替代，GC 消解手工清理栈（`stk_push/pop`）。

## 5. fixture 实际需要的 C 特性（Java 版必须支持）

对 4.2 万行 fixture 逐项核验（含 file:line 证据的清单在调研记录中）：

**必须有**：完整预处理器（函数式/可变参数宏、`#`/`##`、`defined`、`#if`、`#include`、`#error`、`#pragma once`、行拼接、字符串邻接）、`_Static_assert`、`_Generic`、`_Noreturn`、匿名 struct/union、指定初始化器（struct+数组下标）、复合字面量、**1 处 VLA**（`gdcc_call.h:94`）、GNU 语句表达式 `({...})`、`__typeof__`、varargs 内建（`__builtin_va_*` + `__va_arg` 运行时助手）、`_Bool`、`long long`、`long double` 解析（来自 tcc 自己的头文件）、`u8"..."` 与 `\u`/`\U` 转义、GNU `__attribute__`（visibility/pure/const/always_inline/noinline/section/destructor）、**文件级 `__asm__` 块**。

**不需要**：`_Atomic`、位域、柔性数组成员、packed、weak、constructor 属性、计算 goto、`__int128`、向量扩展、`_Complex`、三/双字母符、`#embed`、K&R 函数、操作数式内联汇编。

另外必须能**解析宿主 glibc 头文件**（stdio/stdlib/string/math/pthread/sys/mman.h 等）：tcc bundle 不带这些头，靠宿主系统头 + `tccdefs.h` 预定义宏兜底。本轮 PoC 已实证 Java 预处理器能完整消化 glibc 头并逐 token 对齐。

## 6. 关键风险与难点

### 6.1 minicoro 的 `__asm__` 块（最重要发现）

`fixture/include/gdcc/minicoro.h:823-877`（x86_64）与 `:1233,:1276`（aarch64）在 Linux + tcc 下用**文件级 `__asm__`** 实现协程上下文切换（`_mco_wrap_main`/`_mco_switch`），无法用纯 C 表达。两个选项：

- **移植 tcc 的汇编器路径**（`tccasm.c` + 双架构汇编器，+5.7k 行）；
- **改走 Windows 同款方案**：minicoro 在 Windows 已用 `__attribute__((section(".text")))` + 预编译字节 blob，Java 版可对 ELF/Mach-O 目标同样内嵌预汇编字节，把汇编器从必需清单中移除。**推荐后者**，可省约 5.7k 行移植量。（Windows 字节 blob 路径本轮已在 PE dll 实测中随 fixture 编译加载通过，可行性有直接证据。）

### 6.2 Mach-O dylib 的增量工作（macOS 支持）

保留 `tccmacho.c`（2,477 行）后，产出 `.dylib` 需要的增量工作项（相对纯 ELF 方案）：

**结构关系（已核验）**：`tccmacho.c` 不是独立链接器——`macho_output_file`（tccmacho.c:2179）驱动 `tccelf.c` 的共享链接核心（`tcc_add_runtime`/`resolve_common_syms`/`relocate_syms`/`relocate_sections`，共 74 处调用），Mach-O 侧只负责格式特有的部分。ELF 保留集的工作量因此大部分被复用。

**dylib 产出特有的移植内容**：
- **dyld 风格动态信息**：bind/rebase 编码（uleb128 操作码流，`bind_rebase_import`/`bind_rebase`，约 400 行）与**导出 trie**（`export_trie`，约 140 行）——替代 ELF 的 `.dynsym/.hash` 模型；
- **load command/section 布局**：`LC_SEGMENT_64`/`LC_ID_DYLIB`（install_name，tccmacho.c:1724）/`LC_SYMTAB` 等（`collect_sections`/`macho_write`，约 500 行）；
- **重定位核对**：`check_relocs` 对 arm64 Mach-O 的重定位类型分类（`ARM64_RELOC_*`，与 ELF 的 `R_AARCH64_*` 是另一套枚举）；
- **destructor trampoline**：`tcc_macho_add_destructor`（tccmacho.c:556+）为 `__attribute__((destructor))` 手工汇编 x86_64/arm64 跳板代码并挂 `.fini_array`——**必需**，因为 minicoro.h:590 用了 destructor 属性；
- **输入侧**（链接 libSystem 所需）：`.dylib` 加载（`macho_load_dll`，约 120 行）、Apple `.tbd` 文本 stub 解析（`macho_load_tbd`，约 40 行的简化文本扫描）、SDK 路径探测（`tcc_add_macos_sdkpath` 用 `libxcselect`；**Java 版改为 `xcrun --show-sdk-path` 子进程，无 FFI**）；
- **代码签名**：上游 configure 在 Apple 上默认启用 `CONFIG_CODESIGN`，tcc 通过 `system("codesign -f -s - …")` 完成 ad-hoc 签名（tccmacho.c:2237-2249）。Java 版用 `ProcessBuilder` 调 `codesign` 即可，**无需自己实现 LC_CODE_SIGNATURE**。arm64 macOS 上未签名的 dylib 无法 `dlopen`，此步不可省。

**可删除的 Mach-O 部分**：仅 MH_EXECUTE 可执行文件路径（入口点、`__PAGEZERO` 等，约 100 行）。dylib 保留集 ≈ **2,400 行**。

**注意事项**：`TCC_EH_FRAME` 在 Mach-O 构建中也启用（tcc.h:1841-1846 未排除 Mach-O），删除 `tccdbg.c` 后产物没有 unwind 信息——对 GDExtension dylib 场景无影响；gdcc 的 macOS bundle 只有 **aarch64** 一种（`TinyCcBundle.java:108-111`），所以 Mach-O 只需 arm64 后端，x86_64 Mach-O 不需要。

**实证**：CI macos-14（Apple Silicon）上，最小保留集 + `tccmacho.c` 构建的 arm64 Mach-O tcc 把完整 fixture 编译为 `libprobe.dylib`（`Mach-O 64-bit dynamically linked shared library arm64`），`codesign -v` 通过，`dlopen` 成功。

### 6.3 PE/COFF dll 的增量工作（Windows 支持）

保留 `tccpe.c`（2,304 行）后，产出 `.dll` 需要的增量工作项：

**通用中间格式（关键架构事实，已核验）**：`tcc_output_file`（tccelf.c:3223）对 `TCC_OUTPUT_OBJ` **在所有目标上都走 `elf_output_obj`**——gdcc 逐 TU 产出的 `.o` 在 Windows/macOS 上也是 ELF 对象（tcc 的内部 IR），只有最终共享库链接才分格式。Java 移植因此只需一种对象读写器（ELF `.o`），格式后端只管最终 dll/dylib/so 链接与系统库导入解析。

**dll 产出特有的移植内容**：
- **PE 写出器**：MZ/PE32+ 头、节布局/对齐（`pe_write`/`pe_assign_addresses`，约 700 行）；
- **导入/导出表**：`pe_build_imports`/`pe_build_exports`（约 250 行）——fixture 经 `__declspec(dllexport)`（`gdcc_helper.h:20`）导出 `gdextension_entry`；
- **基址重定位 `.reloc` 与 TLS 目录 `.tls`**（`pe_build_reloc`/`pe_build_tls`，约 130 行）——fixture 在 Windows 上用 `__thread` TLS；
- **`.pdata` unwind 信息**（`pe_add_unwind_info/data`，约 100 行，`x86_64-gen.c:1016` 每函数调用）；
- **导入解析**：`.def` 文件解析（`pe_load_def`）+ 直接读 PE 导出表（`get_dllexports`/`pe_load_dll`），共约 180 行——用于对 msvcrt/kernel32 等系统库符号解析；
- **Windows x64 ABI 分支**：`x86_64-gen.c` 的 12 处 `TCC_TARGET_PE` 分支（shadow space、RCX/RDX/R8/R9 参数序、大栈帧 `__chkstk` 调用）；**LLP64**（`tcc.h:416-419`：PE 下 `LONG_SIZE=4`）——类型系统必须按目标参数化；
- **`__declspec(dllexport/dllimport)` 属性**：`tccgen.c` 的 14 处 PE 分支（共享文件内，已计入核心行数）。

**可删除的 PE 部分**：PDB 生成（`pe_create_pdb`，随调试删除）、`.res` 资源加载（`pe_load_res`）、EXE 专用入口/subsystem 路径。dll 保留集 ≈ **2,100 行**。

**数据文件（不移植）**：`win32/include/`（tcc 自带 CRT+winapi 头，1.5MB，与 gdcc bundle 一致地平铺合并）、5 个 `.def`、`win32/lib/` 的 `dllcrt1.c`/`dllmain.c`/`crtinit.c`（DLL CRT 启动）与 `chkstk.S`（栈探测）。EXE CRT（`crt1.c`/`crt1w.c`/`wincrt1*`/`winex`）删除。`libtcc1.a` 的 PE 变体由 `lib/Makefile` 的 `X86_64_O + chkstk.o + WIN_O` 规则预编译为数据。

**范围便利**：gdcc 只发布 windows-x86_64 bundle，PE 只需 x86_64 后端；且 **minicoro 在 Windows 本就使用预汇编字节 blob**（`_MCO_ASM_BLOB`，见 §6.1），PE 路径不需要内联汇编器（`chkstk.S` 可预编译为数据）。

**实证**：CI 在 Linux 上交叉构建 PE 目标参考 tcc，fixture 5 TU → `probe.dll`（`PE32+ x86-64 DLL`），导出表恰为 `gdextension_entry`，导入表为 `msvcrt.dll`+`kernel32.dll`，含 `.pdata`/`.tls`；windows-latest runner 上 `ctypes.CDLL` 加载成功（LoadLibrary OK）。

### 6.4 链接器不是"toy"（ELF 侧）

`.so` 产出需要 `.dynsym/.dynstr/.hash`、`.dynamic`（DT_NEEDED 等）、GOT/PLT、`.rela.dyn/.rela.plt`、glibc 符号版本（`printf@GLIBC_2.2.5` 式）。tcc 的链接器是自包含子集实现（`tccelf.c` + 两个 `*-link.c`），已包含所需全部逻辑；移植难点在忠实度而非设计。aarch64 用 `SHT_REL`（隐式加数）而 x86_64 用 `SHT_RELA`，两套重定位应用逻辑不同（`R_AARCH64_ADR_PREL_PG_HI21`/`ADD_ABS_LO12_NC`/`CALL26` 等 vs `R_X86_64_PC32/PLT32/GOTPCREL` 等）。好处是：只做 `.so` 不需要 CRT 启动文件（crt1.o/crti.o），与 Android bionic 的命名差异也自然回避。

### 6.5 一个 C 二进制一个后端 vs Java 双后端

`tcc.h:366-393` 用 `#include` 把目标后端文本嵌入所有编译单元，`x86_64-gen.c` 与 `arm64-gen.c` 定义同名符号，C 层面互斥。Java 版可将两个后端作为两个类共存，按目标平台分发——直接解决 gdcc 当前"拒绝交叉编译"（`TinyCcCompiler.java:153-156`）与"Android 无 tinycc 后端"两个限制。

### 6.6 其他已识别风险

- **一次通过式架构**：tcc 边解析边产码，无 AST；Java 移植若忠实复刻则行为最可控，若改用 chibicc 式多趟设计则语义漂移风险自担。建议忠实复刻。
- **TLS**：aarch64 local-exec TLS 在 DSO 中不安全，gdcc 已在 aarch64 走 pthread-key 路径（`minicoro.h:434-456`），Java 版只需保证 `__thread` 解析与 x86_64 路径一致。
- **`#line` 操作数**：tcc 逐字节复制不反转义（gdcc 的 `StringUtil.escapeLineDirectiveFileName` 已适配），Java 版需复刻。
- **诊断/错误模型**：gdcc 依赖 error 回调收集诊断；Java 版用异常即可，需保持错误消息格式可供测试断言。

## 7. 本轮 PoC 证据

本分支提交内容：

| 路径 | 内容 |
| --- | --- |
| `vendor/tinycc/` | 最小保留集（§4 表中的全部 .c/.h + include/ + lib/ 与 win32/ 子集），3.2MB |
| `fixture/` | 完整 gdcc C 输出项目（5 TU，与上一轮相同输入） |
| `jtcc/src/jtcc/` | Java 词法器+预处理器（Tok/Lexer/Preprocessor/Eval/Main，约 1000 行） |
| `reference-cli/` | 仅参考构建用的 `tcc.c`/`tcctools.c` + 共享调试桩 `stubs.c`（不属于移植集） |
| `scripts/build-tcc-ref.sh` | Linux：x86_64 ELF 参考 tcc（含 u8 探针） |
| `scripts/build-tcc-ref-macho.sh` | macOS：arm64 Mach-O 参考 tcc + fixture dylib（codesign + dlopen） |
| `scripts/build-tcc-ref-pe.sh` | Linux 交叉：x86_64 PE 参考 tcc + fixture dll（结构校验） |
| `scripts/check-all.sh` | Linux 端到端校验：fixture→.so + 5 TU 逐 token 对比 |
| `.github/workflows/jtcc.yml` | 4 个任务：Linux ELF 全链路+parity、macos-14 dylib、Linux PE 交叉构建、windows-latest dll 加载 |

实测结果（[CI #39052382088](https://github.com/script-gd/probe-env/actions/runs/39052382088)，四任务全绿）：

1. 最小保留集构建出的 tcc 通过 gdcc u8 探针（Linux）；
2. Linux x86_64：fixture → `libprobe.so`（ELF），`dlopen` 成功；
3. macOS arm64（Apple Silicon）：fixture → `libprobe.dylib`（Mach-O arm64），`codesign -v` 通过，`dlopen` 成功；
4. Windows x86_64：fixture → `probe.dll`（PE32+），导出 `gdextension_entry`、导入 `msvcrt.dll`+`kernel32.dll`、含 `.pdata`/`.tls`，windows-latest 上 `LoadLibrary` 成功；
3. Java 预处理器对 5 个 TU 的输出与 `tcc -E` **逐 token 一致**：entry 103,524 / godot_binding 531,575 / minicoro 10,647 / gdcc_coroutine 104,054 / gdcc_hrx 107,400，合计 **856,800 tokens，0 差异**；
4. 对抗性探针（u8 邻接/`u8##"paste"`/`#define u8 "macro"` 遮蔽/`#undef` 恢复/字符串化含转义/自引用宏/`#if defined` 组合/8KiB+ 多字节字符串）全部一致。

为达到一致，PoC 实现中复刻的 tcc 语义细节：宏展开内 `__FILE__`/`__LINE__` 取**调用点**位置；行拼接的物理行号补偿；stringize 保留原始空白（`ws` 标志）；placemarker 粘贴规则（`A##∅=A`、`∅##∅=∅`、GNU `,##__VA_ARGS__`）；glibc `math.h` 的 `__MATH_PRECNAME` 嵌套粘贴。

## 8. 工作量估算与分阶段计划

外部参照：chibicc（多趟、约 1 万行、C11+x86-64）单人 93 次提交；8cc 约 5 千行 40 天；pcc 前端约 1.5 万行 + 每目标 3 千行；tcc 全量约 10 万行对应 OpenHub COCOMO 约 28 人年。**没有**任何语言对 tcc 的重写先例（只有 c2rust 机械转写与绑定），也没有具备原生代码生成+链接能力的成熟 Java C 编译器（Java 生态里最完整的是 Eclipse CDT 的解析器/预处理器，无代码生成）。

按"忠实移植"路线估算（Java 代码量通常不少于 C 源）：

| 阶段 | 内容 | 规模估计 | 状态 |
| --- | --- | --- | --- |
| M0 | 仓库结构、最小保留集、参考 tcc、CI | — | ✅ 本轮完成 |
| M1 | Java 预处理+词法，fixture 全量 token 对齐 | ~1k 行 | ✅ 本轮完成 |
| M2 | Java 解析器+类型系统（`tccgen.c` 的声明/表达式/语句，不含代码生成） | ~5-6k 行 | 未开始，最大风险 |
| M3 | x86_64 代码生成（`x86_64-gen.c` 忠实移植 + `SValue` 栈机） | ~2.5-3k 行 | 未开始 |
| M4 | ELF `.o` 写出（`tccelf.c` 对象部分）+ fixture 5 TU → 5 个合法 `.o`（`readelf` 校验） | ~2k 行 | 未开始 |
| M5 | ELF `.so` 链接（GOT/PLT/重定位/dynsym，共享链接核心）→ fixture 链接为可 `dlopen` 的 `.so` | ~2-2.5k 行 | 未开始 |
| M6 | arm64 ELF 后端+链接（字节 blob 替代汇编器的前提下） | ~2.5-3k 行 | 未开始 |
| M7 | Mach-O dylib 产出（`tccmacho.c` 移植：bind/rebase、导出 trie、LC_ID_DYLIB、`.tbd` 解析；codesign 走 `ProcessBuilder`；复用 M5 的共享链接核心与 M6 的 arm64 后端） | ~2.5-3k 行 | 未开始 |
| M8 | PE dll 产出（`tccpe.c` 移植：导入/导出表、`.reloc`/`.tls`/`.pdata`、`.def` 解析；复用 M3 的 x86_64 后端与 Windows ABI 分支；win32 头文件/def/CRT 作数据嵌入） | ~2-2.5k 行 | 未开始 |
| M9 | 接入 gdcc（替换 FFM 后端，过 gdcc 测试集与 Android/macOS/Windows 实测） | 集成工作 | 未开始 |

**总计估计约 1.9 万–2.4 万行 Java 的忠实移植工作量**（含 Mach-O 与 PE），其中 M2（解析器）是决定性阶段。M1 的成功（预处理器是公认最难的单点之一）表明忠实移植路线成立，但不能外推解析器的难度——`tccgen.c` 的声明语法、typedef 二义性、语句表达式重放、VLA 机器都比预处理器更复杂。

## 9. 备选方案对比

| 方案 | 规模 | 风险 |
| --- | --- | --- |
| **Java 重写 tcc 子集（本方案）** | 1.5-2 万行 Java | 高工作量、高可控性；消除 FFM，天然支持 Android 进程内编译与双后端 |
| 维持 FFM + 补 Android bundle/桥接（上一轮路径） | 小 | 需为每个 Android ABI 构建 libtcc 并解决 FFM 在 Android 的可用性 |
| zig 后端（gdcc 已支持） | 已有 | 依赖外部 zig 工具链，非纯 Java |
| 预编译 `.o` 缓存分发 | 小 | 失去设备端热重载能力，违背 gdcc 场景目标 |

## 10. 子代理论断核验记录

本轮对三个子代理（两个 explore 架构分析 + 一个 scout 外部调研）的关键论断全部做了亲自核验：

- ✅ FFM 恰绑定 10 个函数、生产路径仅用 `-std=c11`、OBJ→DLL 两阶段（亲读 `TinyCcLibrary.java:86-95`、`TinyCcCompiler.java:275-470`）。
- ✅ 最小保留集行数（亲跑 `wc -l` 复核：核心 19,489；x86_64 2,690；arm64 2,687）与"单 C 二进制单后端"机制（亲读 `tcc.h:366-393`）。
- ✅ `minicoro.h` 文件级 `__asm__`（亲读 `:823-877`）——**修正了 explore 代理把汇编器判为 OPTIONAL 的结论**：对 ELF 目标实为必需，除非采用字节 blob 替代（§6.1）。
- ✅ `tcc.h` 无条件包含 `dwarf.h`/`stab.h`（构建时报错实证），这两个纯常量头因此保留在 vendor 中。
- ✅ Mach-O 增量（本轮追加）：`macho_output_file` 驱动 tccelf 共享链接核心（亲数 74 处调用）、`tcc_macho_add_destructor` 手工汇编跳板（minicoro destructor 属性依赖）、`.tbd` 简化文本解析、`libxcselect` SDK 探测、`CONFIG_CODESIGN` 默认开启且经 `system()` 调 `codesign`——均亲读源码确认；dylib 产出链路在 macos-14 CI 实证。
- ✅ PE 增量（本轮追加）：`tcc_output_file` 对 `TCC_OUTPUT_OBJ` 全目标走 ELF 对象（tccelf.c:3223-3228，通用中间 IR）；`pe_output_file` 无 OBJ 分支（亲读 tccpe.c:2252-2304）；`tcccoff.c` 仅 C67（libtcc.c:47 包含守卫）；LLP64（tcc.h:416-419）；fixture 用 `__declspec(dllexport)`（gdcc_helper.h:20）；dll 链路在 ubuntu 交叉构建 + windows-latest 加载实证。
- ✅ `tccdefs.h` 在 `-std=c11` 下定义 `__STDC_NO_THREADS__` 等（亲读头文件 + PoC 预处理行为一致）。
- ⚠️ scout 关于"tcc 在 Linux 不定义 `__GNUC__`"的论断与 fixture 行为自洽（PoC 黄金对齐实证），未再单独复核 `tccdefs.h:131-134` 原文。
- ⚠️ scout 的外部项目清单（chibicc 各语言移植、C2Eif/mtSystems 等）未逐条复核其当前状态，报告中仅作为规模参照使用。

## 11. 复现方式

```bash
git checkout research/java-tinycc-rewrite
./scripts/build-tcc-ref.sh         # Linux：x86_64 ELF 参考 tcc（tmp/ 内，不污染环境）
./scripts/check-all.sh             # Linux：fixture→.so + jtcc/tcc -E 逐 token 对比
./scripts/build-tcc-ref-macho.sh   # macOS arm64：fixture→.dylib + codesign + dlopen
./scripts/build-tcc-ref-pe.sh      # Linux 交叉：x86_64 PE 参考 tcc + fixture→probe.dll
```

临时产物全部在 `tmp/`（已 gitignore）。未修改 `../gdcc`，未在宿主安装任何软件（JDK/gcc/python3 为既有环境），重工具链验证在 GitHub Actions 完成（Linux x86_64、macOS arm64、Windows x86_64 三端 runner）。

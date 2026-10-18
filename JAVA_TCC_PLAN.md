# Java 重写 TinyCC 分阶段实施计划

日期：2026-10-18。分支：`research/java-tinycc-rewrite`。前置调研：`JAVA_TCC_REPORT.md`。

## 0. 目标与最终验收

**目标**：用 Java 重写 gdcc vendored TinyCC（`43c7708b`，含 gdcc u8 补丁）的编译+链接子集，**包含 Java 重写的 x86_64 汇编器**，使 gdcc 无需任何 FFI/native 进程即可工作。

**最终验收门（Definition of Done）**：在 Linux x86_64 上，Java 版编译器把完整 gdcc 输出 C 项目（本仓库 `fixture/`，5 个编译单元）编译链接为 `.so`，并被官方 Godot Linux 编辑器加载：注册生成类、实例化、GDScript→原生调用返回正确结果（复用上一轮 Android 的断言探针模式，`@tool EditorPlugin` + `GDCC_CASE_PASS` 日志断言）。CI 证据为准。

范围纪律（与调研报告一致）：删 JIT（`-run`/`tccrun`）、删调试信息（DWARF/stabs）、删 32 位后端、删 iOS；保留 ELF + Mach-O + PE 三种格式与 x86_64 + arm64 两个后端；C 库与 `libtcc1.a` 以数据/预编译对象形式使用，不重写。**本计划主线为 Linux x86_64 端到端打通**；arm64/Mach-O/PE 为后续扩展阶段。

## 1. 测试基础设施（已就绪，全部 CI 常绿）

| 套件 | 内容 | 基线（参考 tcc，最小保留集构建） | jtcc 当前状态 |
| --- | --- | --- | --- |
| `tests/tinycc/tests2/` | 142 个上游编译执行测试（NORUN 模式：编译 exe → 运行 → 对比 `.expect`） | **122 PASS / 0 FAIL / 18 SKIP**（skip=JIT/bcheck/异架构/上游已知坏） | 随阶段逐个转为 jtcc 运行 |
| `tests/tinycc/pp/` | 24 个预处理器测试（`-E -P` 文本对比） | 24/24 | **21/21 通过**（3 个 `.S` 汇编模式留待 M4） |
| fixture token parity | 5 TU 的 `tcc -E` 逐 token 对比（856,800 tokens） | — | **完全一致** |
| fixture 端到端 | 5 TU → `.so`/`.dylib`/`.dll` → 加载 | Linux dlopen ✅ / macOS codesign+dlopen ✅ / Windows LoadLibrary ✅（均为参考 tcc） | 待 M6-M7 |

关键运行器：`scripts/run-tests2.sh`（NORUN 语义复刻：含 `-dt` 多片段复现、多 TU 测试、146 TLS 系统链接器互操作、148 ELF 链接器特性矩阵）、`scripts/run-pp-tests.sh`、`scripts/check-all.sh`、`scripts/build-tcc-ref{,-macho,-pe}.sh`。

## 2. C→Java 架构映射与移植纪律

忠实移植（不做架构创新），模块对应关系：

| C 模块（行数） | Java 类 | 状态 |
| --- | --- | --- |
| `tccpp.c` (3,972) 词法+预处理 | `jtcc.Lexer` / `jtcc.Preprocessor` / `jtcc.Eval`（~1.3k 行） | ✅ 完成（M1） |
| `tccgen.c` (9,049) 解析+类型+通用代码生成 | `jtcc.parse.*`（Parser/TypeTable/SymTab/Init）、`jtcc.gen.ValueStack` | M2-M3 |
| `x86_64-gen.c` (2,279) 代码生成 | `jtcc.backend.x86_64.X86_64Gen` | M3 |
| `i386-asm.c` (1,750) + `x86_64-asm.h` (559) x86_64 汇编器 | `jtcc.backend.x86_64.X86_64Asm` | M4 |
| `tccelf.c` (4,215) ELF 对象/链接 | `jtcc.elf.*`（Section/SymTab/ObjWriter/SoLinker） | M5-M6 |
| `libtcc.c` (2,253) 驱动/状态/分配 | `jtcc.TccState` / `jtcc.Driver`（OO 化，无全局态） | 随各阶段 |
| `tcc.h`/`tcctok.h`/`elf.h` 常量 | `jtcc.Tokens`/`jtcc.ElfConst`（按用量生成常量类） | 随各阶段 |
| `include/*.h` + `libtcc1.a` + `win32` 数据 | 资源文件原样打包 | ✅ 已在 vendor/ |

移植约定：
- C 全局变量 → 实例字段集中于 `TccState` 派生类（Java 版天然可双后端/多格式共存，无 C 版单后端限制）。
- `setjmp/longjmp` 错误模型 → `PpError` 式受控异常 + try/finally；GC 消解 `stk_push/pop` 手工清理。
- 指针+`dynarray` → `ArrayList`/数组+显式索引；`CString` → `StringBuilder`/byte 缓冲。
- 每阶段用参考 tcc 的输出做差分对照（token 流、`.o` 字节结构、`.so` 行为）。

## 3. 阶段计划

### M2 — C 解析器与类型系统（无代码生成）【最大单体，决定性阶段】

移植 `tccgen.c` 的语法/语义部分：声明器（含 typedef 二义性消解的 lexer hack）、表达式优先级栈、语句、初始化器（含指定初始化器与复合字面量）、类型表（`_Bool`/`long long`/`long double`/VLA/匿名 struct-union/位域解析但可拒绝生成）、`__attribute__` 解析、GNU 语句表达式与 `__typeof__`、`_Generic`、`_Static_assert`、u8 字面量语义。

- **产出**：解析 fixture 5 个 TU 与 tests2 全量源文件到内部表示，错误/告警行为对齐。
- **门**：(a) `tests2/60_errors_and_warnings`、`96_nodata_wanted`、`149/150` 等诊断用例的预期告警/错误集合与参考 tcc 一致（编译即拒/即过）；(b) fixture 5 TU 解析零未实现路径；(c) 02/21 等 pp 套件保持绿。
- **估**：5-6k 行 Java。
- **风险**：`decl()`/`unary()`/`gexpr()` 的交织递归、初始化器的 `decl_designator` 状态机、VLA 的 token 回放（`begin_macro`/`end_macro` 已在预处理器有等价物可复用）。

**M2 当前状态（2026-10-18，二次深化后）**：解析器骨架已落地（`jtcc.cc.Parser`/`CType`/`Sym`/`ConstEval` + `jtcc.ctok.CTokenizer`，忠实对应 tccgen.c 的 `decl`/`type_decl`/`post_type`/`block`/表达式优先级链结构）。**tests2 范围内 122/122 全部解析接受、fixture 5/5 全部解析通过**（CI 门控：`scripts/check-parse-accept.sh`）。二次深化完成：完整 struct/union 布局（`struct_layout` 移植：字段偏移/对齐/匿名成员/位域解析）、`offsetof` 求值（含匿名成员递归）、`sizeof` 求值（类型名与 `((T*)0)->member` 成员模式）、块作用域 struct 标签（C 作用域遮蔽/恢复）、LP64 `long` 归一、链式 typedef 解析——**fixture 的全部 `GDCC_GODOT_ASSERT_SIZE/ASSERT_LAYOUT` 静态断言在 M2 即可真值求值（警告归零）**。剩余 M2 深度工作：诊断消息逐字对齐（60/96 用例的告警文本）、初始化器的语义化解析（当前为跳过式，M3 接入）、位域布局。

### M3 — x86_64 代码生成

**M3 当前状态（2026-10-18）**：`jtcc.backend.x86_64.VGen`/`X64Enc` + `jtcc.elf.*` 已落地 SysV AMD64 整数值栈机、寄存器分配（含温度寄存器溢出到帧槽）、函数调用（PLT32、参数溢出防 RAX 覆盖）、结构体按值（`genStructCopy`、8/24 字节 fixture 不透明 blob 的返回/传参/赋值）、复合字面量、语句表达式（捕获尾表达式值）、switch/比较/指针运算、全局 .data/.bss/COMMON。**fixture 5 TU 全部编译为合法 ELF `.o`**。tests2 执行门控 27 项通过（CI：`scripts/run-tests2-jtcc.sh` 基线子集）。**已知缺口**：浮点 SSE 代码路径（当前浮点常量压占位值；fixture 895 处 `godot_float` 需 SSE）、位域代码生成、数组初始化器边界推导、VLA、复杂表达式深度寄存器压力（33_ternary_op yarpgen 压力用例）。

移植 `x86_64-gen.c` + `tccgen.c` 的代码生成一半（`gv/gv2/gen_op/gstore/gjmp`、SValue 栈机、寄存器分类 `reg_classes`、SysV AMD64 ABI 分类与 `gfunc_call/gfunc_prolog/gfunc_epilog`、struct 值传递/返回、`__builtin_va_*` 与 `__va_arg`、VLA 的 `gen_vla_*`、原子内建降级为 `__atomic_*` 助手调用）。

- **门**：tests2 中选定核心子集（00-09 基础、10-13 算术/指针/数组、22/24 浮点与数学库、42 函数指针、63-66 结构体/联合、76-79、86-97 的 ABI 相关项）编译为 `.o`，用系统 `cc` 链接运行，输出对齐 `.expect`。
- **估**：2.5-3k 行 Java。
- **风险**：SSE/x87 混合返回（`gfunc_sret`）、`long double` x87 代码路径、寄存器压力下的栈溢顺序——全部以测试差分兜底。

### M4 — x86_64 汇编器（Java 重写，明确保留）

**M4 当前状态（2026-10-18，聚焦子集）**：`jtcc.backend.x86_64.Asm` 落地文件级 `__asm__` 裸块的聚焦汇编器（AT&T 语法）：movq 全形式（reg↔reg/reg↔mem+位移）、leaq `%rip` 相对局部标签、jmp `*reg`/`*mem`、ret、`.L` 局部标签与前向引用回填、`.globl` 导出为 FUNC 符号。minicoro 的 `_mco_wrap_main`/`_mco_switch` 上下文切换块汇编出正确字节。**fixture 5 TU → `.o` → 链接成 `libprobe.so` → `dlopen` 成功**。完整 `i386-asm.c` 忠实移植（操作数约束、内联 asm）留待后续。

移植 `i386-asm.c` + `x86_64-asm.h` + `tccasm.c` 的文件级 `__asm__` 路径（`asm_instr` 的操作数约束解析只做 fixture 用到的子集；文件级裸块为主）。

- **驱动需求**：`minicoro.h:823-877` 的 `_mco_wrap_main`/`_mco_switch` 上下文切换块（文件级 `__asm__`，AT&T 语法、`.type`/`.hidden`/`.size` 指令、`%rip` 相对局部标签）。
- **门**：(a) `tests2/85_asm-outside-function`、`127_asm_goto`；(b) `pp/` 的 12/13/23 `.S` 汇编预处理测试（汇编器模式预处理器）；(c) `chkstk.S` 不需要（PE 专用），`lib/alloca*.S`/`atomic.S` 能汇编（`.S` 完整管线）；(d) minicoro 上下文切换块汇编出的字节与参考 tcc 逐字节一致。
- **估**：2-2.5k 行 Java。
- **说明**：这是比"预编译字节 blob"更通用的路径，用户明确要求保留。

### M5 — ELF `.o` 写出

**M5 当前状态（2026-10-18）**：`jtcc.elf.ElfObj`/`Section` 落地——节区生长缓冲（含序言回填预留）、符号表（局部符号排前 + 重定位索引重映射）、`.text/.data/.rodata/.bss`、重定位（`R_X86_64_64/PC32/PLT32/GOTPCREL`）、COMMON 暂定定义、static 全局 LOCAL 绑定、前向引用就地调和。fixture 的 5 个 `.o` 均为合法 ELF 且被系统 `cc`/`ld` 接受。

移植 `tccelf.c` 的 `elf_output_obj` 路径：节区（`Section` 生长缓冲）、符号表、重定位项（`greloca`）、`stt` 类型/绑定、`stt_common`/`.bss`、`.rodata` 合并、COMDAT 简化。

- **门**：tests2 子集 `jtcc → .o → cc 链接 → 运行` 全过；`readelf -a` 与参考 tcc `.o` 结构对比；`146_tls_extern`（系统 `cc` 链接互操作：tcc 产物的未定义 TLS 符号）通过。
- **估**：2k 行 Java。

### M6 — ELF `.so` 链接器

**M6 当前状态（2026-10-18，过渡方案）**：当前用系统 `cc -shared -Wl,-Bsymbolic` 链接 5 个 jtcc `.o` 为 `libprobe.so`（非自研链接器）。jtcc 侧已就绪 PIC：调用走 PLT32、非局部数据符号走 GOTPCREL、本地符号直接 PC32。**`.so` 可被 `dlopen` 加载且符号全解析**。自研 ELF `.so` 链接器（`.dynsym`/GOT/PLT 生成等）仍是后续工作。

移植 `tccelf.c` 的 `elf_output_file`(DLL 分支)：`.dynsym/.dynstr/.hash/.gnu.hash`、`.dynamic` 标签、GOT/PLT 生成（`build_got_entries`/`create_plt_entry`/`relocate_plt`）、`.rela.dyn/.rela.plt`、`R_X86_64_*` 应用、DT_NEEDED 解析（`libc.so` 链接器脚本子集：仅 `GROUP(...)` 文件列表）、`libtcc1.a` 归档成员按需提取、`tcc_load_archive`/`.so` 读取、`-soname` 等。

- **门**：tests2 全基线 122 项改为纯 jtcc 链路（编译+链接 exe 或直接 so+cc 包装运行）；`148_linker_symbols`（`.o`+`.so`+exe 六组合矩阵）通过；fixture 5 TU → `libprobe.so` → `dlopen` + 调用探测函数成功。
- **估**：2-2.5k 行 Java。
- **风险**：`.hash`/`.gnu.hash` 与 glibc 符号版本（`printf@GLIBC_2.2.5`）；`R_X86_64_COPY`/规范 PLT 只需 `.so` 场景的最小子集。

### M7 — Godot 端到端验收（本计划终点）

CI 下载官方 Godot Linux 编辑器，载入由 jtcc 编译的 fixture `.so`，运行 `@tool EditorPlugin` 断言（复用上一轮 Android 探针的 GDScript 断言与 logcat 模式，桌面版用 `--headless` 或 xvfb）。

- **门**：日志出现 `GDCC_CASE_PASS::value=42/ARM64→x86_64/true` 与 `EDITOR_RESULT::PASS`。
- **估**：集成工作（harness 已有先例）。

### M8+ — 扩展阶段（终点之后）

- M8：arm64 后端 + arm64 汇编器（`arm64-gen.c`/`arm64-asm.c`/`arm64-link.c`，AAPCS64、`SHT_REL` 重定位）→ Android/Linux aarch64 `.so`。门：aarch64 CI（如 GitHub arm64 runner）+ 上一轮 Android 编辑器链路复测。
- M9：Mach-O dylib（`tccmacho.c` dylib 子集 ~2.4k 行 + codesign 子进程）→ macOS arm64。
- M10：PE dll（`tccpe.c` 子集 ~2.1k 行 + LLP64 类型参数化 + win32 数据文件）→ Windows x86_64。
- M11：接入 gdcc（实现 `TinyCcNative` 同构的纯 Java 后端，10 个 API 的语义等价物 + 诊断回调），跑 gdcc 自身测试集。

## 4. 估算汇总与节奏

| 阶段 | 估计 | 累计 |
| --- | ---: | ---: |
| M1 预处理（已完成） | ~1.3k | 1.3k |
| M2 解析器 | 5-6k | ~7k |
| M3 x86_64 代码生成 | 2.5-3k | ~10k |
| M4 x86_64 汇编器 | 2-2.5k | ~12.5k |
| M5 ELF .o | ~2k | ~14.5k |
| M6 ELF .so 链接 | 2-2.5k | ~17k |
| M7 Godot 验收 | 集成 | — |

主线（M2-M7）合计约 **1.5-1.7 万行 Java**；M8-M10 再加约 7-8k。参考点：chibicc（C 约 1 万行实现近似 C11 单后端）；本计划是在**行为已锁定的参考实现 + 全量测试差分**下做忠实移植，风险主要在 M2 的体量与 M3 的 ABI 细节。

## 5. 每阶段通用纪律

1. 所有阶段先在 CI 绿（Linux；macOS/Windows 任务保持现有参考链路绿）。
2. 任何行为不确定处，以 vendored 参考 tcc 的实际输出为仲裁（先写探针再实现）。
3. 每阶段结束更新本文件状态列与 `JAVA_TCC_REPORT.md` 的对应结论。
4. tmp/ 继续只放临时产物；测试与脚本全部入库可复现。

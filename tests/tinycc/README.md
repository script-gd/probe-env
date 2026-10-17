# tinycc 上游单元测试

来源：<https://github.com/TinyCC/tinycc.git> `mob` 分支，pin 提交
`43c7708b85681a2fd4451c8a541af4494a8919b2`（0.9.28rc，与 gdcc vendored tinycc 同一 pin）。

引入范围（按 Java 重写验证需求裁剪，未引入的部分见下）：

- `tests2/` — 142 个编译执行测试（`NN_*.c` + `.expect`），本仓库以 **NORUN 模式**运行
  （`tcc test.c -o a.exe && ./a.exe` 对比输出），不使用上游默认的 `-run` JIT 路径；
- `pp/` — 24 个预处理器测试（`tcc -E -P` 文本对比 `.expect`），直接门控 Java 预处理器；
- `tcctest.c`/`tcctest.h` — 经典单文件大测试（对比 gcc 参考输出）；
- `abitest.c` — ABI 测试（需要 gcc 构建参考一半，计划中备用）。

未引入：上游 `Makefile`（含平台 skip 逻辑，已由 `scripts/run-tests2.sh` 内联替代）、
`boundtest.c`/`libtcc_test*.c`（JIT/边界检查，范围外）、`arm-asm-testsuite.sh`（arm32）、
`asm-c-connect-*`/`msvcrt_start.c`/`test-win32.bat`（Windows  harness）、`gcctestsuite.sh`
（gcc torture 子集需要完整 make 环境，后续阶段可再引入）。

许可：tinycc 为 LGPL-2.1+（见 `COPYING`，重授权讨论见 `RELICENSING`）。本目录仅用于测试。

# proot、glibc 与时间都花在了哪里

`feat/performance-fixes-2` 的研究笔记（2026-09-30）。问题是：Steam 与游戏应当继续跑在 proot 下、直接跑在 glibc 上，还是两者混合；以及怎样才是最快且正确的配置。除非另有说明，以下所有测量都在 Snapdragon 8 Elite Gen 5（SM8850，内核 6.12.38-android16，Android 16）上经 adb 完成。

关于 AYN Thor（Android 13）上 Steam SD 分配的测量，见
[Thor 存储调查](thor-sd-storage-investigation.md)。它区分了观测到的分配包装器耗时、FUSE 目录访问与原生 exFAT 元数据等待；它并不给出完整安装下 PRoot 开销的百分比。

## 简短结论

- **不要去掉 proot。** 在 Android 上它干两件事：翻译路径；并回答每一个被 Android 应用 seccomp 策略拦截的系统调用（`rseq`、`set_robust_list`、`faccessat2`、`fchmodat2`、`openat2`、`set*id` 系列、`futex_waitv`……）。
  - 没有 tracer 的原版 Arch glibc 在第一次 `pthread_create` 时就会被 `SIGSYS` 杀死。即使装了进程内 `SIGSYS` 处理器也一样，因为 glibc 发起这些调用时屏蔽着全部信号。
  - 走"纯 glibc"意味着要构建 Termux 式的 glibc（重定位前缀、改写被拦截的调用）、为 Steam 自更新的每个 ELF 提供 exec 垫片、为 Chromium 提供 `/proc/self/exe` 模拟器、为 `/tmp`、`/dev/shm`、`/mnt/*` 以及伪造的 `/proc`、`/sys` 文件做路径重定向，还要处理 Wine 硬编码的 `/tmp/.wine-<uid>`。
  - 那是另一个独立项目，而且失败模式是崩溃而不是慢调用。
- **游戏过程中 proot 几乎不花什么代价**，这归功于补丁 0004–0013。GPU 提交（`ioctl`）、`futex`、`read`/`write`、`mmap`、`ppoll`/`epoll`、`sendmsg`/`recvmsg` 以及 `clock_gettime`（经 vDSO）在过滤器下都以原生速度运行。
- **proot 仍然要花钱的是**每一个*路径*系统调用、`execve`、`brk`、线程与进程创建，以及每个被 Android 拦截的调用：**每个 15–60 µs**，而原生约 0.5 µs。所有这些都经过**一个 tracer 线程**，其吞吐上限约为每秒 75–95k 次陷阱。Steam 启动、游戏加载与资产流式传输、Proton 的 Python，以及任何多线程同时爆发的请求都会撞上它。
- **这些开销的大头不是 proot 的代码，而是每次陷阱的两次跨核唤醒。** 任何进程外机制都要付这笔钱：ptrace 如此，seccomp 用户通知也如此（实测相同）。唯一的真解法是不离开进程。
- **建议：proot 加一条进程内快速路径。** 一个预加载库在进程内部回答常见的路径调用。它经由一页 trampoline 发出这些调用（proot 的过滤器会放行该页），凡是不能证明与 proot 行为一致的调用都回退给 proot。
  - 原型在本分支：`tools/proot/fastpath/` 与 `patches/0014`。
  - 速度：`stat` 25 µs → 0.6 µs，`open` 25 µs → 0.9 µs，缺失文件查找 23 µs → 1.6 µs。八个线程做 `stat` 从 89k/s 提升到 3.6M/s。
  - 正确性：一组刁钻路径的等价性测试产生逐字节一致的结果。
  - 它只使用应用策略允许的系统调用。

## 一个会话今天是怎么跑起来的

```
SessionService ─HostProcess─▶ libproot.so（唯一的 tracer 进程，2 秒后 nice -6）
  └─ /usr/bin/env -i … droiddeck-session steam
       └─ gamescope ─▶ droiddeck-session（BL_INSIDE）─▶ steamrtarm64/steam（+ steamwebhelper/CEF 树）
            └─ reaper ─▶ droiddeck-game-env ─▶ droiddeck-proton ─▶ Valve ARM64 Proton（python）
                 └─ wine（arm64ec，FEX 作为 DLL 进程内加载）─▶ wineserver、game.exe、…
```

- 树中的每个进程，从会话脚本到游戏，都是同一个 proot 的 tracee。
- 选项包括 `--kill-on-exit`、`--kernel-release=…`（kompat，用于主机名）、`-i uid:uid`（fake_id0，用于 Xwayland 的 `setgid`/`setuid`），以及约 25 个绑定：`/dev`、`/proc`、`/sys`、伪造的 `/proc` 与 `/sys` 文件、GPU 节点、`/dev/shm`、存储与游戏库。
- `/etc/ld.so.preload` 把 `libblsession.so`（`tools/linuxfs/preload/*.c` 垫片）加载进每个 guest 进程。
- Flatpak 沙箱（`BwrapSpawner`）已经运行在**独立的** proot 实例中，从 Android 侧启动。

### proot 对每个被追踪的系统调用做了什么

tracee 撞上 proot 的 seccomp 过滤器，返回 `SECCOMP_RET_TRACE`。然后：

1. 内核停住 tracee 并唤醒 tracer：一次跨核 IPI，还可能伴随退出深度空闲状态。
2. tracer 读取寄存器（`PTRACE_GETREGSET`）并读取路径（`process_vm_readv`）。
3. 对着 rootfs 与绑定关系规范化路径：每个路径分量一次 `lstat`，或在打了补丁 0002 后用一次 `O_PATH` 打开加一次 `/proc/self/fd` readlink。
4. 把宿主路径写进 tracee 的暂存区（`process_vm_writev`）并更新寄存器。
5. 恢复 tracee，这又是一次跨核唤醒。

标记了 `FILTER_SYSEXIT` 的调用在返回途中还要再停一次，例如翻译 `readlink` 或 `getcwd` 的结果。被 Android 拦截的系统调用则以 `SIGSYS` 信号停止的形式到达，由 proot 模拟执行（`src/tracee/seccomp.c`）。fork、clone、exec 与线程退出各自还会增加 `PTRACE_EVENT_*` 停止。

## 测量

工具在 `tools/proot/bench/`；`run-device.sh` 说明了设备上的布局。

**注意：** `adb shell` *不在*应用 seccomp 过滤器之下。机制开销相同，但那些 Android 只对应用拦截的系统调用（见下文）无法在那里复现。

**注意：** 手机进入 Doze（熄屏）时所有内容慢约 7 倍。以下所有数字都在屏幕亮起时测得。

### 按调用计——今天会话中的 proot

`sysbench` 分为打了补丁（`patched`）与未打补丁（`vanilla`）两列，使用会话自身的 proot 选项与相近的一组绑定。

| 调用 | 直接 glibc | termux proot（原版） | 本仓库的 proot（0001–0013） | + 快速路径（0014） |
|---|---|---|---|---|
| getppid / futex / ioctl / pread（未被追踪） | 0.1–0.4 µs | futex **16.6**、fstat **32.8**、ioctl **30.5** µs | 0.2–0.4 µs | 0.2–0.4 µs |
| stat，深层绝对路径 | 0.8 µs | 41.7 | 25.3 | **0.6** |
| open + close | 1.7 µs | 40.4 | 25.3 | **0.9** |
| access | | 27.2 | 26.2 | **1.0** |
| 相对 dirfd 的 fstatat | | 45.5 | 26.8 | **1.9** |
| 缺失文件的 stat | | 38.9 | 22.9 | **1.6** |
| readlink /proc/self/exe | | 42.8 | 38–43 | (proot) |
| getcwd / uname / brk | | 31–35 | 30–33 | (proot) |
| memfd_create | | 20.4 | 18.6 | (proot) |
| pthread create+join | | 58 | 48 | 48 |
| fork + exec + wait | ~1.1 ms | 1.16 ms | 0.70–0.78 ms | +0.2–0.4 ms（见待办） |

仓库中已有的补丁见到了收益：kompat（0011）与 fake_id0（0012）把 `futex`、`fstat` 和 `sendmsg` 移出了陷阱清单，0013 对 `ioctl` 做了同样的事。

### 一次陷阱按机制计的成本（`mechbench`；处理器什么都不做）

| 机制 | 成本 |
|---|---|
| 原生系统调用 | 0.1 µs |
| 0 / 50 / 150 / 300 条比较的 seccomp 过滤器，允许的调用 | **没有差别**（内核 ≥5.11 按系统调用号缓存恒定判定） |
| ptrace + seccomp，tracer 与 tracee 在**同一核心** | 6.2 µs（含寄存器读写为 6.7 µs） |
| ptrace + seccomp，**未绑核**（应用运行时的方式） | 15.5 µs |
| ptrace + seccomp，不同核心（同簇 / 跨簇） | 34–36 / 42–61 µs |
| tracer 自旋的 ptrace，不同核心 | 20 µs（tracee 一侧仍需被唤醒） |
| seccomp 用户通知（"现代 proot"设计） | 同核 5.7 µs，跨核 34–58 µs，未绑核 43 µs；`SYNC_WAKE_UP` 帮不上忙 |
| **进程内 SIGSYS**（`SECCOMP_RET_TRAP`） | **0.83 µs** |
| openat2 `RESOLVE_IN_ROOT`（内核态 chroot 查找） | 1.0 µs，但**在应用中被拦截** |

要点：
- 同核约 14 µs 的陷阱里，tracer 自身的工作只占约 8 µs，其余全是调度。
- 换成 seccomp 用户通知等于重写却毫无收益。
- 过滤器的长度不重要，重要的是别破坏内核的缓存：
  - **任何针对参数或指令指针的检查都必须排在系统调用号分发之后。** 0014 的第一版先检查地址，结果会话中每次系统调用都要跑完整个过滤器，每次 exec 多花 +0.5 ms。
  - 补丁 0004 与 0013 已经遵循这条规则。

### 一个 tracer 管全部（`parstat`：N 个线程做 `stat`）

| | 1 线程 | 4 线程 | 8 线程 |
|---|---|---|---|
| 单个 proot | 37k/s，27 µs | 74–95k/s，42–54 µs | 68–89k/s，每个 **90–118 µs** |
| 两个 proot，各 2 线程 | | 合计 187k/s，21 µs | |
| proot + 快速路径 | 1.64M/s，0.6 µs | 2.76M/s | **3.60M/s，2.2 µs** |

因此，多线程上的路径调用爆发会在单一 tracer 后面排队。CEF 加载资产、Proton 的 Python、游戏在工作线程上流式读文件，以及着色器缓存线程都会产生这样的爆发。

### CPU 放置

负载用 `taskset` 运行；没有快速路径。时间为每次被追踪调用的耗时，最后一列是一次 fork + exec。

| 亲和性 | stat | exec |
|---|---|---|
| 默认 | 25.8 µs | 750 µs |
| 单核 | 14.0 µs | 370 µs |
| 两颗大核 | 14.6 µs | |
| 六核簇 | 25.9 µs | |

当调度器能把 tracer 与 tracee 放在一起时，唤醒亲和的放置有帮助；把它们拆开则有害。**永远不要把 tracer 固定到远离其 tracee 的核心。** 今天 `raiseTracer` 只调整它的 nice 值，这是正确的。

### 整程序

时间单位为 ms，均已扣除 proot 约 40 ms 的启动开销。

| | 直接 glibc | 原版 proot | 本仓库的 proot | + 快速路径 |
|---|---|---|---|---|
| python3：10 个标准库导入 | 75–89 | ~130 | ~110 | ~90（879 次路径调用中 878 次在进程内回答） |
| 2.7k 个文件的 tar | 10–40 | ~180 | ~80 | ~20 |
| 200 次 `true` 的 fork+exec | 230 | ~600 | ~500 | ~500 |
| 遍历 2.7k 个文件的 find | 16–23 | 噪声大 | 噪声大 | 不变：glibc 的 fts 调用的是内部入口 |

## Android 的应用 seccomp 策略比 proot 更有决定权

应用过滤器由 bionic 的 `SYSCALLS.TXT` 生成，减去黑名单，加上白名单。无论 `targetSdk` 是多少，所有应用都一样；未列出的任何调用得到 `SECCOMP_RET_TRAP`，即 `SIGSYS`。过滤器会叠加，最严格者获胜：被拦截的调用永远到不了 proot 的 `RET_TRACE`。它以 `SIGSYS` 停止的形式到达，由 proot 回答（通常是 `ENOSYS`）。

对照 bionic `android16-release` 核查：

| 系统调用 | 应用策略 | 谁在调用 |
|---|---|---|
| `rseq`、`set_robust_list` | **拦截** | glibc，在每个线程启动时，且此时屏蔽全部信号 |
| `faccessat2` | **拦截** | glibc 的 `faccessat()`，每次调用都会先试它再回退，于是要 2 次往返（`access()` 直接调用普通的 faccessat 系统调用） |
| `fchmodat2`、`openat2` | **拦截** | 较新的 glibc、Flatpak/libglnx、FEX 的 rootfs 查找（`libblsession.so` 把 `openat2` 变成 `openat`） |
| `setuid`、`setgid` 及该家族其余 | **拦截** | Xwayland（所以要 `-i`） |
| `futex_waitv`、`io_uring_*`、`landlock_*` | **拦截** | Proton 的 fsync（droiddeck-fsync 在 `libblsession.so` 里回答 `futex_waitv`）、部分引擎 |
| `clone3` | Android 15 之前拦截 | glibc `pthread_create` |
| `close_range` | Android 12 上拦截 | |
| `statx`、`process_vm_readv`/`writev`、`memfd_create`、`pidfd_*`、`seccomp` | 允许 | |

在 ptrace 往返之外，上述每一次都要经由 proot 再付一次信号往返。

## 每个被追踪的系统调用：它需要什么、能否绕过 proot

这是 termux proot 在 `source.env` 加补丁 0001–0013 下的过滤器。"快速路径"指该调用可以按 0014 的设计在进程内回答。

| 系统调用 | proot 为何拦截 | 能否绕过 proot？ |
|---|---|---|
| `openat`/`open`/`creat`、`newfstatat`/`stat`/`lstat`、`statx`、`faccessat`/`access`、`readlinkat`（退出）、`mkdirat`、`unlinkat`、`renameat(2)`、`symlinkat`、`linkat`、`fchmodat`、`fchownat`、`utimensat`、`truncate`、`*xattr`、`inotify_add_watch`、`name_to_handle_at` | 路径翻译（guest → 宿主），外加 `readlink` 与 `rename` 的退出修正 | **可以，经由 libc 包装器。** 原型覆盖 open、stat、statx、access、readlink、opendir 与 fopen；写家族是下一步。裸 `svc` 与 glibc 的内部调用者（ld.so、fts、realpath、nss）仍走 proot。 |
| `chdir`/`fchdir`、`getcwd`（退出） | proot 模拟 cwd；在 0014 之前内核的 cwd 从不移动 | 有了 0014，内核的 cwd 跟随 guest 的。`getcwd` 留在 proot（便宜、罕见）。 |
| `execve`/`execveat` | 运行加载器、在 rootfs 内映射 `PT_INTERP`、处理 shebang、跟踪 `/proc/self/exe` | **不能。** 这是 proot 存在的核心。每次 exec 花 0.4–0.8 ms；会话脚本已经在回避 exec 密集的循环（`nap`）。 |
| `brk`（进入与退出） | 堆模拟：程序由 proot 的加载器映射，因此内核的 brk 区域属于加载器 | 不能（`PR_SET_MM` 需要 `CAP_SYS_RESOURCE`）。**减少调用：** `GLIBC_TUNABLES=glibc.malloc.top_pad=…` 让堆以更大的步幅增长。 |
| `bind`、`connect`、`accept(4)`、`getsockname`、`getpeername` | Unix 套接字的 `sun_path` | 同路径绑定（运行时目录、文件目录）无需翻译，但过滤器看不到地址。可能的快速路径候选。 |
| `wait4`/`waitpid`、`ptrace` | guest 内的 ptrace 模拟（breakpad、gdb） | 可以做成可选；那样 `wait4` 免费（今天每次等待一次停止）。 |
| `prctl(PR_SET_DUMPABLE)`、`setrlimit`/`prlimit64(RLIMIT_STACK)` | 加载器与栈修正 | 已收窄到这些参数（0004）。 |
| `ioctl(TCSETSF, termios2, FICLONE)` | Android pty 策略、FICLONE 的 `EACCES` | 已收窄到这些请求；GPU ioctl 免费（0013）。 |
| `uname`、`sethostname`、`setdomainname` | `DroidDeck` 主机名（kompat） | 罕见。保留。 |
| `set*id`、`*setxattr` | fake_id0 身份模式（0012） | 罕见。保留。 |
| `memfd_create` | Qt JIT 与 php 规避措施（字符串参数） | 每个 Wayland `wl_shm` 缓冲与 Chromium 共享内存都要付一次停止。如果 DroidDeck 运行时不需要这些规避措施，可以去掉。 |
| `statfs`（退出） | 为 `/dev/shm` 伪造 tmpfs | 罕见。 |
| `open_tree`、`move_mount`、`fspick`、`mount_setattr`、`openat2` | 回答 `ENOSYS`（0008、0010） | 没问题。 |
| 被 Android 拦截的调用（上表） | `SIGSYS` 模拟 | **可从源头避免：** `GLIBC_TUNABLES=glibc.pthread.rseq=0` 去掉每个线程一次停止。其余需要打过补丁的 glibc。 |
| clone/fork/vfork/exec/exit 事件 | 跟踪 tracee | proot 追踪期间必需。 |

## 备选方案的权衡

1. **纯 glibc，不用 proot。** 作为主路径被否决，理由见简短结论。
   - 最接近的先例：huntergdavis/steamclienttermux，它在打了补丁的 Termux glibc 2.44 上运行 `steamrtarm64`，配 exec 垫片与 `/tmp`、`/dev/shm` 重定向预加载。
   - 他们报告 Steam 首个窗口快 7 倍、FPS 高约 5%，而 proot 曾占用 60–65% 的一个核心。
   - **他们仍然把 Proton 与游戏跑在 proot 下。**
   - DroidDeck 还有四个包名（`tools/release/variants.txt`），因此重定位前缀需要四份构建或 Winlator 式的补齐路径改写。
2. **按进程混合（Steam 在 proot、游戏在 glibc，或反过来）。** 游戏一侧是*更难*的那一半。Wine 会重新 exec 自己和 `wineserver`，撞上 `PT_INTERP`；其服务器目录硬编码在 `/tmp` 下；Proton 的 esync 与 fsync 使用 `shm_open`（`/dev/shm`）和被拦截的 `futex_waitv`（droiddeck-fsync 在会话预加载中模拟它，glibc 侧的游戏也必须加载该预加载）。游戏的稳态本来就大部分不被追踪，收益在加载期。在快速路径之前不值得做。
3. **用 seccomp 用户通知替代 ptrace。** 实测与 ptrace 相同，而且它无法改写参数，于是每个调用都得被模拟。不行。
4. **一切走进程内 `SIGSYS`**（0.8 µs）。内核会把被屏蔽的 `SIGSYS` 重置为默认动作并杀死进程（`force_sig_seccomp`、`HANDLER_CURRENT`）。glibc 与 Wine 恰恰在这些调用周围屏蔽所有信号。除非同时拦截 `rt_sigprocmask`，否则不安全。不行。
5. **proot 加进程内快速路径（本分支）。** 推荐。细节见下。
6. **一个 proot 感知的 glibc**，第 5 项的后续。重建 rootfs 自带的 glibc（同版本、同 Arch 包），改两处：
   - 其内部路径入口（`__open_nocancel`、`__fstatat64`、ld.so 的 open）改走快速路径，这覆盖 ld.so、fts、realpath 与 nss；
   - 它永不发起被 Android 拦截的调用，就像 Termux 的 `fakesyscall.json` 那样。

   这会去掉剩下的库加载陷阱与所有 `SIGSYS` 往返。rootfs 是按版本发布的（`linuxfs-rN`），glibc 重建因此可控。
7. **只动 tracer 一侧，不改进程内：**
   - **为游戏树再开一个 proot。** 实测陷阱吞吐 2.5 倍，Steam/CEF 的爆发不再卡住游戏。`BwrapSpawner` 已经展示了这个模式：会话中的替身进程把 stdio、退出状态与信号中继给从应用启动的 proot。
   - 让 tracer 保持不绑核（见上）。
   - 在会话环境中设置 `GLIBC_TUNABLES=glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216`。

## 快速路径（原型）

代码在 `tools/proot/fastpath/fastpath.c`，预加载进每个 guest 进程。proot 一侧需要 `patches/0014`，用 `PROOT_FASTPATH=1` 启用。

- **Trampoline。** 该库在固定地址 `0xffff00000` 映射一页 4 KiB 的可执行页，内容是 `mov x8,x0 … svc #0; ret`。开启 `PROOT_FASTPATH` 后，proot 的过滤器在每次 `RET_TRACE` 之前检查调用者地址，并放行来自该页的系统调用。
- **路径映射。** 库取字面的 guest 路径，找出 guest 路径是其前缀的最长绑定；否则该路径位于 rootfs 下。
  - 它以 `O_PATH` 打开*父*宿主目录，并要求 `/proc/self/fd` 恰好指名该路径。这证明沿途没有任何分量是符号链接，proot 也会走同样的目录。
  - 最后一个分量绝不跟随：那里的符号链接交给 proot。
- **缺失文件。** 对缺失文件，以同样方式验证最深的已存在祖先，且下一个分量即使作为符号链接也必须不存在。这样 `ENOENT` 就是精确的。
- **总要交给 proot 的：**
  - `..`、结尾的 `/`、`/proc`、`/dev/fd` 与 `/dev/std*`；
  - `O_TMPFILE`、`AT_EACCESS`，以及任何未处理的标志；
  - 任何一点疑虑。"交给 proot"意味着调用真正的 libc 函数，由 proot 定夺。
- **相对路径。** 补丁 0014 让内核的 cwd 保持在 guest cwd 对应的宿主目录。相对路径与 dirfd 查找经 `/proc/self/{cwd,fd/N}` 映射。
- **已验证父目录缓存。** 已验证的父目录缓存 2 秒（`PROOT_FP_TTL_MS`）。这与 `preload/pathcache.c` 为 Steam 客户端所做的取舍相同。若父目录在该窗口内变成符号链接会被漏掉；`PROOT_FP_TTL_MS=0` 时每次调用都验证，代价是 2.0 µs 而非 0.6 µs。
- **只用被允许的系统调用。** 只使用 `openat`、`newfstatat`、`statx`、`faccessat`、`readlinkat` 与 `close`，全部为应用所允许。第一版用了被应用策略拦截的 `openat2 RESOLVE_IN_ROOT`。
- **等价性校验。** `bench/equiv.py` 在若干刁钻路径上运行 stat、lstat、readlink、read、listdir 与 access，来自多个工作目录并经由 dirfd。有无快速路径、TTL 为 0 与 2000 时输出逐字节一致。路径包括：
  - 指进绑定的绝对与相对符号链接、`..` 符号链接、悬空链接与环；
  - 绑定内的文件、嵌套绑定（`/dev` 内的 `/dev/shm`）与 `/proc`；
  - 符号链接后面的缺失路径。

### 发布前的待办

- **在应用内验证**，而不只是经 adb。要查三件事：
  - 应用域是否允许匿名 `PROT_EXEC` 页（execmem）。若不允许，改为像 proot 的加载器对每个程序那样，把库文件自身的一页映射到固定地址。
  - 没有人先占用 `0xffff00000`。
  - 桩的系统调用能通过 Android 的过滤器。应当能：全部被允许。
- **集成进 `libblsession.so`** 而不是再加一个预加载。`opens.c` 与 `pathcache.c` 已经定义了 `open` 与 `access`，它们的"真实"调用应改为快速路径。这也去掉第二个库约 0.2–0.4 ms 每次 exec 的加载开销——该开销目前仍未解释（见上表 exec 行）。
- **接通启动器。** 应用已经知道绑定（`LinuxRuntime.binds`）：
  - 在 proot 的环境中设置 `PROOT_FASTPATH=1`；
  - 向 guest 传入 `PROOT_FP_ROOT` 与 `PROOT_FP_BINDS`；
  - 在"不带 seccomp 运行 proot"旁加一个性能开关，seccomp 关闭时快速路径也随之关闭。
- **覆盖写家族**（mkdir、unlink、rename、symlink、chmod、utimens）以及同路径绑定的 Unix 套接字 `connect`/`bind`。然后在应用里测量一次真实的 Steam 启动、一次游戏加载，以及 steam、steamwebhelper、wine 与 wineserver 的 `PROOT_FP_STATS` 命中率。
- **FEX** 作为 DLL 在 Proton 内部，经由 glibc 的 `syscall()`（而非包装器）翻译 x86 路径系统调用，因此它们仍会陷阱。一个 `syscall()` 钩子也可以把 `openat` 与 `newfstatat` 路由过去。

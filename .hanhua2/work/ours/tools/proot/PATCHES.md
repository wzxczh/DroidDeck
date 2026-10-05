# 应用携带的 proot 补丁

由 `tools/proot/build.sh` 使用 NDK 在 termux/proot `4dba3afb`（Termux 的 5.1.107-70 包，也就是 Linux 运行时在 `opt/android-host/proot` 出厂携带的那个 proot；该二进制中每一处 `file.c:line` 都与这个 commit 对应）之上构建。talloc 从 Samba 的发布 tarball 静态链接进来，因此 apk 携带的是一个自包含的 `libproot.so` 加 `libproot-loader.so`，`LinuxRuntime` 优先使用它们而不是运行时自带的副本。

从 WinNative（`feature/proot-enhancements`，e7af0f24）移植：

- `0001-tracee-lookup-by-pid.patch` - 被跟踪进程按 pid 哈希存储，因此每次指针跟踪停止都能直接找到自己的被跟踪进程，而不必逐线程遍历链表的一个条目；已终止的被跟踪进程只在一次终止发生之后才被清理。
- `0002-canon-resolve-parent-at-once.patch` - 对于仅位于 rootfs 绑定之下、深度至少三层的干净绝对 guest 路径，只要 `/proc/self/fd` 给出的路径与 proot 将要构造的路径一致，就用 `O_PATH` 一次性打开其父目录并直接当作规范路径，从而取代按路径分量逐个 `lstat`。不跟随父目录的调用，其最后一个分量也不再被 `lstat`。扩展仍然看得到父目录的 host 路径，并且在 f2fs 变通方案生效期间快速路径保持关闭。
- `0003-clone3-flags-read-guard.patch` - 当无法读取 `struct clone_args` 时，用 `clone3` 创建的线程保留它继承的 flags，而不是被当作一次 fork 来跟踪。

从 WinNative（`main`，53836ca9，"Fix/performance and vac"）移植：

- `0004-seccomp-filter-by-argument.patch` - seccomp 过滤器只在 `PR_SET_DUMPABLE` 时跟踪 `prctl`，只在 `RLIMIT_STACK` 时跟踪 `setrlimit`，只在设置新的 `RLIMIT_STACK` 时跟踪 `prlimit64`——这些是 proot 唯一会处理的情况；其余每个调用都在不停止的情况下直接运行。核心只在 x86_64 上跟踪 `uname`（它是唯一会被重写的架构）；kompat 仍会为 `--kernel-release` 添加它。
- `0005-seccomp-skip-unneeded-sysexit.patch` - 一次 seccomp 停止只取一次寄存器，并按系统调用号查找过滤器 flags，取代一次 `PTRACE_GETEVENTMSG` 加第二次寄存器读取。flags 表由 proot 的列表与已启用扩展的列表（这里是 kompat 和 fake_id0）合并构建，因此与过滤器上报的内容完全一致。留给内核处理的 `wait4`/`waitpid`，以及不带 sockaddr 的 `accept`/`accept4`，都会跳过其退出停止——除非某个扩展替换了该系统调用（kompat 把 `accept4` 变成 `accept`）。
- `0006-clone3-exit-signal.patch` - `clone3` 的 flags 和退出信号按 `struct clone_args` 的 64 位字段读取，因此 proot 在决定如何跟踪子进程时，能够把一次 `clone3` fork 与线程区分开。
- `0007-proc-self-thread-group.patch` - 被跟踪进程记录自己的线程组，`/proc/self` 指向该线程组而不是调用它的线程，`/proc/thread-self` 解析为 `/proc/<tgid>/task/<tid>`。
- `0008-fchmodat2-openat2.patch` - `fchmodat2` 的路径会被转换（并遵守 `AT_SYMLINK_NOFOLLOW`），`openat2` 应答 `ENOSYS` 使调用方回退到已转换的 `openat`，超出表末尾的系统调用号被拒绝而不是被读取。
- `0009-tracee-relatives-sweep.patch` - 被跟踪进程统计自己的子进程数量，因此一个既没有子进程也没有 ptrace 对象的线程在终止时不再遍历每个被跟踪进程；每次停止所用的内存收集器改为清空，而不是释放后重新分配。

为 Flatpak 添加：

- `0010-new-mount-api-enosys.patch` - `open_tree`、`move_mount`、`fspick` 和 `mount_setattr` 应答 `ENOSYS`。它们接收的路径是 proot 从未转换过的，因此 libglnx 的 `open_tree(AT_FDCWD, "/")` 把 host 根目录的描述符交给了 Flatpak，使它试图在那里创建目录（`mkdirat(root): Operation not permitted`）；由于不受支持，libglnx 回退到 `openat`。
- `0012-android-hardlink-denial.patch` - Android 的 SELinux 策略禁止应用建立硬链接，因此 `linkat` 以 `EACCES` 失败，Flatpak 无法创建它的仓库（`Creating repo: linkat: Permission denied`）。`O_TMPFILE` 应答 `EOPNOTSUPP`，于是 libglnx 改为写一个具名临时文件再重命名它；被拒绝的链接应答 `EPERM`，ostree 的 checkout 遇到这种情况改为复制。
由 DroidDeck 添加：

- `0011-kompat-utsname-only.patch` - `--kernel-release`（guest 的 `DroidDeck` 主机名）会加载 kompat，它的过滤器拦截 `futex`、`fcntl`、`epoll_pwait`、`pselect6`、`pipe2`、`eventfd2`、`socket` 等调用，并在每次 `execve` 时剥离 `AT_SYSINFO_EHDR`，让 glibc 在没有 vDSO 的情况下运行。当虚拟的 release 不比真实内核更旧、且 hwcap 不被改动时，这些处理程序全都是 no-op：kompat 现在只跟踪 `uname`、`sethostname` 和 `setdomainname`，并让 auxv 保持内核写入的原样。在一台 SD 8 Gen 2 guest 上，这让一次 futex 往返从 467 us 降到 97 us，`epoll_pwait` 从 60 us 降到 0.8 us，`fcntl` 从 40–107 us 降到 0.4 us，并让 `clock_gettime` 回到 vDSO。
- `0012-fake_id0-identity-only.patch` - `-i uid:gid`（用于 Xwayland 在运行 xkbcomp 之前的 setgid/setuid）会加载 fake_id0，它的过滤器拦截每一次 `fstat`/`newfstatat`/`stat`（进入与退出）、每一次 `sendmsg`（全部 Wayland、X11、Chromium 和 PulseAudio 流量）、`socket`、`getsockopt`、`get*id` 系列以及 chown/chmod 系列。当给定的 id 就是 proot 实际拥有的、且不为 0 时，这些处理程序全都是 no-op；fake_id0 现在只跟踪 `set*id` 系列和 xattr 权限修正，并在 `execve` 时不管 set-user-ID 位（Android 以 `nosuid` 挂载应用的数据分区，内核同样不会理会它们），从而让 id 保持不变。在相同选项下的 x86_64 host 构建：`fstat` 41.7 -> 1.3 us，`sendmsg` 18.0 -> 2.5 us。
- `0013-seccomp-ioctl-by-request-and-kernel-exit-stops.patch` - 过去每一个 `ioctl` 都在进入和退出时停止，也就是每一次 GPU 提交和每一次等待。过滤器现在只跟踪 enter.c 和 exit.c 会处理的那些请求（`TCSETSF`、四个 termios2 请求、`FICLONE`），其余的立刻放行，而且 ioctl 块最先被生成（沿用 WinNative 53836ca9 的做法，它只跟踪 termios2 那几个）。当运行中的内核足够新时，`faccessat2` 的退出停止（termux 5ba8b95，用于 5.8 之前内核上 glibc 的 ENOSYS 回退）和 `statx` 的退出停止（4.11 之前内核上的模拟）被去掉。host 构建：`ioctl` 31.6 -> 0.5 us；`stat`/`statx`/`faccessat2` 约 44 -> 28 us（一次停止）。

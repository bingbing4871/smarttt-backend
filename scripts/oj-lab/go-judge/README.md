# 专用虚拟机部署配方（尚未在 Linux 执行）

本轮用户已明确没有专用 VM。当前只有 Windows/VMware 记录，不能启动现有 Ubuntu/CentOS 或其他共享服务器执行代码。下面是未来专用环境的复现步骤，不是部署成功记录。

## 资源与版本契约

- 新建可销毁 Linux VM，建议 2 vCPU、4 GiB RAM、20 GiB磁盘；内核至少6.1、cgroup v2、cpu/memory/pids controller、namespace和seccomp支持。
- 禁用宿主共享目录、剪贴板、设备透传、SSH agent转发；VM不可持有业务凭据、训练数据或生产网络访问权限。建议host-only管理网，构建所需出站临时放行；运行后阻断非必要出站。
- 需要事先由管理员配置 Docker、git、Python3、systemd-detect-virt；脚本不安装工具、不调用sudo、不修改内核或宿主全局配置。
- 固定 go-judge `v1.9.10`，官方tag对应commit `662109c510ae8d42b0f37a833c2449e9ffc778e1`。2026-11-10已通过官方仓库tag及源码核对。
- **不直接采用未核实seccomp能力的预编译镜像。** 固定版本源码的默认发行构建未启用seccomp标签；配方显式 `go build -tags seccomp` 并提供过滤文件。显式写入嵌入version.txt，避免仅ldflags被源码初始化逻辑覆盖。
- Go构建镜像必须为满足源码Go1.25要求的官方镜像（建议1.25系列bookworm），运行镜像为Debian bookworm，均必须提供真实sha256摘要；还要提供apt snapshot时间及其中的精确g++包版本。当前未获取这些镜像或编译器，**不提供虚构摘要/版本**，参数缺失时脚本拒绝构建。

官方依据：安装、配置、API页面及固定tag源码，详见 `docs/oj-real-e2e.md`。源码下载只用于本机只读核对，未执行其程序。

## 管理员准备

只在明确分配的空白VM中，由管理员创建 `/etc/smarttt-oj-disposable-vm`，内容恰好一行 `SMARTTT_OJ_DEDICATED_VM_V1`。这是一项人工资源归属声明，不是自动检测能够证明的事实，禁止给共享或生产主机加此标记。

运行配方前设置以下**非秘密**参数，填写经过管理员核实的实际值：

```bash
export OJ_LAB_DEDICATED_VM=YES_DISPOSABLE_VM
export OJ_GO_IMAGE='golang:<满足Go1.25的bookworm版本>@sha256:<真实摘要>'
export OJ_DEBIAN_IMAGE='debian:bookworm@sha256:<真实摘要>'
export OJ_APT_SNAPSHOT='<YYYYMMDDTHHMMSSZ>'
export OJ_GXX_VERSION='<该snapshot中的精确g++包版本>'
bash scripts/oj-lab/sandbox.sh build
bash scripts/oj-lab/sandbox.sh start
bash scripts/oj-lab/sandbox.sh check
```

`build`只在私有state目录fetch固定tag，核对commit后将git archive送入Docker构建；不会修改SmartTT仓库历史。Dockerfile固定仓库snapshot，保持TLS与apt签名验证，将构建镜像的CA信任库带入运行镜像以引导HTTPS apt。记录完整dpkg版本及最终image ID，不自动拉取latest。镜像/包仓库不可用、Go版本不符或摘要错误时必须失败；本机不能验证上述构建步骤。

`start`仅在专用VM内使用特权容器，`--cgroupns=private`、不挂载宿主目录或Docker socket。创建带管理标签的internal网络，仅发布 `127.0.0.1:5050`。容器CPU2核、内存/含swap总额2GiB、总pids512、判题并发1；VM本身CPU/RAM上限仍是特权容器外的最终边界，不能只依赖特权容器自身的限制。

执行器设置 `-no-fallback`、独立mount/seccomp配置、`-net-share=false`、缓存TTL5m、默认缓存拷出16MiB、文件描述符128。seccomp为补充性危险syscall阻断表，不是完整允许列表或对VM边界的替代；其实际有效性必须通过后续ptrace/SIGSYS探针证明。镜像内canary文件位于 `/opt/oj-host-canary`，不挂入学生根目录，供文件隔离探针使用。

随机Bearer Token只写入私有state目录的0600文件 `auth.env`，通过Docker env-file注入，不放argv/Git。此固定版本源码会在普通日志里记录配置和Token，因此使用 `-silent` 和Docker `--log-driver=none`；不以原始执行器日志作为证据，改为保存脱敏预检与JUnit结果。启动失败时读取容器ExitCode/受控预检，不去掉静默参数来公开日志。

`check`是配置预检，不是沙箱验收：检查VM、namespace入口、cgroup、Kernel6.1、镜像/编译器版本、API鉴权、资源配置、私网与回环映射，输出不含Token的 `preflight.json`。默认目录为 `$HOME/.local/state/smarttt-oj-sandbox`（可用XDG_STATE_HOME更换）。`/version`、`/config`在固定版本中不要求Token，但同样仅回环可达；`/run`/`/file`必须鉴权。API不得面向学生或公网。

## 真实端到端验收

经批准建立本机到专用VM回环5050的SSH转发；不要直接公开VM端口。将 `preflight.json`复制到本机被Git忽略的证据目录。通过安全传输把Token加载到本次进程环境 `OJ_LAB_JUDGE_TOKEN`，不要cat、粘贴到聊天或写进启动命令。

Windows SmartTT仓库根目录，设置：

```powershell
$env:OJ_LAB_JUDGE_ISOLATED='YES_DEDICATED_SANDBOX'
$env:OJ_LAB_JUDGE_ENDPOINT='http://127.0.0.1:5050'
$env:OJ_LAB_SANDBOX_EVIDENCE='<复制到本机的preflight.json绝对路径>'
# OJ_LAB_JUDGE_TOKEN 从本地安全来源加载，不在此处填写字面量。
python scripts/oj-lab/run_mysql.py --mysql-bin D:/phpstudy_pro/Extensions/MySQL8.0.12/bin --with-judge
```

结束后恢复本次新增的环境变量。脚本运行独立 `oj-real-e2e` Profile，预期24项：原8项真实MySQL/HTTP测试，原8项沙箱测试，再加后续用例检查、版本/鉴权、实际缓存超限、执行器不可达有界重试、墙钟、进程、namespace/网络/文件、seccomp，共16项沙箱检查。没有真实资源时明确失败，不以Mock响应、跳过或请求字段检查替代。

CPU/内存/输出通过真实程序和响应测量验证；墙钟用睡眠程序区分CPU用时；进程用最多64次fork的有界程序验证EAGAIN及procPeak；namespace与外容器基线比较；网络要求文档保留地址连接返回ENETUNREACH；seccomp要求ptrace触发Dangerous Syscall；大缓存下载真实二进制确认大小，超限通过真实HTTP判题到两次尝试后FAILED/SYSTEM_ERROR。执行器不可达测试只切换实验服务目标到本机空闲端口，不停止其他人的服务。

24项通过仍不代表全部故障与生产规则已验收：真实socket响应超时、缓存DELETE失败、整个JVM/VM重启和异常残留缓存TTL清理仍需独立可控故障实验。当前没有专用VM，所有沙箱测试均未执行。

## 停止与清理

```bash
bash scripts/oj-lab/sandbox.sh stop
bash scripts/oj-lab/sandbox.sh clean
```

只处理固定名称且标签匹配的容器/网络；运行中不允许clean，不强删镜像、不删其他资源、不做docker prune。移除本轮凭据文件，但保留镜像、源码版本和构建证据。启动中途失败可能留下带管理标签的网络/容器，须先检查状态；脚本不会自动扩大删除范围。最终可以由VM所有者销毁整个专用VM；本轮不自动创建或销毁VM。

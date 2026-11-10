# OJ 真实资源联调记录与复现

日期：2026-11-10。基线：`822b7f41edd3eda87bef1e7a5d7c3e415969443e`，分支 `feature/oj-http-api`。

## 结论与证据边界

本机已实际完成：临时 MySQL → 数据库密码核验 → RS256 Token → 真实 JDBC 授权查询 → 回环 HTTP 提交/查询 → 文件持久化与 Spring HTTP 服务上下文重启恢复。新增 8 项真实 MySQL/HTTP 测试通过，未使用 Mock JDBC、MockMvc 或模拟 HTTP 服务代替这一段链路。

**没有可用真实 go-judge，尚未完成 C++17 编译执行或完整判题端到端验收。** MySQL 使用根据现有 Mapper/实体建立的最小契约表，不是现有测试库或生产库的结构导出。因此本次不能证明已有实库字段、索引、字符集、学期表后缀与生产资格规则正确。

## 实际资源

- Windows 10；现有 Java 21.0.10、Maven 3.8.6；源码编译目标仍为 Java 8。未安装/升级或修改全局环境。本轮本地运行不替代基线的远程 Java8 证据。
- 复用 `D:/phpstudy_pro/Extensions/MySQL8.0.12/bin` 的 MySQL Community Server 8.0.12 程序文件。
- 新建私有 datadir，回环端口 `33379`，随机 `oj_lab_*` 数据库；每轮进程结束后关闭本轮 mysqld。没有启动/停止原有 `MySQL80` 服务或连接其数据目录。
- WSL 返回未安装发行版，Docker 未在 PATH 或默认安装路径发现，未发现 go-judge 命令/本地执行器端口，未提供隔离远端地址或凭据。未尝试探测生产服务或读取项目配置中的连接凭据。

## 可复现的真实 MySQL/HTTP 验证

在仓库根目录运行：

```powershell
python scripts/oj-lab/run_mysql.py --mysql-bin D:/phpstudy_pro/Extensions/MySQL8.0.12/bin
```

脚本不下载软件。端口已占用、目录初始化失败或缺少工具则退出，不接管已有服务。它创建仅当前用户可访问的 Windows ACL 目录（Linux 使用 0700），随机生成 root、只读账号和两个学生的凭据，使用进程环境传给 Maven/JDBC，不打印密码/Token。首次启动所需的 root 初始化 SQL 在该私有目录中临时保存，服务就绪后即移除，异常退出也清除；数据库文件保留在被忽略的 `target` 目录供检查，不提交 Git。秘密仍存在于进程内存和测试库数据中，禁止上传整个 target 或 Maven 调试转储。

只启动本轮创建的 mysqld，显式 `--no-defaults`、独立 datadir、`127.0.0.1` 绑定、禁用 mysqlx，限制连接数和内存。应用仅使用 SELECT 权限的 `oj_reader`；测试管理连接才负责夹具写入、成员撤销与关闭本轮实例。脚本不修改全局代理/TLS/环境或业务配置。JDBC 的 `useSSL=false` 仅用于本次本机临时 MySQL 回环连接，不适用于远端数据库，也不改变 Git/HTTPS TLS 设置。

实际 Maven 命令由脚本注入随机数据库连接信息后执行：

```text
mvn -B -ntp -f smarttt-exam/pom.xml -Poj-real-mysql \
  "-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC" \
  "-Doj.lab.report-directory=<本轮target目录>/surefire" test
```

新增 Profile 仅选择 `OjRealMysqlTests`；正式 `oj-tests` Profile、108 项基线、业务实现、CI 工作流均未改。不要同时启用两个真实资源 Profile 或与 oj-tests 合并运行。真实资源 Profile 无环境时明确失败，不静默跳过。脚本检查退出码和 XML 中实际 8 项、0 失败/错误/跳过，报告缺失/数量不符也失败。

首次运行有 1 项辅助代码错误：把预期 404 HTML 按 JSON 解析，实际为 8 项/1 error；已修正辅助代码，失败报告保留于 `target/oj-real-e2e/initial-failed-report`。最终真实 MySQL 证据目录为 `target/oj-real-e2e/mysql-533687587226`，`summary.json`/`maven.log`/`surefire/TEST-*.xml` 记录退出 0、8 项通过和本轮 mysqld 已关闭。

## 登录与资格规则的实际依据

既有 admin `StUsersMapper.getStUsersByLoginNameAndPwdAndCatelog` 以 loginname/pwd/catelog 查询，`LoginController.login` 把数据库返回的用户交给 `getStudentInfor`，令牌 id 为 `st_users.id`。学生表通过 `sm_student.usersid` 关联；成员表 `cm_classroom_student.userId` 同样是用户 ID。实验签发入口每次按参数化 SQL 查询用户和学生，密码作精确比较；客户端不能传入用户 ID、roleId 或额外字段，不允许旧 HMAC Token 换签。

新入口 **仅位于 test 源码** `OjIsolatedServer`，不会打入业务运行 JAR，没有注册生产 CredentialProof 或 Eligibility。密码比较沿用旧 Mapper 契约，并未声称现有明文/散列存储方式满足生产密码安全标准。生产密码存储和限速/暴力破解防护仍需专门确认。

实验状态 `LAB_ACTIVE`、学期 `lab-term` 是明确的夹具值，不是对生产 status 的猜测。真实 JDBC 每次读取两个状态和当前学期日期；未知值、失效日期拒绝。`cm_term` 的 begindate/enddate/iscurrentterm 来自现有实体/Mapper，但“只允许当前日期内且当前学期”只是实验规则，尚未获得生产业务确认。

旧 `/login/user` 从客户端选择身份/角色签发共享 HMAC 的风险仍存在。实验服务器采用显式 Bean 导入，不扫描 admin 或旧登录控制器，因此该路由为 404，旧 Token 不在 OJ RS256 信任域。**没有改写生产教师登录，不能据此宣布旧登录风险已修复。** 生产开放仍要求先完成教师角色选择兼容性评估以及该旧路径的访问控制整改。

## 8 项真实 MySQL/HTTP 测试

1. 数据库密码核验签发 RS256，错误密码、大小写不同账号和 SQL 注入字符串拒绝。
2. 客户端伪造 id 拒绝，旧登录路由不暴露，伪造/旧 HMAC Token 拒绝。
3. 过期 Token 与无 Token 请求返回 401。
4. 提交、幂等、冲突、历史、HTTP 上下文关闭后重启恢复，响应字段不泄漏隐藏数据。
5. 数据库撤销成员后提交/查询立即 403，且不产生提交 JSON。
6. 其他学生无成员权限时 403，有成员资格后仍看不到他人提交；跨课程/不存在课堂拒绝。
7. 未知账号/学生状态及过期学期拒绝。
8. 应用只读数据库账号实际无法删除业务夹具表数据。

HTTP 均经过真实回环 TCP/Tomcat 和既有 OjHttpController。开启 OJ 的属性仅注入测试服务器的独立 Spring Environment，不读取原 application.properties、不启动原业务应用、不自动连接 Redis/MinIO。无判题器时没有模拟成功，提交保持 WAITING_JUDGE。

## 真实 go-judge 环境交接与启动契约

本轮未安装 Docker/WSL 或猜测远端机器。下一步需提供专用 Linux VM/测试机器，明确运行权限、已审核的固定 go-judge 镜像摘要/版本、g++ 版本和 cgroup 权限。不要使用未固定的 latest，不在业务宿主执行学生代码。执行器 API 仅绑定测试机器回环地址，使用 SSH 本地转发或其他已审核私网访问；不得公网映射。沙箱内禁用网络，宿主出站也应受控。容器 privileged/cgroup 挂载配置必须由该专用机器管理员按所选版本审核，不能因 API 可访问就认定隔离生效。

`scripts/oj-lab/preflight_linux.sh` 只读检查明确获准的专用VM/容器，委托inspect_sandbox.py保存脱敏结果；不打印容器环境变量、不部署或启动容器。本机无可用Linux；本轮找到Git Bash并完成语法检查，但没有在Linux实际执行该脚本。命令示意（专用VM标记/确认要求见后文）：

```bash
export OJ_GOJUDGE_CONTAINER=<已审核的独立容器名>
bash scripts/oj-lab/preflight_linux.sh
```

管理员确认真实沙箱隔离后，将其回环执行器安全转发到本机，例如本机 `127.0.0.1:5050`。令牌只从安全配置注入环境，不写在命令行、Git 或日志中。已有专用沙箱和转发就绪后设置：

```powershell
$env:OJ_LAB_JUDGE_ISOLATED='YES_DEDICATED_SANDBOX'
$env:OJ_LAB_JUDGE_ENDPOINT='http://127.0.0.1:5050'
# OJ_LAB_JUDGE_TOKEN 如需要，从本地安全来源注入，不在终端输出。
python scripts/oj-lab/run_mysql.py --mysql-bin D:/phpstudy_pro/Extensions/MySQL8.0.12/bin --with-judge
```

`--with-judge` 在创建数据库前要求明确隔离确认、回环执行器可连接、64位hex凭据及匹配v1.9.10/cgroup2的真实预检文件，否则失败；不提供模拟回退。选择独立 `oj-real-e2e` Profile，运行 `OjRealJudgeTests`（继承 8 项数据库测试与本轮合计16项沙箱测试，共24项）。测试请求真实编译运行 AC/WA/CE/RE/TLE/MLE/OLE；正常 AC 判题后关闭/重建 HTTP 与结果仓库并查询持久化结果；独立编译大产物并下载真实缓存，断言 >64KiB 且 ≤16MiB，删除缓存后再通过生产客户端编译运行。任何预期 verdict 不符、意外FAILED、超时或报告缺失都失败；技术失败专用反例则必须两次后FAILED/SYSTEM_ERROR。

这些测试 **目前只完成Java编译，未执行，不能作为真实沙箱成功证据**。本轮已补入CPU/墙钟/进程/namespace/文件/网络/seccomp实际探针、缓存真实超限和不可达有界重试；这些源码也尚未经真实沙箱执行。即使未来24项通过，仍须单独验收以下运维故障：

- 真实socket响应超时/DELETE缓存清理失败；需可控的隔离网络故障注入，不能停止其他人服务。不可达/拒绝连接测试不能替代这些故障。
- 缓存TTL和异常响应导致残留的清理。
- JVM/机器级重启（本次只验证 Spring HTTP 服务和仓库实例重启），完整审计记录和敏感日志检查。

## 生产开放与独立验收

仍阻断：可信生产登录接入和私钥生命周期；旧教师角色登录安全兼容性；真实业务状态/学期规则；真实测试库 DDL/学期动态表验证；真实沙箱与全部资源/故障探针；完整 AC/WA/CE HTTP 判题链路。具备对“真实 MySQL 实验夹具与环境脚本”的独立审核条件，不具备完整真实集成验收或生产开放条件。

原 108 项测试与固定基线保留；真实资源测试独立标记，不塞入无资源的常规 CI。四个本地配置文件保持原样。没有 Commit、Push、PR、Merge、修改部署工作流或启用生产 HTTP。

## 本轮最终回归记录

`mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests "-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC" test` 实际返回 0，108 项、0 失败/错误/跳过，日志为 `target/oj-real-e2e/regression-108.log`。进程级 MAVEN_OPTS 临时限制为 128/512MiB 堆和 256MiB Metaspace，结束恢复。

`python scripts/ci/verify_oj_reports.py smarttt-exam/target/surefire-reports` 返回 0，验证 108 个唯一通过测试及完整固定基线。`python scripts/ci/test_verify_oj_reports.py` 返回 0，两个 Python 测试方法包含原13个场景及55项新增基线移除反例。没有改动这三个 CI 脚本/基线文件或原108项测试。

上一阶段增加/修改 8 个文件：本文、`smarttt-exam/pom.xml`、`scripts/oj-lab/{run_mysql.py,schema.sql,preflight_linux.sh}`、`smarttt-exam/src/test/java/com/example/smartttexam/oj/integration/{OjIsolatedServer.java,OjRealMysqlTests.java,OjRealJudgeTests.java}`。生产 Java 源码没有修改。真实 MySQL 的8项测试与原108项分别执行，不能将两次运行称为一次116项完整判题测试。

## 继续沙箱准备：2026-11-10

用户本轮明确“没有专用的虚拟机”。只读发现VMware安装于D:/VM、虚拟机清单留有Ubuntu/CentOS记录；`vmrun list`为0台运行。未启动/登录这些VM，未探测共享训练/生产服务器。WSL无发行版，Docker不可用；固件虚拟化开启不等于存在获准执行环境。没有实际使用Linux内核、go-judge或g++执行程序。

已准备 [专用VM配方](../scripts/oj-lab/go-judge/README.md)：sandbox.sh的build/start/check/stop/clean、Dockerfile、最小mount与seccomp配置，以及inspect_sandbox.py。入口拒绝Windows、容器内运行、缺少专用VM标记/确认、缺少cgroup2控制器的环境；只管理带独立标签的本轮资源。镜像摘要、apt snapshot及精确编译器包版本须待专用VM选定后核实填写，不虚构已安装版本。

官方资料已实际下载核对：

- https://docs.goj.ac/install
- https://docs.goj.ac/configuration
- https://docs.goj.ac/api
- https://github.com/criyle/go-judge/tree/v1.9.10 （commit 662109c510ae8d42b0f37a833c2449e9ffc778e1）

当前官网与固定版本有差异：v1.9.10源码Seccomp使用build tag，默认发布配置无该tag，缺失过滤文件可无过滤继续运行；因此配方从固定源码带-tag构建并指定过滤文件。该版本普通日志会打印配置及鉴权Token，配方使用-silent和禁用Docker日志。没有把“参数配置正确”当成seccomp或资源限制生效证据。

OjRealJudgeTests本轮补充8个方法：realCasesBeyondTheFirstAreActuallyEvaluated、realVersionCgroupControllersAndApiAuthentication、realCacheOverflowUsesBoundedTechnicalFailureAndSurvivesRestart、realUnavailableExecutorUsesBoundedRetriesAndPersistsFailure、realWallClockLimitTerminatesSleepingProgram、realProcessLimitRejectsBoundedForkAttempts、realNamespaceNetworkAndFilesystemIsolation、realSeccompKillsForbiddenSyscall。CPU测试新增实际CPU用时范围断言。测试只能在已确认专用VM的真实服务上运行，不提供HTTP模拟替代。

本轮静态检查：两个Bash文件分别bash -n返回0；Python AST和两个YAML文件解析通过；Windows执行部署/预检入口拒绝，退出1；Python -O预检拒绝，退出1；无沙箱确认时--with-judge拒绝，退出1。Git Bash只是解析器，不是Linux，以上不能证明Docker镜像构建或沙箱部署成功。

原108项本轮重新执行通过（0失败/错误/跳过，Maven退出0），证据为 `target/oj-sandbox-round/regression-108.log`。后续8项真实MySQL与CI报告检查结果见本节补充记录。真实沙箱24项**未执行**；目前仅能交付部署配方与待运行测试供独立审核，完整真实集成验收仍被专用VM缺失阻断。

补充实际结果：`python scripts/oj-lab/run_mysql.py --mysql-bin D:/phpstudy_pro/Extensions/MySQL8.0.12/bin` 返回0，8项、0失败/错误/跳过；证据 `target/oj-real-e2e/mysql-ab285e1f9099/{summary.json,maven.log,surefire/}`，临时33379实例关闭。两个专用Profile保持分离，新增沙箱测试源码在该次Maven中完成Java编译，但没有执行。正式报告校验器返回0，108项基线完整；夹具脚本返回0（原13场景及55项移除反例），日志 `target/oj-sandbox-round/report-fixtures.log`。

本轮涉及11个文件：修改本文、preflight_linux.sh、run_mysql.py、OjIsolatedServer.java、OjRealJudgeTests.java；新增sandbox.sh、inspect_sandbox.py、go-judge/{Dockerfile,mount.yaml,seccomp.yaml,README.md}。加上上一阶段尚未提交的POM、schema.sql、OjRealMysqlTests.java，累计交付14个文件；原4个本地业务配置继续字节不变且不暂存。没有修改生产源码、既有测试/CI基线或部署工作流，没有Commit/Push/PR/Merge/部署。

## 隔离脚本 P2 安全修复：2026-11-10

Docker 操作统一经过 `scripts/oj-lab/local_docker.py`：每次检查专用 Linux VM 确认、管理员标记和 VM/容器边界，校验 `/var/run/docker.sock` 为可读写 Unix socket，以 `docker --host unix:///var/run/docker.sock info` 验证 Linux daemon，再执行明确绑定相同 socket 的操作。shell 中的 build/run/stop/rm/inspect/network 及 Python 中的 inspect/exec 全部使用这一入口；当前默认 context 即使指向远程也不被采用。DOCKER_HOST、DOCKER_CONTEXT、DOCKER_TLS_VERIFY、DOCKER_CERT_PATH、DOCKER_CONFIG、BUILDX_BUILDER、BUILDKIT_HOST 只要存在（包括空值）即拒绝；操作者须在专用 VM 中明确移除这些覆盖。子进程设置 DOCKER_BUILDKIT=0，避免 buildx 的独立远程 builder。若 Docker 不支持 classic builder，构建失败，不自动回退。没有增加特权启用路径，原有 VM、cgroup 和资源确认仍必需。

`run_mysql.py` 的 `cleanup_owned` 仅接收本轮创建的 Popen，不按进程名枚举或终止服务。SHUTDOWN 最多等待10秒，随后等待本轮进程20秒，必要时 terminate/等待10秒、kill/等待10秒。嵌套 finally 分别尝试凭据删除、日志关闭和 summary 保存。失败记录为步骤与异常类型，避免暴露命令、密码或原始异常。任何清理失败使整体结果为失败；已有主异常保留，否则抛出清理错误使进程非零退出。Maven 返回0仍须实际 XML 数量与结果校验通过，才设置 checks_passed/success。summary 自身不可写时不能保证落盘，以标准错误和非零退出明确报告，不伪称成功。

新增 `test_lab_safety.py`，命令 `python scripts/oj-lab/test_lab_safety.py -v` 实际执行 **24项，全部通过，退出0**。Docker/进程操作全为 Mock；覆盖远程 HOST/CONTEXT 和其他覆盖、远程默认 context 的显式本机绑定、socket 缺失/类型错误/权限拒绝、目标验证异常，以及 Windows/未批准 Linux/无效标记/容器拒绝。MySQL 覆盖关闭超时与异常、正常退出、已退出、超时 terminate、无法退出后的有界 kill、wait 异常、凭据删除/日志关闭/summary 写入失败及多故障组合。隔离副本故意反转一项真实断言后返回1；正式测试源码未被破坏。

本轮实际验证（本机 JDK21.0.10，不代表新的 Java8 远程运行）：

- `mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests "-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC" test`：退出0，108项，0失败/错误/跳过。进程级 MAVEN_OPTS 临时限制后恢复。
- `python scripts/ci/verify_oj_reports.py smarttt-exam/target/surefire-reports`：退出0，108唯一通过测试、基线完整。
- `python scripts/ci/test_verify_oj_reports.py`：退出0，原13场景和55项新增基线移除反例均符合预期；CI校验器/基线未修改。
- `python scripts/oj-lab/run_mysql.py --mysql-bin D:/phpstudy_pro/Extensions/MySQL8.0.12/bin`：退出0，真实实验 MySQL/HTTP 8项通过，0失败/错误/跳过；本轮33379实例正常关闭，cleanup_errors为空，bootstrap已删除。证据 `target/oj-real-e2e/mysql-f85b4660b127/`。只连接本轮新建实验实例，不连接业务库。
- 两个 Bash 入口 `bash -n` 退出0，Python AST 与 YAML 解析通过；Windows 和模拟未批准 Linux 均拒绝，退出1，未调用真实 Docker。

日志与校验结果位于 `smarttt-exam/target/oj-p2-fix/`，含脚本测试、108项回归、报告夹具、故意失败副本和 verification-summary.json；未提交任何生成物。原14个交付文件全部保留，本轮修改其中5个（本文、sandbox.sh、preflight_linux.sh、inspect_sandbox.py、run_mysql.py），新增 local_docker.py、test_lab_safety.py，累计16个。未修改 POM、Java实现/测试、CI基线、部署工作流或4个本地配置；配置 SHA256 前后相同。仍无专用 Linux VM，未启动真实 Docker/go-judge，真实沙箱24项未运行，完整端到端验收继续受此阻断。具备本轮两项P2针对性独立复审条件，不代表真实沙箱验收通过。

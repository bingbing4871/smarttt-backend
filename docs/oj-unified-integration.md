# OJ 统一前后端实验联调（2026-11-11）

本轮实际使用原 SmartTT Vue 主应用、原路由和页面，经同源转发连接独立 Java 服务及本轮新建 MySQL。没有浏览器 Mock 回退；没有执行 C++ 或连接 go-judge。生产身份、业务数据库结构与真实沙箱不在本次验收范围。

## 实际环境与隔离

- Windows 本机；Node 20.20.2 / Vite 5.0.8 / Vue 3.3.11；JDK 21.0.10 / Maven 3.8.6，Java 编译目标 8。本轮不是新的 Java8 远程 CI。
- 复用预装 MySQL 8.0.12 二进制，创建独立 datadir、随机数据库、随机 root/reader/学生密码，仅绑定 127.0.0.1:33379，SELECT-only reader。没有连接已有 MySQL 服务。
- Java 测试类路径启动器 `OjBrowserLab` 使用 `OjIsolatedServer`，只注册明确指定的组件，无业务应用扫描，不加载 application.properties。Tomcat 仅绑定 127.0.0.1 随机端口；本轮没有注册执行器。
- 为原 Vue 登录页/课堂导航增加的接口只在 `browserLab=true` 时注册于测试服务。`/api/login` 实际验证实验 MySQL 中的密码，再使用现有 OjTokenIssuer 签发 RS256；只接受实验学生密码登录，不提供教师或 `/login/user` 入口，不转换旧 HMAC Token。
- 原学生首页实际向 Java 查询本人的当前课堂成员关系。切换课堂重新走现有授权策略；导航 Token 由实验签发器签发，OJ 页面仍单独验证并只在内存保存 OJ Token。这里验证的是实验登录链，不是生产 SmartTT 登录整改已验收。
- 实验资格策略仍是明确的 LAB_ACTIVE/lab-term；MySQL 是 Mapper 契约子集夹具，不是正式 SmartTT schema 已验收的证据。
- 前端 `real-lab.mjs` 直接运行现有 index.html → src/main.js → 原 router。它不读取现有 .env/vite.config.js，所有已知 API host 绑定同源 /api，只转发到本轮 ready.json 中的 loopback Java 端口。不存在 Mock API 或判题响应。CSP 禁止外部连接、脚本及远程头像。
- 仅本轮实例显式开启 HTTP/认证；原生产默认关闭策略、4+6 个本地配置及部署工作流保持原样。

## 已实际验证的流程

1. 浏览器从 `/login` 输入随机实验学生凭据，真实 Java/MySQL 验证后进入 `/homes/studenthome`。
2. 学生从数据库返回的 lab-class 课堂卡片进入课堂，再点击“编程练习 OJ”。页面带入 classroomId，登录 OJ 后服务器返回 courseId=lab-course。
3. 加载加法/乘法两题；切题提示草稿变化；编辑并提交源码，真实 JSON 持久化，页面收到 64 位提交 ID、attemptNumber、WAITING_JUDGE。
4. 查看本人历史与详情；主动重交产生新尝试；刷新整个页面后 OJ 会话清除，重新登录即可恢复历史。代码草稿不持久化。
5. 从另一课堂入口进入 other-class，服务器确认 other-course；只有 oj-other-scope，lab-class 的两题不出现在目录中。
6. 受控修改私有实验目录并重启 Java：页面显示版本二，新页面提交的持久化快照 version/testVersion 均为 2；原提交仍是 1/1。HTTP 场景同时验证重启后旧 requestId 返回原记录。
7. 受控下架加法题并重启：页面目录只剩乘法题，“显示本课堂所有题目历史”仍能查看下架前记录。直接新提交下架题返回 400，不新增文件；旧 requestId 重试及本人详情/历史仍可查。
8. 故意延迟真实 Java 返回的响应 18 秒，触发前端 15 秒超时。提交实际已保存，页面显示“重试原请求（保持幂等）”，锁定原源码；重试成功且持久化文件数量前后均为 6，没有重复记录。
9. 实验数据库撤销成员后，真实请求返回 403，页面清除历史、题目、草稿与 OJ 会话。恢复成员后可重新验证。
10. 历史请求超时会提示错误；成功刷新更新为“提交记录已刷新”。停止本轮 Java 后再刷新，页面提示服务异常，不生成虚假业务响应。

另由 `verify_browser_http.py` 发出 **26 次真实 HTTP 请求**，有效断言覆盖错误密码/未登录、跨学生详情 404、非成员 403、同课堂其他学生历史为空、伪造 studentId 400、篡改课程/课堂 403、篡改 problemId 400、不同源码同 requestId 409、版本/测试快照、下架、撤销及隐藏字段检查。它不代替上述浏览器实操，也不执行判题。

本轮实验最终有 7 条持久化记录，状态全部 WAITING_JUDGE，结果文件为 0。代理记录共 137 条，其中 136 条取得 Java HTTP 响应（200/400/401/403/404/409），另有 1 条重启期间的传输 502。故障注入只延迟真实响应，不伪造 AC/WA/CE 或其他业务结果。

## 复现与停止

在后端目录，先编译测试类并运行安全 OJ Profile；只运行指定 Profile，不能运行全项目测试：

```powershell
# MAVEN_OPTS 仅对本次子进程设置，建议 Xms128m/Xmx512m/SerialGC。
mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests "-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC" test
```

从本次 Surefire XML 的 `java.class.path` 属性写出包含 target/test-classes、target/classes 和依赖的本地 classpath 文本。它只用于本地已编译测试服务，不提交：

```powershell
python -c "from pathlib import Path; import xml.etree.ElementTree as E; x=E.parse('smarttt-exam/target/surefire-reports/TEST-com.example.smartttexam.oj.OjSubmissionTests.xml').getroot(); p=Path('smarttt-exam/target/oj-unified'); p.mkdir(parents=True,exist_ok=True); (p/'classpath.txt').write_text(next(v.get('value') for v in x.find('properties') if v.get('name')=='java.class.path'),encoding='utf8')"
```

```powershell
python scripts/oj-lab/run_mysql.py --mysql-bin D:/phpstudy_pro/Extensions/MySQL8.0.12/bin --browser-classpath smarttt-exam/target/oj-unified/classpath.txt
```

该命令创建当前用户独占 ACL 目录及新数据库，输出实验目录。`target/oj-unified/active-lab.json` 仅保存目录指针。学生密码只保存在该私有目录 credentials.json；不得打印、上传或提交。浏览器分支的随机密码为 14 个十六进制字符，以兼容现有登录页 3–15 字符约束，不调整业务登录校验规则。

在前端目录，传入该实验目录启动真实主应用：

```powershell
node --max-old-space-size=2048 scripts/oj/real-lab.mjs <本轮私有browser目录>
```

访问 `http://127.0.0.1:45782`，使用私有临时凭据操作原页面。所有业务请求经真实 Java/MySQL；`preview.mjs` 是另一个仅供 UI 展示的 Mock 工具，不得用它报告真实联调成功。

从后端目录运行 `python scripts/oj-lab/verify_browser_http.py` 可复现 26 请求场景。每次使用新建的实验数据库；该工具会临时修订/下架私有目录并重启 Java，随后恢复目录及 alice 成员。不要在其他数据库或生产目录运行。

本地故障/重启控制仅通过 ACL 目录文件，不存在远程管理 HTTP：command.json 只接受 revoke/restore/join-bob/restart；Java 运行时上限由 Python 父进程限定为 1 小时。题目修改仅实验目录离线操作，生产管理仍使用已审核的私有目录工具。

停止时先在私有 browser 目录创建 `stop` 文件。Java 正常关闭存储锁，父进程随后关闭本轮 mysqld、删除临时凭据、关闭日志并保存 summary；必要时仅有界终止本轮 Popen。再创建 `stop-vite` 文件关闭本轮 Vite。不要按进程名结束其他 Java/Node/MySQL 服务。

## 集中验证与证据

全部证据保存在忽略目录 `smarttt-exam/target/oj-unified/` 和本轮私有实验目录，不提交日志、凭据或数据库产物。

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| Maven oj-tests | 120，0失败/错误/跳过，退出0 | maven-120-final.log、surefire-summary.json、target/surefire-reports |
| 真实实验 MySQL/HTTP | 原8项全部通过，退出0 | mysql-8.log；oj-real-e2e/mysql-276bc3da547a/surefire 与 summary.json |
| 前端逻辑测试 | 原31项全部通过，退出0 | frontend-31-final.log |
| CI正式报告校验 | 120唯一通过测试，基线完整，退出0 | report-verifier.log |
| 校验器夹具 | 原13场景及67项扩展基线逐项删除反例均符合预期，退出0 | ci-fixtures.log |
| 实验脚本安全测试 | 原24项通过，退出0 | lab-safety.log |
| 真实HTTP业务场景 | 26次请求断言通过，退出0 | http-scenarios.log；私有目录/http-scenarios.json |
| Vue/SFC及JS/Python语法 | 3个变更SFC编译、路由/客户端/实验入口语法通过，退出0 | 现有 scripts/oj/check.mjs 与语法检查 |

真实浏览器证据位于 `oj-real-e2e/mysql-daf220d31a54/browser/`：student-real-submit.png、student-final.png、browser-refresh.txt、browser-other-class.txt、browser-version2.txt、browser-unpublished-history.txt、browser-revocation.txt、browser-uncertain-submit.txt、browser-retry-success.txt、browser-retry-evidence.json、browser-timeout-recovered.txt、browser-backend-unavailable.txt、http-trace.jsonl。截图和 DOM 证据属于实验数据，勿公开发布。

该实例及后续8项回归实例均已正常停止，cleanup_errors=[]；bootstrap/credentials 已删除。safety-final.json 记录后端4个/前端6个配置哈希未变、暂存区为空、HEAD未变，以及全部WAITING_JUDGE/无结果文件检查。

## 主应用正式构建：未通过

实际运行原 `apps/main-project` 根入口及原受保护 Vite 配置，未裁剪路由/模块、未换成OJ专用构建，输出仅指定到忽略的 target/oj-unified/main-dist：

```powershell
node --max-old-space-size=3072 node_modules/vite/bin/vite.js build --outDir D:/KTZXproject/lunwen-code/smarttt-backend/smarttt-exam/target/oj-unified/main-dist
```

首次构建转换7635个模块后因共享 `packages/ui` 无 node_modules 链接、无法解析 d3 返回1。d3 已在原 package.json/锁文件中声明。按锁文件冻结恢复依赖时，离线缓存缺包，随后安装遇到 Windows 原生 lightningcss 文件占用（EPERM）；没有结束其他进程或覆盖原生二进制。最后仅补齐缺失的9个UI依赖链接，逐项核对锁定版本；对安装中断导致缺失的 Rollup/Oxide 包元数据，从官方包恢复，校验锁文件 SHA512，且确认现存原生二进制内容完全相同才恢复缺失文件。package.json、pnpm-lock.yaml 均未修改，Vite及其实际Rollup实现可正常加载。

依赖恢复后，3GiB正式构建转换9487个模块，随后 `Reached heap limit / JavaScript heap out of memory`，退出 **134**。未将模块转换完成当作构建成功。

最后对单个Node进程使用 **4096MiB** 堆进行一次尝试，并实时检查物理可用内存：启动时5504MiB，最低1012MiB，触及保留1GiB系统余量的阈值后，仅终止本轮Popen。日志已转换9484个模块并进入后续打包阶段；进程退出 **1**，`abortedForMemory=true`，不是完整构建通过。未增加到原package脚本配置的8GiB，以免挤占本机其他任务。无全局NODE_OPTIONS、环境或Vite配置修改。

日志：frontend-build.log、dependency-restore*.log、ui-dependency-links.json、frontend-build-restored.log、frontend-build-4096.log、frontend-build-4096-summary.json。当前 **主应用正式构建仍为阻断项**；需要在有足够内存的隔离构建环境验证原构建，或另行评估原全应用打包的内存优化，不能用真实dev页面和31项测试通过替代正式构建通过。

## 本轮修复和修改范围

- `scripts/oj-lab/run_mysql.py`：增加受控持久浏览器实验模式，复用原独占 MySQL 生命周期与清理；不复用业务数据库。
- `scripts/oj-lab/verify_browser_http.py`：真实实验 HTTP 场景与快照断言，不使用 Mock。
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/integration/OjBrowserLab.java`：测试类路径导航/登录适配、真实 SQL、私有重启和撤销控制。
- 同目录 `OjIsolatedServer.java`：增加默认不注册导航适配的构造参数；原8项测试仍使用原启动范围。
- 前端 `scripts/oj/real-lab.mjs`：真实主应用同源代理及明确的响应延迟故障注入，无 Mock 回退。
- 前端 `src/views/page/oj/index.vue`：成功刷新清除过期错误提示。
- 前端 `src/views/redrawPages/Login.vue`、`src/utils/request.js`：移除密码表单、包含Token的响应和Token的控制台输出；登录协议/教师流程未改。
- 本文及两端原学生联调文档同步说明。

没有新增业务接口或题目管理能力，没有改动本轮之前的 POM/CI基线/存储/授权/判题实现。依赖恢复只涉及忽略的 node_modules，不修改 package.json、锁文件或受保护配置；详情以本次构建结果记录为准。

## 尚未验收

2026-11-11集中审核P2修复：目录安装跨新旧目录和课堂绑定核对题目ID、测试版本和有序测试内容；改变隐藏输入或输出但测试版本不变时拒绝替换。新增2项回归，原120项保留，实际122项通过，日志p2-regression.log（退出0）。前端下架刷新保留原题ID和草稿并禁止提交，主动确认切题后才切换；目录失败也保留关联。原31项加2项回归共33项通过，三个SFC编译通过。

前端独立分支feature/oj-student新增oj-build.yml，仅测试及完整原主应用构建，无部署、Secrets或产物上传。Node20.20.2、原pnpm版本及冻结锁文件；不改变原Vite配置、完整路由、legacy或sourcemap。build-main.py要求至少11GiB可用内存，子进程8GiB堆，低于2GiB余量或超过30分钟停止本轮进程。当前本机资源不足，预检退出2，未再次强行构建；远程结果须以实际Actions为准。

真实 go-judge、C++17 编译执行及 CPU/内存/网络隔离仍未运行；没有专用 Linux VM。不将实验 WAITING_JUDGE 或 Mock 单元测试算作真实判题成功。

生产可信凭据证明、旧登录兼容整改、账号/学生/学期有效业务规则、实际数据库 schema 与教师授权仍需正式确认。生产 OJ 不得开放。本轮具备实验链路的集中复审证据；完整可部署验收还取决于主应用正式构建和上述真实环境前提。

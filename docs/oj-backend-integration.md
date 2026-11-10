# OJ 后端隔离集成成果（待独立审核）

2026-11-10，feature/oj-http-api，已提交基线092ba3176785c23b5789ae3beba17b3c6a38e563。包括上一轮未提交认证组件；没有Commit、Push或部署。本文更新早期oj-http.md与oj-production-auth.md阶段记录。

## 完成与边界

已实现独立RS256签发/验证契约、参数化只读SQL适配、条件Bean接线、HTTP结果白名单、go-judge HTTP客户端、单工作线程自动判题、原子结果日志及有界重试恢复。隔离测试接通真实Java组件及本地HTTP协议夹具。没有运行真实C++编译器、沙箱或数据库，不能声称真实go-judge联调或生产认证完成。

FileOjSubmissionRepository仅新增synchronized snapshots()，复用既有完整性检查提供脱离磁盘的快照。判题写入独立结果目录，不重写已审核提交文件，不改变schema2或旧记录迁移策略。原39项存储测试保持不变。

## 可信身份及业务阻断

登录身份JWT.id来自st_users.id，关联sm_student.usersid及cm_classroom_student.userId；证据详见oj-production-auth.md。重新核查LoginController与StUsersServiceImpl：/login/user将请求id/role参数交给getTeaInfor签发旧HMAC Token，代码未验证此前的密码登录证明，外部保护未知。不能用旧HMAC Token换取可信OJ Token。

OjTokenIssuer必须注入经审核的CredentialProof，验证本次凭据和有效账号后才返回数据库用户ID。RS256至少2048位，固定issuer/audience、id、iat和15分钟exp；使用后清除密码数组。没有默认私钥、生产Bean或HTTP签发入口，未把测试证明源注册到应用。隔离测试验证签发到OjStrictJwtIdentity的契约，不表示已接通旧登录。保留旧业务登录兼容性，也保留其风险阻断。

TestTokenController上一轮已移除所有组件及HTTP映射，本轮没有恢复。旧HMAC及任意身份测试Token不在新OJ信任域。没有使用ThreadLocal登录状态。

OjJdbcAuthorizationData使用现有JdbcTemplate和参数绑定：

| 检查 | 实际只读查询 |
| --- | --- |
| 学生映射 | st_users u JOIN sm_student s ON s.usersid=u.id WHERE u.id=?；读取catelog和双方status |
| 课程 | cm_course按id计数，必须恰好1条 |
| 课堂 | cm_classroom按id读取courseId、termId；保留全部行以检测重复 |
| 成员 | cm_classroom_student WHERE classroomId=? AND userId=?计数，必须恰好1条 |

SQL依据既有admin学生Mapper、exam课堂Mapper和course课堂成员导入/关联SQL；termId依据course CmClassroom实体。没有新增业务表或写入。状态数值、学期有效性不能猜测：Eligibility.student(userStatus,studentStatus)与classroom(classroomId,termId)为必需的已审核规则，没有默认允许实现。未知值必须拒绝；尚未提供生产Eligibility Bean。未通过真实MySQL验证这些SQL及实际学期表策略；动态学期表部署必须先确认，不自动猜后缀。

OjProductionAccess每次请求重新查询学生、课程、课堂、成员，要求唯一合法学生、catelog=1、已确认状态/学期、课堂属于指定课程及成员恰好1条。身份来自验签JWT而非请求字段。401未登录、403拒绝、503依赖异常；不返回内部原因。

## 条件接线及配置

OjAccessConfiguration仅oj.auth.enabled=true时注册；读取绝对路径X509 RSA公钥、issuer/audience，必须另有隔离DataSource与已确认Eligibility。缺少依赖启动失败。OjHttpConfiguration仍由oj.http.enabled=true控制；OjJudgeConfiguration由oj.judge.enabled=true控制。三个开关缺省false，本轮没有修改application文件。

模板oj-isolated.properties.example只作说明，不包含真实凭据。禁止直接配合原业务默认DataSource启动；需独立配置隔离库及可信签发方。isolatedEnabledConfigurationWiresAndCloses使用独立Spring上下文、Mock DataSource、临时密钥与目录验证接线/关闭，未启动整个业务应用。unconfirmedEligibilityCannotAutoEnableAccess验证不完整接线失败关闭。

## HTTP和判题流程

1. 三个既有/api/exam/oj/submissions接口先授权，POST复用OjSubmissionService持久化。原requestId幂等/冲突、分页、身份隔离保留。
2. 一个后台工作线程每秒扫描已校验提交快照，磁盘提交是可恢复队列；无额外内存任务队列，不启动宿主执行进程。
3. 通过go-judge POST /run在远端用/usr/bin/g++、-std=c++17 -O2编译，缓存main可执行文件，逐例运行并复用fileId。没有ProcessBuilder或宿主shell执行学生代码。
4. Accepted只表示正常退出，仍比较预期答案。比较规则CRLF转LF、忽略首尾空白，其余严格一致，不支持浮点特判。映射AC、WA、CE、TLE、MLE、RE、OLE、SYSTEM_ERROR；识别官方Nonzero Exit Status及兼容旧拼写。缺必要输出/退出状态、未知状态与HTTP异常一律技术失败，不能AC。
5. 编译CPU10秒/墙钟20秒/内存512MiB，编译stdout/stderr各64KiB；编译产物copyOutMax独立设置，默认16MiB、允许64KiB至64MiB，配置项oj.judge.compile-artifact-limit-bytes，禁止截断缓存。运行使用固定题CPU1秒/墙钟3秒/内存128MiB/每路输出64KiB，进程数32。HTTP连接/读取各15秒，作业60秒累计预算在阶段及响应读取中检查，单次阻塞仍以socket超时为界；最多100用例，HTTP响应上限2MiB，禁止重定向。TLS默认验证不关闭。
6. finally DELETE /file/{id}清缓存；清理失败也按技术失败处理。无法收到fileId的响应丢失须由go-judge管理员配置缓存TTL，不能承诺没有残留。服务配置可选Bearer认证，真实部署需网络隔离/TLS，禁止对学生或公网开放执行器。
7. 结果日志专用绝对目录，与提交目录不重叠；独占进程锁、严格单JSON/重复字段拒绝、SHA256、临时文件force+原子移动，无非原子降级。RUNNING已持久化后执行；DONE/FAILED终态不再调度；首次技术失败RETRY，最多第二次尝试后FAILED；崩溃遗留RUNNING同样最多两次，防无限执行。存储异常触发worker unhealthy，下次扫描可恢复。结果日志损坏拒绝启动，不静默修复。
8. HTTP详情/历史可增加judgement={state,verdict,timeNs,memoryBytes}；没有结果时省略字段。原status仍是不可变提交记录的WAITING_JUDGE，判题状态以judgement.state为准。不返回隐藏测试、预期输出、stdout/stderr、编译日志、校验和或路径。

MVP仍只支持固定加法题oj-fixture-add及cpp17，没有真实题库或学生页面。结果日志保存最新结果及尝试次数，不提供人工重判API或逐轮判题审计历史；主动重交仍产生独立提交及原attemptNumber。

## 测试与CI

原39存储+14 HTTP+14认证保留，新增OjBackendTests 24个JUnit方法：签发验证、JDBC映射/拒绝、Spring配置启用/关闭、HTTP到本地go-judge协议夹具、AC/WA/CE/资源限制、超时/畸形响应、并发去重、自动调度、重启恢复、重试/耗尽、日志完整性、响应隐私。JDBC仅Mock接口，go-judge仅HTTP模拟响应，不会执行源码。

Maven oj-tests Profile已显式包括四类安全测试，CI基线从实际Surefire XML提取共91项，保留原53并加入认证14和新24。校验器仍允许未来增加唯一测试；原13个夹具子场景保留，增加38个新增基线逐项缺失反例。现有工作流和部署流程不改。未推送，不能称新版本远程Java8通过。

实际验证命令：

```
mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests "-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC" test
python scripts/ci/verify_oj_reports.py smarttt-exam/target/surefire-reports
python scripts/ci/test_verify_oj_reports.py
```

本机沿用JDK21.0.10/Maven3.8.6，目标Java8。MAVEN_OPTS只在进程内限128/512MiB堆、256MiB Metaspace及SerialGC，退出恢复。没有改系统环境或依赖版本。正式日志和退出码保存target/oj-backend-evidence，Surefire XML位于target/surefire-reports，不提交测试产物。

协议依据：go-judge官方README、https://docs.goj.ac/api、https://docs.goj.ac/example；核对页面保存在本地证据目录，真实版本兼容性仍需沙箱联调。

## 距完整可用后端仍缺什么

- 可信CredentialProof、登录入口集成与签名私钥管理；确认/login/user兼容性及保护方案。
- 明确账号/学生状态和学期/课堂规则，落实Eligibility并独立审核；真实隔离MySQL结构/查询验证。
- 在另台隔离Linux机器运行经审核的go-judge/g++，验证cgroup、内存/CPU/墙钟/输出/进程和网络限制；不在业务宿主执行学生代码。
- 使用真实签发方、隔离库、真实沙箱端到端验收及新版Java8 CI。全部通过后才决定显式开放。

可对当前增量进行独立代码审核，但尚不具备生产开放条件。原4个本地配置必须保留；无Commit/Push/PR/Merge/部署。

## 当前未提交交付文件（含上轮认证核心）

- `docs/oj-backend-integration.md`
- `docs/oj-isolated.properties.example`
- `docs/oj-production-auth.md`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjAccessConfiguration.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjAuthorizationData.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjJdbcAuthorizationData.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjProductionAccess.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjStrictJwtIdentity.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjTokenIssuer.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjVerifiedIdentity.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/judge/GoJudgeClient.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/judge/OjJudgeConfiguration.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/judge/OjJudgeDispatcher.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/judge/OjJudgeJournal.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/OjBackendTests.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/OjProductionAccessTests.java`
- `docs/oj-ci.md`
- `scripts/ci/oj-baseline.txt`
- `scripts/ci/test_verify_oj_reports.py`
- `scripts/ci/verify_oj_reports.py`
- `smarttt-exam/pom.xml`
- `smarttt-exam/src/main/java/com/example/smartttexam/controller/TestTokenController.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/FileOjSubmissionRepository.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjHttpController.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjHttpDtos.java`

## 上一轮集成实际结果（91项）

最终测试日志final-tests.log：Tests run: 91, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS。exits.txt记录MAVEN_EXIT=0、REPORT_EXIT=0、FIXTURES_EXIT=0。四类Surefire分别39/14/14/24项；91项基线完整，原13个报告夹具场景及38个新增基线逐项缺失场景均符合预期。测试使用临时目录、Mock JDBC、回环HTTP服务与独立Spring上下文，没有访问真实业务服务。末次复核四个本地配置哈希保持一致、暂存区为空。没有执行package或远程Java8验证，本轮结论仅为本地编译及隔离测试通过。

## 三项P2修复及回归（2026-11-10，108项）

本次以用户消息中三项审核问题及修复要求为依据；仓库及target中未定位到另外保存的本轮完整审核报告，没有将旧存储审核报告当作本轮报告。先读取实际GoJudgeClient、OjJudgeDispatcher、OjBackendTests、配置及CI校验脚本，再修复。所有变更仍未提交。

### P2-1：禁止数值有损转换

GoJudgeClient.exitCode在转换前要求isIntegralNumber且canConvertToInt，然后intValue；拒绝超出[-2147483648,2147483647]的整数、小数、字符串、布尔、数组、对象、null及缺失字段。两个原反例4294967296和-4294967296均抛出协议IOException，不可能回绕成0。

同一run响应入口对编译和运行阶段都检查exitStatus、time和memory；time/memory必须是[0,Long.MAX_VALUE]精确整数，runTime/procPeak出现时同样校验。仅在范围验证后读取longValue，防止BigInteger截断、字符串/小数强制转换或负值绕过。非法响应由现有调度器保存SYSTEM_ERROR，先RETRY再最多第二次FAILED。

### P2-2：编译失败与执行器故障分开

compileStatus使用明确分类，不再让任意非零exitStatus覆盖状态：

| 响应 | 处理 |
| --- | --- |
| Accepted且精确exitStatus=0，无执行器/文件错误 | 才能继续验证缓存ID及运行 |
| Nonzero Exit Status（兼容旧拼写）且精确非零退出，无执行器/文件错误 | CE，DONE且不自动重试 |
| Accepted却非零退出、Nonzero却退出0 | 矛盾协议，SYSTEM_ERROR |
| 未知状态、Internal Error、File Error、编译阶段资源限制/信号等 | SYSTEM_ERROR，不归责学生编译错误 |
| 非空error或非空/异常fileError | SYSTEM_ERROR，即使状态声称Accepted或普通编译失败 |

缺少/非法数值或缓存ID是IOException，同样进入技术失败路径。OjJudgeDispatcher原有两次上限、终态去重和恢复逻辑无需修改；新增测试验证RETRY到FAILED跨重启仍成立，终态文件字节不再改变，也不再请求执行器；技术故障恢复后可第二次DONE/AC，但不重复执行终态。

### P2-3：编译产物与输出预算独立

根据go-judge官方API文档（本地核对证据为target/oj-backend-evidence/go-judge-api.html），copyOutCached将产物拷贝至缓存，copyOutMax是拷出文件大小上限，不是stdout/stderr收集器的max。现在编译请求copyOutCached=[main]，copyOutMax默认16777216，copyOutTruncate=false；两个输出收集器各维持65536，CPU/墙钟/内存/进程上限不变。运行请求输出预算仍由题目outputLimitBytes控制，没有被编译产物预算放大。

OjJudgeConfiguration新增有界配置oj.judge.compile-artifact-limit-bytes，默认16MiB；客户端构造时拒绝小于64KiB、大于64MiB或溢出值，异常配置不能成为无限制执行。仅文档模板增加示例，四个本地application配置未修改。

HTTP夹具用artifactBytes表示模拟产物大小，按请求copyOutMax决定返回缓存ID或File Error/CopyOutSizeExceeded。覆盖32KiB、96KiB、恰好16MiB、超过16MiB，以及自定义128KiB和上限+1。此处没有生成真实可执行文件，也没有真实沙箱验证；夹具只证明请求预算和客户端判定契约。

### 新增17项JUnit测试

原91项全部保留：原24项后端测试方法体逐字比较未变化，只扩展共享HTTP夹具，使Nonzero状态具有真实非零退出，并支持响应字段/模拟缓存大小故障注入；另外三类原测试文件字节未变。

| 新测试方法（均位于OjBackendTests） | 主要断言 |
| --- | --- |
| rejectsExitStatusOutsideIntRangeInBothPhases | 编译/运行正负超范围均拒绝，含原4294967296反例 |
| rejectsNonIntegralAndMissingExitStatusInBothPhases | 错误类型、小数、null及缺失拒绝 |
| exactIntegerBoundariesNeverWrapToAcceptance | int最小/最大、-1、0、1不发生截断误AC |
| rejectsLossyAndNegativeResourceMetadataInBothPhases | time/memory及可选runTime/procPeak拒绝溢出、负值或错误类型 |
| acceptsExactNonnegativeLongResourceBoundaries | 0及Long.MAX_VALUE精确保存 |
| unknownAndInfrastructureCompileStatusesNeverBecomeCe | 未知状态+退出1及基础设施错误不判CE |
| contradictoryAndMalformedStatusesAreTechnicalFailures | 状态/退出码矛盾、非字符串/空状态不判正常 |
| explicitExecutorErrorsCannotBeHiddenBySuccessOrNonzeroExit | error/fileError不能被Accepted/非零状态掩盖 |
| normalAndAbove64KiBArtifactsUseIndependentCacheBudget | 模拟32KiB/96KiB/16MiB产物可缓存，输出和资源预算独立 |
| artifactBudgetIsBoundedAndConfigurable | 配置边界、128KiB预算及超出预算判技术失败 |
| artifactOverflowAndMissingCacheIdAreTechnicalFailures | 真正超预算及缺缓存ID不判CE/WA/AC |
| isolatedArtifactPropertyControlsConfiguredClient | 隔离Spring读取自定义属性，HTTP没有因此启用 |
| overflowingExitCodePersistsAsBoundedTechnicalFailure | 溢出原例先RETRY后FAILED，重启/终态不重复 |
| unknownCompilerFailurePersistsAsBoundedTechnicalFailure | 未知编译状态原例进入有界重试 |
| cacheOverflowPersistsAsBoundedTechnicalFailure | 缓存失败进入有界重试及技术失败持久化 |
| unknownCompilerFailureCanRecoverWithoutRepeatingTerminalWork | 故障恢复后第二次成功，终态不重调度 |
| genuineCompilerExitFailureIsTerminalCeWithoutRetry | 真正编译器非零退出为DONE/CE，不重试 |

### 本轮实际执行结果与范围

命令与前述隔离测试命令一致，沿用本机JDK21.0.10/Maven3.8.6/Java8编译目标，只临时限制进程MAVEN_OPTS并恢复。最终Maven执行108项，0失败、0错误、0跳过；四类分别39/14/14/41项，退出0。没有将编译成功等同于测试成功；没有运行全项目测试或启动真实业务应用。

原91条基线逐字保留并追加实际Surefire XML中的17条身份。正式校验器确认108个唯一通过测试及完整基线，退出0；原13个夹具场景全部符合预期，且新增后的55个认证/后端基线逐项删除均退出1（包含本轮全部17项），两个Python测试方法通过、退出0。报告仍接受额外唯一新测试，没有弱化失败/错误/跳过检查。

本轮日志、退出码、变更前哈希和原测试快照位于target/oj-p2-fixes；正式JUnit证据在target/surefire-reports。tests-final.log记录108项，exits.txt为MAVEN_EXIT=0、REPORT_EXIT=0、FIXTURES_EXIT=0。隔离目录不会提交。

本轮实际仅修改9个文件：GoJudgeClient.java、OjJudgeConfiguration.java、OjBackendTests.java、scripts/ci/oj-baseline.txt、scripts/ci/verify_oj_reports.py、scripts/ci/test_verify_oj_reports.py、docs/oj-isolated.properties.example、docs/oj-ci.md及本文。未修改OjJudgeDispatcher、提交存储、HTTP Controller、身份授权、POM或任何工作流。原四配置文件按修复前哈希核验保持字节不变。

三个原始反例在隔离夹具中不再复现，具备针对性独立复审条件。可信生产身份源、真实数据库规则/授权及真实go-judge沙箱联调仍是原有阻断，未宣称生产可用或远程Java8验证通过；未启用任何生产开关，未Commit/Push/PR/Merge/部署。

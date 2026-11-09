# OJ轻量接入当前实现

## 范围

已选执行器为go-judge，当前只实现提交数据结构、固定题目、文件存储及内部Service。
没有新增HTTP接口、学生页面、实际判题调用或成绩写入。新增类不参与自动组件扫描，不改变现有应用启动行为。
后续Controller必须由已验证的登录及课程授权构造Owner，不能将客户端studentId直接作为可信身份。

## 提交语义

- 固定题目为`oj-fixture-add`，版本1，测试版本1，开发期语言标识为`cpp17`；真实编译器版本待go-judge联调核实。
- 固定题及测试是开发夹具，未挂接正式题库或实际教学班级，不伪造KW-A标签。
- `OjSubmissionService.submit`接收可信Owner、请求键、固定题ID、语言及代码。
- 代码最大256 KiB，原样保存（包括中文、合法代理对及换行），服务端使用UTC时间。UTF-8编码器使用REPORT模式，在源码哈希、请求指纹及落盘之前拒绝非法UTF-16代理序列；不替换字符、不做Unicode规范化。Service和存储入口均检查，避免直接调用存储时绕过。
- 同一学生、课程、课堂范围内，相同requestId及相同内容返回原提交；内容不同则报冲突。
- 主动再次提交使用新requestId，即使代码相同也保留新记录和递增尝试序号。
- 写入成功后才返回记录，状态为WAITING_JUDGE，executionMode为NOT_RUN，judgeAttempts为空。
- NOT_RUN不是MOCK，也不是判题通过；当前没有任何真实编译或运行结果。
- 内部find/history按完整Owner隔离，历史分页上限100。返回的是持久化模型，含隐藏测试，不得直接作为学生HTTP响应。

## 存储约定

`FileOjSubmissionRepository`必须显式传入专用目录，建议使用仓库外实验目录。当前没有默认开启的存储配置，不修改application.properties。

每次提交对应一个JSON文件，文件名由身份范围与requestId的SHA-256决定；幂等信息和作答在同一个文件中，不使用可能与正文不一致的独立索引。

写入顺序为同目录临时文件、文件内容force、原子移动。文件系统不支持原子移动时失败，不降级为直接覆盖写。失败向调用方抛出异常，不返回成功提交；清理本次临时文件，同一requestId可在故障排除后重试。既有不完整临时文件仍按原约定保留，不能作为提交记录读取。

新增记录使用schemaVersion=2。recordChecksum为完整持久化模型（排除recordChecksum自身）的SHA-256，参与校验的对象属性按名称排序，覆盖尝试序号、提交时间、任务状态、执行模式和判题记录，以及源码、身份和题目快照。保留原有源码哈希和请求指纹语义，不因新增校验和改变合法源码的幂等匹配。该校验和用于发现意外损坏，不是防止本机恶意篡改的签名。

读取时校验必要字段、schema、文件名、源码哈希、内容指纹和记录校验和；拒绝重复JSON键，以及非整数、超范围或非正的attemptNumber。在启动、历史扫描和新提交分配序号前，以完整Owner和problemId分组，要求尝试序号从1开始连续递增，拒绝重复或缺口。当前不支持删除单条历史记录，手动删除造成的缺口会阻止继续写入。使用Math.addExact递增，达到long上限时明确报错，不回绕为1。发现异常不静默修正或删除历史。

### 旧MVP记录兼容

旧schemaVersion=1记录没有元数据校验和，程序无法追溯证明其尝试序号和时间从未被修改。默认构造器遇到旧记录时明确报错并保留原文件，不自动迁移，不把未受保护的历史默认为完好。运行中的正常查询也拒绝格式降级，不能通过修改schemaVersion绕过校验。

只有在停止写入、备份整个目录并从外部核实旧历史后，调用方才能显式选择迁移：

```java
new FileOjSubmissionRepository(directory, Clock.systemUTC(),
    FileOjSubmissionRepository.LegacyPolicy.MIGRATE_AFTER_EXTERNAL_VERIFICATION);
```

迁移前先检查全部记录原有源码哈希、请求指纹、字段及分组序号；任何异常均阻止迁移。迁移逐文件原子替换为版本2，保留提交ID、请求键、源码、题目快照、时间及序号；RUNNING仍按原恢复规则变为INTERRUPTED。中途失败可能留下版本1/2混合目录，已成功迁移的数据仍保留，可在排除故障后再次显式迁移。迁移完成后使用默认构造器即可。本轮只对隔离夹具验证迁移，没有迁移用户历史目录。

目录持有操作系统文件锁，只允许一个仓库实例写入。仓库是AutoCloseable，调用方必须关闭它。当前不支持多进程共享目录、多实例部署或网络文件系统。

重启时WAITING_JUDGE保持待判题，RUNNING标记为INTERRUPTED。残留临时文件不作为成功提交，不自动重放判题；后续人工排查后再清理。

当前遍历JSON进行历史查询及计算尝试编号，仅适用于小规模单进程MVP。文件锁和原子移动不构成断电、硬盘损坏或恶意本地修改下的数据绝对保障；真实采集前需要目录访问控制、容量监控及备份。

## 数据结构

- Submission：格式版本、记录校验和、身份范围、提交ID、请求键、题目/测试快照、源码、源码哈希、语言、提交时间、尝试序号及任务状态。
- Problem：固定题面、版本、测试、KW-A编号快照及时间/内存/输出限制。
- JudgeAttempt和CaseResult：预留独立的每次判题、编译器版本、执行模式、编译输出、逐测试判定及资源消耗字段；后续接入时实现追加和状态更新，当前不写入虚构结果。

## 隔离回归检查

先核对README及AGENTS中的环境要求。当前保留JDK、Maven、依赖及Surefire 2.22.2版本，仅调整exam测试选择配置。

当前使用现有JUnit Jupiter 5.8.2及exam模块显式配置的Surefire 2.22.2。原默认Surefire 2.12.4不能发现JUnit 5方法；本次未新增项目依赖，未调整Spring Boot、Maven或JDK版本。39个@Test方法由JUnit独立执行，main反射入口已移除。

在后端根目录使用PowerShell执行：

```powershell
mvn -B -pl smarttt-exam -Poj-tests test
if ($LASTEXITCODE -ne 0) { throw 'OJ Profile回归检查失败' }
# 或使用定向命令：
mvn -B -pl smarttt-exam -Dtest=OjSubmissionTests test
if ($LASTEXITCODE -ne 0) { throw 'OJ回归检查失败' }
```

这不是执行现有全量测试，不启动Spring上下文，不连接MySQL、Redis、MinIO、模型或OJ。JUnit @TempDir为每个方法提供独立临时目录，测试结束自动递归清理；文件仓库和执行器仍按原try/finally规则关闭。夹具不是真实学生数据。

默认Surefire保留2.22.2和failIfNoTests=true，不再配置includes/excludes，恢复通用测试发现规则。只有显式启用oj-tests Profile时才限定OjSubmissionTests，Profile继承2.22.2并明确设置failIfNoTests=true，未发现目标测试会失败。Profile不默认激活，仅配置在exam模块，不改变其他模块。

普通`mvn -pl smarttt-exam test`可能发现原SmartttExamApplicationTests并启动Spring上下文；该测试没有被永久排除。数据库、Redis、MinIO等资源尚未确认隔离，本轮禁止且未执行普通默认test，不能宣称旧Spring集成测试通过。默认发现行为仅通过POM、effective-pom及原测试源码静态核查；实际资源隔离后另行验证。也不执行根项目全量test。

### Maven自动化集成验证（2026-11-09）

白名单修复后实际执行两种命令：`mvn -B -pl smarttt-exam -Poj-tests test`和`mvn -B -pl smarttt-exam -Dtest=OjSubmissionTests test`。均BUILD SUCCESS、退出码0；各自独立保存的Surefire XML均为39项、0失败、0错误、0跳过，39个方法名逐一核对无遗漏。日志及报告在`smarttt-exam/target/oj-profile-fix/`。另执行help:effective-pom，默认build/plugins中Surefire为2.22.2、failIfNoTests=true且无includes/excludes。没有运行默认test或原Spring测试，未修改OJ源码和测试代码。

目标命令实际BUILD SUCCESS，退出码0；Surefire XML和文本报告均为Tests run:39、Failures:0、Errors:0、Skipped:0，测试名称逐一与HEAD版本的39项核对一致。全部测试逻辑与断言保留，业务实现无变化。

故意失败验证只在`target/oj-maven-integration/negative`创建独立测试工程副本，复用当前生产源码只读编译、相同JUnit依赖及Surefire配置。副本仅把一个源码相等断言改成错误期望。实际命令`mvn -B -f smarttt-exam/target/oj-maven-integration/negative/pom.xml -Dtest=OjSubmissionTests test`执行39项，1项失败、0错误、0跳过，BUILD FAILURE、退出码1。正式测试源码未受故障注入影响。

正式报告位于`smarttt-exam/target/surefire-reports/TEST-com.example.smartttexam.oj.OjSubmissionTests.xml`；运行日志、负例报告及退出码位于`smarttt-exam/target/oj-maven-integration`。以下历史验证记录中的手动main和“未集成”说明仅描述当时状态，当前以本节标准Maven命令为准。

测试类保留原16个@Test方法。正常并发批次总等待上限10秒，每个Future读取上限1秒；超时取消明确触发失败。执行器在finally中shutdownNow，并等待最多5秒，未终止同样判失败；检查线程使用daemon防止清理失败后JVM无限挂起。新增用例故意制造超时和任务异常，检查失败传播和线程清理。

写入故障使用包内可见PersistenceIO替换点，不使用权限变更或破坏真实目录：临时文件实际写入部分内容后抛出IOException；或完整写入并force后模拟AtomicMoveNotSupportedException。断言没有成功返回值、没有目标JSON记录、失败不消耗尝试序号、历史不变、临时文件已清理，以及重试、重启后的幂等与历史完整性。还覆盖恢复写入失败、迁移中途失败及后续重试。

修复前已实际执行exam打包及原16项检查，均通过。修复后的最终执行结果见下方验证记录。

### 2026-11-09修复验证记录

- 环境保持现有Maven 3.8.6、JDK 21.0.10、Java 8编译目标；未修改环境、POM、数据库配置、业务Controller/Service/Mapper或部署流程。
- 修复前：上述package及类路径生成命令BUILD SUCCESS，隔离main入口实际执行16项，16项通过。
- 修复后：相同package及类路径生成命令BUILD SUCCESS；隔离main入口实际执行35项，原16项全部保留并通过，新增19项全部通过，进程退出码0。
- Maven使用-DskipTests，因此Maven打包成功不计作测试通过；35项结果来自显式main调用@Test方法及JUnit断言，不启动Spring，不连接数据库、模型或真实OJ。
- 证据位于`smarttt-exam/target/oj-fix-evidence/`：`baseline-build.log`、`baseline-checks.log`、`final-build.log`、`final-checks.log`。属于本地可再生成验证产物，不提交Git。

新增19个测试方法（参数循环中的多个边界不另行累计测试数量）：

| 测试方法 | 验证内容 |
| --- | --- |
| rejectsMalformedUnicodeBeforePersistence | 孤立高/低代理、错序及截断代理被拒绝，无文件、无序号消耗；合法问号可随后提交 |
| repositoryRejectsMalformedUnicodeBeforeHashing | 直接调用存储也不能绕过源码编码检查 |
| preservesValidUnicodeWithoutNormalizationAndRejectsConflicts | 中文、补充平面字符、换行原样保存；组合/分解形式不归一化，幂等冲突正确，重启原文不变 |
| checksumDetectsCriticalMetadataChanges | 序号、时间、状态、执行模式和判题记录变化导致校验失败，原文件不被改写 |
| rejectsMissingAndNonintegralMetadata | 字段丢失、小数序号、超过long范围、缺少版本或校验和均被拒绝 |
| rejectsSchemaDowngradeWhileStorageIsOpen | 打开存储后降级版本、删除校验和，查询/重试/新建均不能绕过校验 |
| rejectsAbnormalAttemptNumbersEvenWithMatchingChecksum | 即使重新计算校验和，0、负数或不连续的long最大序号仍被拒绝 |
| rejectsDuplicateAndGappedAttemptSequences | 跨记录重复和序号缺口被拒绝 |
| safeAttemptIncrementFailsInsteadOfWrapping | 0到1及最大值前一项递增正常，long溢出和非法前值明确报错 |
| sequenceValidationKeepsDifferentOwnersIndependent | 不同学生的序号分别从1递增，重启后历史隔离保持 |
| legacyRecordsRequireExplicitVerifiedMigration | 默认保留旧文件并拒绝；显式迁移保持源码、时间、指纹、幂等和后续序号，重启可读 |
| legacyMigrationValidatesWholeHistoryBeforeWriting | 旧记录中存在重复序号时整个预检失败，其他记录不被提前迁移 |
| interruptedLegacyMigrationCanResumeWithoutLosingHistory | 第二个文件迁移失败保留混合版本，显式重试后历史完整、序号连续 |
| temporaryWriteFailureIsNotAcknowledgedAndCanRetry | 实际部分临时写入失败，无成功提交，清理后同键重试及重启正确 |
| atomicMoveFailureIsNotAcknowledgedAndCanRetry | 实际完整写入后原子移动失败，无目标记录，重试及重启历史不变 |
| failedWritePreservesHistoryAcrossReopenBeforeRetry | 写失败后先关闭再重开，旧历史完整、失败提交不存在，重试序号正确 |
| failedRecoveryKeepsRunningRecordForNextRestart | RUNNING恢复时移动失败保留旧文件并释放锁，下次启动正确恢复并更新校验和 |
| concurrencyTimeoutFailsAndTerminatesExecutor | 故意阻塞任务，触发明确超时失败并验证线程池终止 |
| concurrencyExceptionTerminatesExecutor | 任务异常向外传播，finally清理线程池 |

正常行为对比：精确源码保存、同键同内容返回原记录、同键不同内容冲突、新键主动重交、身份隔离、历史分页及RUNNING恢复保持原16项约定。变化限于非法Unicode和损坏记录被明确拒绝、写失败可控验证及并发检查有界等待；旧格式采用显式迁移，不静默丢弃或自动信任历史。

本轮未验证真实判题、HTTP身份边界、断电或真实磁盘硬件故障；故障注入只证明上述文件操作失败下的程序行为。未执行Commit、Push、创建PR或部署。

## 下一阶段

### 第二轮复审P2修复验证（2026-11-09）

记录读取器启用现有Jackson的`FAIL_ON_TRAILING_TOKENS`：只接受一个完整根对象及合法尾随空白，第二个根值或非法文本均抛出IOException。版本2读取、启动恢复预检及版本1显式迁移预检共享该读取器；预检全部成功前不改写任何历史。原checksum与版本兼容策略不变。

新增4项检查：正常JSON及尾随空格/换行/制表符；查询及幂等重试拒绝第二对象、非法文本、数组和null；恢复遇异常时两个历史文件均逐字节不变；迁移遇异常时两个旧文件均逐字节不变。

实际执行`mvn -B -pl smarttt-exam -DskipTests package dependency:build-classpath '-Dmdep.outputFile=target/oj-trailing-fix/classpath.txt'`，BUILD SUCCESS，退出码0。随后使用该类路径和target/test-classes、target/classes执行`com.example.smartttexam.oj.OjSubmissionTests`，夹具目录为`smarttt-exam/target/oj-trailing-fix/checks`，实际39项通过，退出码0。原35项逐名核对全部保留并通过，新增4项全部通过。打包不等于测试自动执行，手动入口未集成Maven生命周期的限制仍保留。

本轮日志：`smarttt-exam/target/oj-trailing-fix/build.log`、`checks.log`及`exits.txt`。未连接数据库/OJ，未修改POM、环境、业务接口或部署配置，未执行Commit、Push或PR。

先建立不暴露隐藏测试的提交/查询DTO、经过验证的身份边界及独立测试入口，再接学生页面。
随后实现go-judge HTTP适配、编译产物缓存、多测试输出比较、有界异步调度及结果追加。
真实判题机可用后验证C++版本、资源限制、实际错误分类和并发；此前不能宣布OJ闭环通过。

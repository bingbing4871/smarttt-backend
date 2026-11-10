# OJ Maven CI

## 工作流及边界

新增`.github/workflows/oj-ci.yml`，只编译、打包和运行隔离OJ测试。没有部署、SSH、数据库操作、业务服务容器或生产Secrets引用。不修改现有部署工作流、POM、业务实现和测试源码。

- push：只匹配`feature/**`，不匹配main。
- pull_request：只匹配目标分支main；不使用pull_request_target。
- 权限：contents: read；checkout关闭persist-credentials。
- 环境：ubuntu-24.04、actions/checkout@v4、actions/setup-java@v4、Temurin Java 8。步骤打印java与mvn版本。Maven使用runner自带版本；当前官方清单为3.10.0，Apache文档要求Java 8，首次远程运行仍需核对实际版本。
- 超时20分钟；同一工作流和ref的新运行取消旧运行。
- 无缓存恢复要求，依赖和Maven插件可从Maven Central下载。临时settings位于RUNNER_TEMP，mirrorOf=*避免旧传递POM引用的过时仓库；不包含账号或密码，不修改本机settings。

## 原有部署风险

| 文件 | 触发 | 已确认的操作 |
| --- | --- | --- |
| main.yml | main push、workflow_dispatch | 引用HOST120、USERNAME120、SSH_PRIVATE_KEY120 Secrets；上传源码并SSH执行部署脚本，包含远端清理操作 |
| deploy-stable.yml | release published、workflow_dispatch | 引用HOST、USERNAME、SSH_PRIVATE_KEY Secrets；上传、停止服务、删除旧目录、执行部署脚本并重启nginx；scp-action使用浮动master引用 |

这些是代码静态检查结果，未验证工作流启用状态或凭据有效性。origin为bingbing4871/smarttt-backend个人Fork，upstream为KTZX-ncut/smarttt-backend。新CI不调用上述工作流，不发布Release，也不产生推送。功能分支push和PR事件不匹配现有部署触发条件；未来合并到main产生的push仍可能触发现有main部署工作流，本次未消除此风险。

## 执行步骤

checkout → Java 8 → 生成临时Central settings → exam打包 → OJ测试 → 核验Surefire → 保留报告。

```bash
mvn -B -ntp -s "$RUNNER_TEMP/oj-maven-settings.xml" -f smarttt-exam/pom.xml -DskipTests package
mvn -B -ntp -s "$RUNNER_TEMP/oj-maven-settings.xml" -f smarttt-exam/pom.xml -Poj-tests -DskipTests=false -Dmaven.test.skip=false test
```

第一条命令只负责编译和打包，不能代表测试通过。第二条仅启用已审核的oj-tests Profile，不运行旧contextLoads。Surefire配置failIfNoTests=true，测试失败或未发现测试会使Maven失败；步骤没有continue-on-error。

报告检查已提取到`scripts/ci/verify_oj_reports.py`，只用Python标准库。读取报告目录全部TEST-*.xml，以(classname,name)全局去重；缺失身份、跨文件重复、XML损坏或缺失、零测试均失败。逐项统计failure/error/skipped，与每个testsuite的tests/failures/errors/skipped四个非负整数属性核对；任何逐项异常均失败。固定清单`scripts/ci/oj-baseline.txt`保护53项已审核测试：原39行方法名保持不变，默认属于OjSubmissionTests；新增14行使用`com.example.smartttexam.oj.OjHttpTests#方法名`。load_baseline校验53个唯一身份、存储类39项及HTTP类14项。报告必须完整包含这些类与方法，不能用同数量的其他用例替代。允许报告未来增加独立用例；扩大已审核基线须显式审核并同步数量约束，不得自动从修改后的源码重建基线以掩盖缺失。

工作流先执行`python3 scripts/ci/test_verify_oj_reports.py`，再执行`python3 scripts/ci/verify_oj_reports.py smarttt-exam/target/surefire-reports`。13个隔离子场景验证正常53项、重复、failure/error/skipped与汇总矛盾、数量不符、跨文件重复、缺失基线、报告缺失/损坏、零测试，以及新增测试和合法跨文件拆分。夹具按真实(classname,name)生成，不再把全部方法归入存储测试类。异常场景断言子进程退出1，成功场景断言退出0。未更改正式JUnit测试、POM或部署流程；Artifact上传规则及7天保留不变。

2026-11-10 HTTP基线扩展：HTTP骨架已作为默认关闭的本地阶段成果提交，生产认证及课堂成员授权未完成，接口不得正式开放。14个名字从实际OjHttpTests.java提取并与真实Surefire XML逐一核对后才加入清单。实际Maven OJ Profile执行53项、0失败/错误/跳过，退出0；正式校验器验证53项完整，退出0；13个夹具子场景全部符合预期，退出0。额外对真实报告副本逐一删除14项HTTP用例中的每一项，并同步修正tests汇总值，全部14次校验退出1且明确指出缺失基线；原XML不变。新增报告用例与合法跨文件拆分仍被接受。证据位于smarttt-exam/target/oj-baseline-53。基线扩展修改尚待独立复审，未提交、未推送，未改HTTP实现或测试逻辑。

exam父POM通过../pom.xml从checkout读取；exam没有依赖其他SmartTT本地模块，也没有systemPath或私有仓库依赖，不需要先install其他模块。当前以-f smarttt-exam/pom.xml直接选择exam，加载父POM但不从根聚合器扫描evaluation等兄弟模块。-f不改变Shell工作目录：后续Python命令仍从仓库根运行，Surefire及上传路径仍为smarttt-exam/target/surefire-reports。

actions/upload-artifact@v4在always()条件下收集Surefire的TEST-*.xml和*.txt，保留7天。未产出报告会提示warning，之前的构建/测试失败仍保留。编译错误可在Actions步骤日志查看。禁止上传JAR、打包配置或整个target目录；测试报告仅包含隔离夹具和测试进程信息，不应改为记录真实学生数据。

## 本地验证与远程待验证项

### 2026-11-10：修正构建入口

首次远程运行[42763715461](https://github.com/bingbing4871/smarttt-backend/actions/runs/42763715461)实际使用Temurin Java 8和Maven3.10.0。根目录-pl命令在模型解析阶段因evaluation POM的5处重复依赖失败，OJ测试和校验器未执行，未生成报告。旧版本Maven曾将相同问题报告为警告，旧的本地成功不能替代此远程结果。

本次工作流只把两处-pl smarttt-exam替换为-f smarttt-exam/pom.xml；其余参数、Profile、脚本、权限、触发和上传步骤保持不变，不修改任何POM或部署文件。

本地直接运行test生命周期，未先执行package。实际命令为`mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests '-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC' test`；进程级MAVEN_OPTS为`-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC`，finally恢复原值。使用现有Maven3.8.6/JDK21，实际39项、0失败/错误/跳过，BUILD SUCCESS、退出0，未扫描evaluation。13个校验器子场景全部符合预期，退出0；actionlint（未启用shellcheck）退出0。真实Surefire报告另由正式校验器核验，路径未变。证据位于`smarttt-exam/target/oj-ci-entry-final/`。

先前本机Java8验证曾遇原生内存分配失败，之后受限内存package到达repackage时因Windows现有JAR占用而无法重命名；这些是本地限制，没有据此改POM、清理产物或结束其他进程。本轮没有验证package成功，修复后的GitHub Actions远程Java8编译打包、测试及上传仍需下一次Linux Runner运行确认。下面的旧记录保留为历史证据。

本轮P2修复验证：13个隔离子场景全部符合预期，夹具测试退出0；真实Maven oj-tests重新编译并执行39项，0失败/错误/跳过，退出0；新校验器接受真实39个唯一用例和完整基线，退出0。actionlint（未启用shellcheck）退出0。证据在`smarttt-exam/target/oj-ci-guard-fix/`。本轮真实回归使用现有本机JDK21，未声称重新完成Java8冷依赖验证或远程GitHub验证。没有更改上传步骤、触发、权限、部署文件或业务代码。

本地证据位于忽略目录`smarttt-exam/target/oj-ci-validation/`：

- PyYAML解析及触发条件、最小权限、无Secrets/部署引用的静态检查通过。
- actionlint 1.7.7（官方发行二进制，仅放在target）检查通过，退出0；本机没有shellcheck，actionlint以-shellcheck=运行，未声明shellcheck验证通过。
- 报告检查脚本取自工作流原文：正常39项报告退出0；零测试、失败、跳过、缺失报告夹具均退出1。夹具均在target。
- 使用现有Java 8u131及Maven3.8.6直接启动独立进程，保持JAVA_HOME/PATH不变；现有依赖缓存下，oj-tests实际39项通过、0失败/错误/跳过，退出0。只编译和测试，不启动Spring。
- 同一Java 8/Maven环境执行离线缓存下的exam package，BUILD SUCCESS，退出0；此打包跳过测试，测试结果来自上条独立test命令。
- 冷依赖验证最初因旧Java 8证书库导致PKIX错误；仅对验证进程指定现有JDK21证书库重试，未关闭TLS校验。未统一Central时历史传递仓库解析缓慢，该尝试中止（退出-1），不计作成功。
- 使用工作流相同的Central镜像配置及独立新建central-repository后，Java 8/Maven冷依赖package实际BUILD SUCCESS、退出0，耗时11分27秒；日志确认重新编译89个主源码文件及2个测试源码文件，未依赖旧class通过编译。没有使用原Maven依赖缓存或凭据。
- 随后使用同一个独立依赖目录和Central settings执行oj-tests（显式skipTests=false及maven.test.skip=false），实际39项、0失败、0错误、0跳过，BUILD SUCCESS、退出0，耗时34.051秒。证据为central-cold-build.log、central-tests.log、exits.txt。依赖冷下载验证成功不代表远程GitHub运行已通过。
- exits.txt保留了诊断失败：旧证书错误退出1，以及一次PowerShell未引用带点参数导致的命令解析错误退出1；加引号后测试正常通过。工作流使用Linux shell，不存在此PowerShell参数拆分问题。
- Java 8u131与较新Temurin 8、Windows与Ubuntu、Maven3.8.6与runner自带Maven的差异仍需远程确认；没有安装/切换系统环境。

GitHub事件派发、Fork PR可能要求的人工批准、仓库Actions策略、官方Action下载、Temurin配置、Linux文件系统及artifact展示均待远程验证。本轮没有Commit、Push、PR、Merge或部署，没有声称远程CI通过。

参考：[官方setup-java](https://github.com/actions/setup-java)、[Ubuntu 24.04 runner清单](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)、[Maven Java版本要求](https://maven.apache.org/docs/history.html)。

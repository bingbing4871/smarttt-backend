# OJ HTTP 第二阶段：安全关闭的骨架

基线d74afb62054bb86acb2c9d6790e91daa9e40a5d0，分支feature/oj-http-api。当前完成DTO、控制器骨架、条件初始化和隔离测试；生产身份认证与成员授权尚未接通，不能对真实学生开放。未改已验收存储实现。

## 实际认证核查及阻断

- exam/Utils/JwtTokenUtils.parseToken使用JWT.require(HMAC256).build().verify，验证签名和已有exp过期时间；未要求exp必须存在，也未约束issuer/audience。OjHttpTests使用自建测试密钥验证正常、错签名、过期和缺失exp行为，不调用真实签发接口。
- exam/controller/TestTokenController.getTestToken对应/api/exam/test/token：代码上未见AuthRequired、Profile或启用条件，可传userid/roleid/obsid并使用系统TokenSK签发。没有实际调用此入口、没有测试真实密钥有效性，也未修改它。只验签无法区分该测试入口签发的任意身份，不能作为OJ的可信身份来源。
- exam/Utils/AuthorizationAspect.beforeMethodWithAuthRequired的切点为@annotation(authRequired) && args(.., request)，依赖方法参数最后一项匹配；不能把注解当作对所有方法的保证。user分支仅打印信息。tokenThreadLocal.set后没有remove，且在后续权限校验之前设置，有线程复用残留风险。OJ不复用此ThreadLocal和切面。
- exam/dto/Token只有id/roleid/obsid/obsdeep，不能证明学生归属任意courseId/classroomId。admin登录StUsersServiceImpl.login返回数据库匹配的用户；LoginHomeController.switchStuCourse还会接收id改变obsid，不能把客户端课程上下文当作成员授权证明。
- exam/mapper/CmClassroomMapper.getCourseIdByClassroomId查询cm_classroom.courseId，CmClassroomStudentMapper.getStudentsByClassroomId查询cm_classroom_student中classroomId和userId，表结构提供潜在关系依据。但尚未确认登录id到成员userId的一致性、有效学期/状态、可撤销关系及可信调用边界；现有公开控制器不能直接替代完整授权检查。本轮没有查询真实数据库，也不编造规则。

阻断解除条件：确定可信登录令牌签发和验证边界（与任意身份测试Token隔离）；确认学生ID映射及课程/课堂有效成员关系；实现并独立审核OjHttpAccess生产适配器。接口必须对每次请求验证身份及成员关系，401未登录/非法身份、403无成员权限、503授权依赖不可用。不能从客户端studentId/roleId或旧ThreadLocal构造Owner。

## 安全默认值与生命周期

oj.http.enabled默认缺省/false，OjHttpController与OjHttpConfiguration均不注册，不创建存储目录。没有修改任何application配置。配置为true但缺少OjHttpAccess Bean时启动失败，不提供允许全部请求的默认实现。仅配置开关不能使接口可用。

未来授权适配器确认后，还需显式配置oj.http.storage-directory为绝对的专用目录。仓库Bean依赖OjHttpAccess先解析后才创建；关闭上下文调用close释放目录锁，测试验证重新打开。单实例独占规则、旧版记录处理和指纹逻辑保持不变。当前测试授权实现只存在测试类中，Test-Identity头不属于生产协议。

## HTTP契约（未开放）

既有WebConfig为@RestController统一添加/api；以下为正式组合路径。MockMvc standalone测试使用去掉全局/api前缀的局部路径，不代表启动整个业务应用。

| 方法 | 路径 | 输入与输出 |
| --- | --- | --- |
| POST | /api/exam/oj/submissions | JSON: requestId、courseId、classroomId、problemId、language、sourceCode；返回实际保存的View，HTTP200 |
| GET | /api/exam/oj/submissions/{id} | 必填courseId、classroomId；只查经授权当前学生的记录，无记录或其他学生记录为404 |
| GET | /api/exam/oj/submissions | 必填courseId、classroomId，可选problemId、offset=0、limit=20（上限100）；返回当前学生分页结果数组 |

View明确白名单：submissionId、requestId、courseId、classroomId、problemId、language、sourceCode、submittedAt、status、attemptNumber。没有Problem、隐藏输入/预期输出、内部hash/checksum、存储路径或judgeAttempts。不会直接序列化Submission。POST出现studentId/roleId等未知JSON字段报400；GET附带这些参数不会决定身份。

重复requestId同内容返回原保存记录，新key为主动重交；同key不同内容409。参数错误400，身份失败401，无权限403，不存在404，存储IOException映射503且不暴露异常消息。身份验证依赖未来适配器；当前没有真实JWT登录成功链路。状态返回持久化实际值，当前新提交WAITING_JUDGE；已有INTERRUPTED等状态不伪装成判题结果，不生成成绩或实际执行代码。

## 隔离验证与CI

oj-tests Profile新增OjHttpTests白名单，保留OjSubmissionTests；没有永久排除或扩大到旧@SpringBootTest。39项基线清单及校验器不改，校验器支持新增唯一用例，因此同一CI流程可验证新增HTTP用例。没有新增依赖或变更插件版本。

命令：mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests test。本机使用现有JDK21/Maven3.8.6，进程级MAVEN_OPTS和argLine限制内存，结束恢复原值。测试使用@TempDir、MockMvc standalone及最小AnnotationConfigApplicationContext，不启动Spring Boot、数据库、Redis、MinIO或真实OJ。

新增14个JUnit方法：提交/历史、匿名、正文身份伪造、其他学生详情与伪造查询身份、跨课程/课堂、幂等与冲突、响应字段白名单、分页/缺参、存储异常脱敏、默认关闭、启用缺授权实现失败关闭、本人详情及后续匿名隔离、仓库上下文关闭释放锁、旧JWT实际行为。JWT用例首次因测试Token缺少obsdeep触发旧解析器拆箱异常，补齐测试声明后重新执行；没有修改旧解析器或用测试绕过身份阻断。

2026-11-10最终实际结果：原39项存储测试全部保留，新增14项测试全部通过，总计53项，0失败/错误/跳过，Maven退出0；正式报告校验器验证53个唯一通过用例及39项基线完整，退出0；校验器13个夹具子场景全部符合预期，退出0。日志位于smarttt-exam/target/oj-http-evidence/release-check.log及exits.txt，实际Surefire XML位于exam的target/surefire-reports。

生产端到端身份、真实成员授权及远程Java8 CI未验证，不宣称HTTP阶段已具备上线条件。可以独立审核本轮安全关闭的骨架、DTO、初始化及隔离测试；不能据测试用授权实现通过而批准生产开放。外部网关是否限制既有TestTokenController尚未核实，本轮没有修改或调用它。

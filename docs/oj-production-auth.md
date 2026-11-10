# OJ身份与成员授权：策略已实现，生产接线仍阻断

2026-11-10，分支feature/oj-http-api，起点092ba3176785c23b5789ae3beba17b3c6a38e563。本轮没有注册生产OjHttpAccess Bean，没有修改oj.http.enabled默认值，不能开放OJ接口。实现可审核的严格JWT读取器和真实授权策略，数据与签发方契约未确认前保持关闭，不把测试数据当作生产授权。

## 登录身份完整映射：有实际代码依据

1. admin/controller/LoginController.login调用StUsersServiceImpl.login。StUsersMapper.getStUsersByLoginNameAndPwdAndCatelog按loginname、pwd、catelog查询st_users。学生分支catelog=1调用getStudentInfor，传入数据库返回的StUsers，而非客户端userid。
2. StUsersServiceImpl.getStudentInfor构造Token(stUsers.getId(), StuRoleID, null, -1, currentTerm)，admin/Utils/JwtTokenUtils.getToken将该id签入HMAC256 JWT，并设置两小时exp。可信学生登录路径的身份字段是JWT的id，对应st_users.id，不是sm_student.id、学号或roleid。
3. admin/mapper/StUsersMapper中的学生查询明确连接st_users.id=sm_student.usersid；CateLogEnum.STUDENT(1)及登录查询确认catelog=1为学生。
4. course/service/impl/CmClassroomStudentServiceImpl.importClassRoomStudentExcel用sm_student查到的student.getUsersid()设置课堂成员userId；course/resources/com/example/smartttcourse/mapper/ClassroomStudentMapper.xml连接sm_student.usersid=cm_classroom_student.userId。
5. exam/mapper/CmClassroomMapper.getCourseIdByClassroomId明确cm_classroom.id -> courseId；CmClassroomStudentMapper.getStudentsByClassroomId按classroomId查询成员userId。故OJ Owner.studentId采用st_users.id，成员应按该ID和classroomId检查，课堂courseId还必须等于请求courseId。请求课程/课堂仅是待授权目标，不是身份声明。

本轮未连接真实库。现有实体中的st_users.status、sm_student.status有效值、软删除及学期规则尚无充分代码契约，不能擅自把某数字解释为正常账号。OjAuthorizationData因此只定义只读端口，eligible必须由后续已核实的账号和学生状态规则计算，未知状态必须拒绝；未提供猜测性SQL实现或生产Bean。

## 现有令牌为何仍不能直接作为生产身份源

- exam与admin的JwtTokenUtils.parseToken使用固定HMAC算法验证签名和已有exp，但不要求exp存在，不约束issuer/audience。原AuthorizationAspect依赖@annotation && args(.., request)，ThreadLocal.set后没有remove；本轮新代码完全不读取该上下文。
- 原TestTokenController直接按userid/roleid/obsid请求参数签发共享密钥JWT，无代码级登录要求。现在移除@RestController、类级@RequestMapping和方法级@GetMapping。它是普通旧夹具工具类，无HTTP入口、无自动注册Bean；不是仅依赖dev/prod命名的条件限制。测试甚至显式注册该普通类，确认RequestMappingHandlerMapping中没有任何处理方法。
- 另一条阻断不能忽略：LoginController.getUserInformation（/login/user）无AuthRequired，调用StUsersServiceImpl.getTeaInfor；后者从请求id/roleid/obsid/obsdeep/termid构造Token，getAllUserInfor只查询用户和角色存在，没有验证本次请求曾通过密码登录。外部网关是否保护该接口未核实。不能因关闭TestTokenController就宣布整个旧签发链可信。
- 未擅自重写教师角色选择/登录流程，也未擅自轮换共享密钥或迁移所有模块JWT协议。历史共享HMAC令牌及其他旧接口的风险不因本轮修改而消失。

## 本轮实现与不接线的边界

- OjVerifiedIdentity：返回经过验证的st_users.id的端口，不接受客户端studentId、roleId或测试身份头。
- OjStrictJwtIdentity：可测试的独立RS256候选信任边界，构造时要求至少2048位可信RSA公钥和明确issuer/audience；仅接受Authorization: Bearer；强制id、exp、iat，检查签名、过期、签发时间及issuer/audience。失败统一401，无Token或异常细节。没有动态从客户端取公钥，没有默认密钥，没有引用CommonFunctions.TokenSK。
- 现有SmartTT登录不签发该RS256/issuer/audience契约，因此此读取器没有注册为生产Bean，也不能声称与现有登录已连通。这是防止旧任意身份签发入口绕过的候选隔离实现，不是已部署的新登录协议。
- OjAuthorizationData：每次请求读取用户/学生状态、课程存在性、课堂归属及当前成员计数；无缓存，无真实DB实现。未决状态规则不能用测试值代替。
- OjProductionAccess：实现OjHttpAccess的授权策略核心。取验证ID后，每次要求唯一对应学生、catelog=1、eligible=true、课程存在、课堂唯一且属于请求课程、该用户当前成员记录恰好一条。缺失/重复/错配均拒绝；成员撤销下次请求即拒绝；存储身份始终来自JWT。身份缺失401、拒绝403、身份源或数据源异常503；异常原因不透传，不使用ThreadLocal。
- 不修改OjHttpController、OjSubmissionService或存储实现。现有三类HTTP操作全部先调用同一OjHttpAccess；拒绝不会调用提交持久化。默认enabled=false，生产端口无Bean，启用HTTP但没有可信接线仍失败关闭。

解除生产阻断仍需：确认并收紧全部旧任意身份签发路径，选择可信签发方/密钥管理与登录集成方式，确认账号/学生状态和学期规则后实现只读数据适配器，经独立审核再决定显式启用。不能把本轮隔离测试通过等同于生产身份认证完成。

## 测试与CI

仅为oj-tests Profile增加OjProductionAccessTests，不改原53项及其基线。新增14个JUnit方法，内部包含多种边界：合法学生；无Token；错误算法/旧HMAC测试令牌/乱码；过期；缺少exp；错误issuer/audience、缺id/iat、未来iat；非学生及不合格状态；伪造客户端身份；跨课程/课堂；成员撤销；课程/课堂不存在；重复/错配数据；测试签发类无HTTP映射；拒绝及数据异常不产生提交且响应脱敏。测试使用新生成的测试RSA密钥与Mock只读数据端口，不调用真实签发或数据库。

首次回归发现保留@RequestMapping仍可能被Spring识别为处理器，随后移除全部HTTP映射并重跑。最终命令：`mvn -B -ntp -f smarttt-exam/pom.xml -Poj-tests '-DargLine=-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC' test`。本机现有Maven3.8.6/JDK21，MAVEN_OPTS仅进程级限制128/512MiB堆、256MiB元空间与SerialGC，结束恢复。

实际最终结果：原53项及新增14项全部通过，共67项，0失败/错误/跳过，退出0；正式校验器验证67个唯一通过用例及53项基线完整，退出0；原13个校验器子场景全部符合预期，退出0。证据位于smarttt-exam/target/oj-auth-evidence/verified-tests.log及exits.txt和Surefire XML。未验证远程Java8 CI或真实登录到OJ的端到端链路。

本轮可以独立审核授权核心、默认关闭边界与测试入口移除；不具备生产开放条件。没有Commit、Push、PR、Merge或部署，没有修改四个本地配置文件、数据库或部署设置。

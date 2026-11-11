# OJ 学生端与课堂题目接入（2026-11-11）

本轮为连续功能开发，未启动独立审核、Commit、Push或部署。后端分支 feature/oj-http-api，开发基线 440611c76a9057013ab1caff1077cc4f9bc6ba0b；前端复用现有 frontend/apps/main-project，不另建应用。

## 已实现的使用流程

1. 学生使用原 SmartTT 页面选择课堂；studentHomePage 把菜单 ID 作为 classroomId 传给课堂路由。依据是 StMenusMapper.getStudentCourseList 的 cm_classroom.id AS id，不把它猜成 courseId。课堂内导航保留 query。
2. 左侧“编程练习 OJ”打开 /homes/studentcourses/exam/oj。原系统门禁仅作导航控制，不能授予 OJ 权限。
3. 在已授权隔离环境中，学生以账号密码通过现有实验签发入口获得 RS256 Token；前端只保存在当前页面闭包。生产没有已确认 CredentialProof，入口仍只在 test classpath。
4. 前端用 Bearer 调用 GET /api/exam/oj/submissions/scope?classroomId=...。服务端验证 JWT，读取当前课堂所属课程，再执行完整学生、课程、课堂、成员授权，返回 courseId/classroomId。不能由菜单名、旧Token或前端studentId推测。
5. GET /api/exam/oj/submissions/problems?courseId=...&classroomId=... 返回当前课堂已发布的题目目录。每次请求重新授权，空目录也是合法状态；无身份401、拒绝403、依赖异常503。返回 problemId/version/statement/language 和四项资源限制，绝不直接序列化包含隐藏数据的Problem。
6. 选择题目、编辑C++17、提交；保持既有 POST、详情和分页历史契约。网络不确定时保留原请求；主动重交才生成新requestId。题目切换清草稿前确认，离开前提示。支持本人历史源码重新载入、按题目或课堂全部题目查询（包括已下架题目）。
7. 展示服务端真实状态，轮询有上限；没有执行器时只显示待判题。401/403清除身份、目录、草稿、历史及详情。

## 题目与业务数据

已有 tm_testquelib 包含 courseId/classroomId/content/title/codelang/codelangversion/status，TestQueLibMapper.getCourseQL 只读取课程题目概览；tm_testquelib_answer 保存选项/答案，pm_testpaper_questions 关联试卷。没有发现经过确认的编程隐藏测试、资源限制、不可变版本及教师发布授权契约，不能把 answer/analysis 当作学生公开字段，或猜测 status 即发布状态。

因此本轮没有建表、迁移或写入业务库。先用显式私有题目目录完成可独立验证的基础管理：创建题目、编辑升版、按课程/课堂发布、下架。可复用真实课程/课堂ID，但目录本身不授予学生访问权限。后续确认题库与教师规则后，可实现 OjProblemCatalog 适配器，复用提交和HTTP流程。

目录 schemaVersion=1，problems 数组每项为 courseId、classroomId、published(boolean) 和 problem。problem 使用已有快照字段：problemId/version/testVersion/statement/language/kwaIds、四项限制及testCases。详见 oj-catalog.example.json，它只有公开示例数据，绝不是生产题库。正式文件必须在私有目录，不能放在前端public、静态资源或上传到浏览器。

安全校验：严格UTF-8及UTF-16代理字符、单JSON根、重复键/未知字段/类型拒绝、整数范围、绑定唯一、同ID同版本内容一致。最多4MiB/200条绑定/每题100个测试；题面和单输入最多64KiB，预期输出不超过输出预算；CPU 1..10000ms、墙钟CPU..30000ms、内存16..512MiB、输出1..1024KiB。运行器还有原有独立限制，不因此放开缓存或执行权限。

## 本地基础题目管理

使用当前项目已有Jackson，不新增依赖。先复制示例到私有目录，编辑题面、隐藏测试和真实的目标ID。管理员操作不通过HTTP暴露，不以学生Token充当教师权限。

从仓库根准备classpath（仅exam入口，不扫描evaluation）：

```powershell
mvn -B -ntp -f smarttt-exam/pom.xml -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/oj-catalog-classpath.txt"
$deps = (Get-Content smarttt-exam/target/oj-catalog-classpath.txt -Raw).Trim()
$cp = "smarttt-exam/target/classes;$deps"
java -Xmx128m -cp $cp com.example.smartttexam.oj.FileOjProblemCatalog validate D:/private-oj/catalog-draft.json
java -Xmx128m -cp $cp com.example.smartttexam.oj.FileOjProblemCatalog install D:/private-oj/catalog-draft.json D:/private-oj/catalog.json
```

父目录由操作者预先创建并限制访问。本轮实际CLI校验复用了本地已存在的classpath，示例校验退出0；上述classpath生成命令是复现方法，不声称本轮重新运行。

install先校验，使用同目录临时文件、强制落盘、原子替换和管理文件锁；不支持原子移动则失败，不退化为覆盖。已有绑定不能静默删除，使用published=false下架。修改题面、限制或测试需要新的version；测试变更同时更新testVersion。同版本修改或校验失败不替换旧目录。私有目录仅允许受信管理员写入，不提供热更新：应用启动加载快照，安装完成后受控重启才生效。

显式启用HTTP时必须指定绝对路径 oj.http.catalog-file；缺失/无效则启动失败，不退回固定加法题。FixedOjProblemCatalog仅保留给隔离旧测试使用。生产默认开关仍关闭，示例配置也为false。

## 历史与幂等兼容

持久化仓库、校验和、提交格式和判题器未改。OjSubmissionService通过目录接口读取已授权范围的发布题目。首次保存完整题目快照；已有requestId先按本人范围读取原快照并复用仓库指纹校验。因此修订/下架不会使原请求产生新尝试或错误冲突，改变源码/题目仍冲突。新的requestId必须通过当前目录发布检查。本人历史不以当前发布状态过滤，避免下架造成历史不可查；跨学生详情仍404。

## 本轮统一验证

本机Java21.0.10/Maven3.8.6，编译目标Java8。本轮未运行新的远程CI，不能用本地结果声称Java8远程验证已完成。

- Maven oj-tests：120项，0失败/错误/跳过，退出0。原108项保留；新增12项OjCatalogTests覆盖目录范围与副本、公开DTO、每次授权、异常课堂、禁止提交、修订下架重启重试、严格解析、数字/未知字段、重复绑定、原子安装、空目录/资源上限、缺少私有目录关闭。原两项配置生命周期测试只补显式临时目录前提，断言保留。
- 正式报告校验器：120个唯一通过测试，退出0。原108行逐项保留；追加12项目录基线。13个夹具场景通过；67个扩展基线逐项删除均被拒绝，仍接受额外测试。没有改动CI工作流。
- 临时真实MySQL/HTTP：原8项通过，退出0。补充课堂解析、多题目录和成员撤销断言。证据 smarttt-exam/target/oj-real-e2e/mysql-d6a4e15a8f46；独占本轮mysqld已关闭，cleanup_errors为空。使用LAB_ACTIVE/lab-term实验规则，没有访问业务库。没有真实判题。
- 前端：31项Node检查通过；3个SFC及路由语法检查通过；隔离OJ入口Vite构建通过。浏览器UI夹具验证多题切换、草稿确认、主动重交、WA/AC展示、跨题历史、源码转义及403清除。夹具的判题结果是模拟值，不是执行C++。
- 旧全量主应用构建受1GiB堆内存限制失败，本轮仅完成隔离入口构建与改动SFC编译，不宣称整个主应用构建成功。也未宣称浏览器已直连真实MySQL后端完成全链路。

日志位于smarttt-exam/target/oj-student-tests.log（最终120项），初次配置夹具缺少新目录前提的失败保留为oj-student-tests-initial-failure.log，修复夹具后重跑通过；真实MySQL日志oj-student-mysql.log。前端证据位于target/oj-delivery/frontend-validation，不提交产物或秘密。

## 未确认事项与联调边界

- 生产凭据证明、密码存储/限速、旧/login/user兼容整改及资格/学期规则仍未验收。没有生产签发入口，不能把旧HMAC Token换签。前端账号密码表单只在显式隔离环境启用。
- 教师在线题目CRUD/发布权限未确认，当前只提供私有目录管理，不开放教师管理接口。
- 正式题库的编程类型/版本/隐藏测试映射尚待确认，不把实验MySQL当作实库验收。
- 没有专用Linux，真实go-judge仍跳过，不能宣布真实AC/WA/CE等已联调。
- 可以集中进行隔离环境统一联调；生产开放不具备条件。没有修改业务配置、原部署工作流、生产密钥或任何远程Git状态。


## 本轮开发文件清单（不含受保护配置）

### backend (21)

- `docs/oj-catalog.example.json`
- `docs/oj-ci.md`
- `docs/oj-isolated.properties.example`
- `docs/oj-student-integration.md`
- `scripts/ci/oj-baseline.txt`
- `scripts/ci/verify_oj_reports.py`
- `smarttt-exam/pom.xml`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/FileOjProblemCatalog.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/FixedOjProblemCatalog.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/OjProblemCatalog.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/OjSubmissionService.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjHttpAccess.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjHttpConfiguration.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjHttpController.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjHttpDtos.java`
- `smarttt-exam/src/main/java/com/example/smartttexam/oj/http/OjProductionAccess.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/OjBackendTests.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/OjCatalogTests.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/OjHttpTests.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/integration/OjIsolatedServer.java`
- `smarttt-exam/src/test/java/com/example/smartttexam/oj/integration/OjRealMysqlTests.java`

### frontend (13)

- `apps/main-project/src/api/oj.js`
- `apps/main-project/src/api/ojClient.mjs`
- `apps/main-project/src/router/index.js`
- `apps/main-project/src/views/page/oj/index.vue`
- `apps/main-project/src/views/page/oj/ojState.mjs`
- `apps/main-project/src/views/redrawPages/studentCoursePage.vue`
- `apps/main-project/src/views/redrawPages/studentHomePage.vue`
- `docs/oj-student.md`
- `scripts/oj/check.mjs`
- `scripts/oj/oj.test.mjs`
- `scripts/oj/preview-entry.mjs`
- `scripts/oj/preview.html`
- `scripts/oj/preview.mjs`

## 后续统一真实联调（2026-11-11）

原主应用登录页、课堂路由和OJ页面已在本轮独立Java/MySQL实验实例上实际连接，未使用浏览器Mock回退。版本、下架、幂等、成员撤销、超时重试和重启恢复均有真实请求及页面证据。完整运行环境、复现步骤、集中测试及正式构建结果见 [oj-unified-integration.md](oj-unified-integration.md)。此前的浏览器夹具结果与本轮真实链路分开记录；生产登录、正式业务库资格规则及真实go-judge仍未验收。

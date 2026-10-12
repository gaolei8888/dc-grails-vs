# CLAUDE.md

本文件为在此仓库工作的 Claude Code 提供指引。

## 这是什么

`dc-grails-vs` —— 一个 VSCode 扩展,给 **Grails/Groovy 项目**提供命令面板集成。

- `package.json`:`name: "grails-gradle-extension"`,`publisher: "gaolei8888"`,version 0.2.0(pre-release)
- `extension.js`:1904 行 JS(2026-10-12 并入 origin/dev 之后数),注册 **206 条命令**(合并后逐 id 核对;`package.json` 声明 205 条):
  - 11 条显式命令 —— `grails.runApp` / `stopApp` / `debug` / `debugTests` / `stopDebug` /
    `attach` / `explainException` / `showExceptionContext` / `qa` / `openApp` / `showOutput`
  - `genericCommands[]` 76 条 —— 注册成 `grails.<cmd>`(`create-*`、`generate-*`、`dbm-*`、`s2-*`……),
    走 `grailsw`
  - `gradleTasks[]` 119 条 —— 注册成 `gradle.<task>`(`bootRun`、`build`、`test`、`idea`……),
    走 `gradlew`
  - `package.json` 声明 205 条,零缺口零重复;唯一差额是 **`grails.openApp` 注册了但没声明**
    —— 它只作为 status bar 的点击目标,故意不进命令面板(2026-10-12 合并后逐 id 核对)
- `snippets/`:Groovy/Grails 代码片段
- **已经在 Marketplace 上**:最新已发布的 pre-release 是 0.1.29;0.1.30 在 origin/dev 上,未发布;本分支的 0.2.0 待发(见「开发」)

**这是一个通用工具,不绑定任何具体业务项目。** 需要一个 Grails 应用做靶子时,自建空白
Grails 应用(7 或 8),不要借用别的业务仓库。**目标是同时支持 Grails 7 与 Grails 8**
(现状见下「Grails 7 / 8 支持」)。

调试靶子已经建好、在**仓库外**(别放进来,会进 git 和 vsix):
`~/Documents/work/dc/dc-grails-vs-target/dbgtarget` —— forge 生成的空白 Grails 7.2.3 rest_api,
加了一个类级 `@Transactional` 的 service(方法体里含闭包)和一个调它的 controller。
`GET /example` 触发。起法:`./gradlew bootRun --debug-jvm`(JDWP 5005,suspend=y)。
要重建:`curl -sL -o app.zip "https://latest.grails.org/create/rest_api/com.example.dbgtarget?javaVersion=JDK_17"`
—— 注意 forge 已迁到 Apache,`start.grails.org` 只剩跳转页,`javaVersion` 要写 `JDK_17` 不是 `17`。

第二个靶子(Grails 8,同样在**仓库外**):
`~/Documents/work/dc/dc-grails-vs-target/dbgtarget8` —— 空白 Grails 8.0.0 rest_api,形状同
`dbgtarget`,另加 service 方法 `nestedMethod`、controller action `nested()` 和 `/nested` 的
URL mapping。`GET /example`、`GET /nested` 触发。起法同样是 `./gradlew bootRun --debug-jvm`
(JDWP 5005,suspend=y)。要重建:
`curl -sL -o app.zip "https://latest.grails.org/create/rest_api/com.example.dbgtarget?javaVersion=JDK_21"`
—— 这里要写 `JDK_21` 不是 `21`。

## 当前状态(2026-09-06)

**2026-10-12 的版本线**:本分支(`merge-dev`)把 origin/dev(0.1.14 – 0.1.30:条件 / logpoint / 数据断点、GSP、QA、异常解释)与本地 0.2.0 线(Grails 7/8、launch 配置、JDK 发现、`groovy-dap` 改名……)合在一起,见下「合并 origin/dev(2026-10-12)」。下面这一节是 origin/dev 写的,版本号按它当时的情况。

**Groovy 调试适配器已经做出来并发布**,最新已提交 Marketplace 的版本是 `0.1.19`(pre-release,
publisher `gaolei8888`)。主线任务从「让断点能绑上」变成「继续把这个调试器做好用」。

**`0.1.19` 已于 2026-09-06 按用户授权发布，vsce 返回 Published；已通过本地VSIX安装到日常编辑器，CLI确认版本0.1.19；当前窗口需Reload Window加载。** 0.1.18 补真实编辑器回归、GSP 高亮和 AI 解释异常；0.1.19 加 Grails QA 引擎、UI 和认证选项。
发布后立即查询公共版本索引仍为0.1.17，正在等待Marketplace索引/校验传播；不要因暂未显示而重复发布同一版本。
最新验证与实现见设计文档 §7.14、`docs/testing.md`。开头的 0.0.1/394 行是历史背景。

### Grails QA 本地进展（0.1.19）

2026-09-08：0.1.30 已本地打包安装，未发布。新增 Generate QA cases with AI → VS Code 模型选择 → ai-draft.json → Use AI draft → Run QA。lib/qa-ai.js 发送有界 controller/GSP/Domain 源码（不读配置/日志/DB），模拟模型测试验证流式解析、取消、无 provider；真实模型生成质量尚未实测。qa/browser-cases.js 校验最多 40 case／30 step，只执行 click/fill/select/check/visible/text/url，不执行模型 JS。保存 browserCases 时保留 Domain 基线及原页面，备份 plan.json.before-ai.json。面板默认可见本机浏览器，identitySession.run 透传 headless。AI case 限同 origin 导航与写请求；逐步输出、失败步骤和截图。34 Node通过、lint零error/旧6warning；真实VSCode AI draft suite通过（无provider提示→应用草稿→22 Domain+15UI）；安装目录引擎可见Edge smoke验证操作成功、错误断言失败、外站请求0。最终150文件/3.22MB，CLI确认0.1.30，需Reload。当前是源码生成+确定性重放，尚无截图驱动的实时AI探索。

2026-09-08：按用户授权发布 0.1.29 pre-release，vsce 返回 Published gaolei8888.grails-gradle-extension v0.1.29。发布前 31 项 Node 测试通过，lint 零 error／原有 6 warning，构建成功；最终 VSIX 148 文件／3.22 MB，解包到系统 temp 后独立浏览器 smoke 通过。相同 VSIX 已强制安装本地，窗口需 Reload。Marketplace 索引可能稍后刷新，不要重复发布同版本。dc_classeditor 的 Windows test Docker 配置修改位于业务仓库，不在扩展包内。

0.1.29已本地打包安装（未发布）：用户说tenant下user为空，查看实际QA窗口发现Find test users输入了`test`；搜索结果被筛掉，且Output占据下半屏，选择区不容易看见。改用户选择框在可选搜索前，增加selector旁状态/计数，区分无匹配与无用户，Clear filter按钮、Enter搜索，重载/切tenant清空旧filter，加载后滚到tenant/user选择区。真实VSCode tenant suite通过：无匹配提示→Clear恢复用户→切tenant清filter及已选user→空tenant→Cancel释放lock。lint零error/旧6warning；CLI确认0.1.29。用户需Reload Window，或旧窗口清空Find test users后点击Search即可取消筛选。

0.1.28（2026-09-07）已打包安装，未发布：用户点击Load没有列表，实际重跑发现HibernateException。其CECurrentTenantResolver用裸DetachedCriteria枚举，旧fixture内包withTransaction掩盖问题；改fixture裸查询复现后，在Tenants.withoutId的零参闭包内用datastore.transactionManager +只读TransactionTemplate枚举，已修。安装引擎实际读取dc_classeditor test环境：2tenant，每个2个用户，只记录数量，没有选择身份/提交UI表单。发现项目test配置仍覆盖chainMap；仅在QA initializer对springSecurityFilterChainRegistrationBean设enabled=false，保持所有安全beans及capability请求保护，fixture断言credentials启用/Skip禁用。列表请求禁止重定向，非JSON返回明确报错，discovery异常写QA输出。已安装代码JDK25验证：裸resolver多租户14UI，credentials/bypass各14UI，capability/跨origin/请求隔离通过；31Node通过，lint旧6warning；安装runtime与源码逐文件一致。需Reload Window后重新Load。

0.1.27（2026-09-07）已本地打包安装，未发布：Skip 面板改为 Load test users / tenants → 如检测到多租户先选tenant → 选该tenant的user。运行时从HibernateDatastore检测multiTenancyMode，通过AllTenantsResolver或DATABASE命名连接列tenant，通过Spring Security配置的GORM用户类只投影用户名（搜索/100条分页，过滤已映射的禁用/锁定/过期标记）。空库/不支持的provider明确提示，不创建/猜身份。选择进程保持并持有QA lock，Run复用同一进程；Cancel/关闭/切换target或auth/完成时停止并释放。加载用户前绑定RequestContextHolder和Tenants.withId；resolver有attributeName时同步设置并恢复session属性，适配CECurrentTenantResolver这类自定义session名称。不硬编码school.name，报告记录tenant。
安装目录引擎+用户JDK25已验证：single/multi fixture各14 UI；租户内同名用户、空tenant、拒绝不存在tenant、分页106个可用用户、过滤disabled/locked、同进程复用、锁保护、currentUser及session租户。真实VSCode：Buttons+Skip 2项通过（22 Domain+14UI），Tenant choices 1项通过（切tenant清空用户/空tenant/Cancel释放lock）。31Node通过，lint零error/旧6warning；身份capability/请求隔离/跨origin不传user和tenant header复验通过。最终包148文件/3.21MB，需Reload Window；业务项目尚未重跑。
GORM7.2.3坑：Tenants.withoutId没有(Class,Closure)重载，需传datastore实例；枚举tenant的闭包必须显式零参数 `{ -> ... }`，隐式it会触发withSession导致DataAccessResourceFailureException。身份枚举仅用于本地Skip模式；现有远程服务器和外部非GORM用户provider不自动读取。CLI原有手动用户名路径保留。

0.1.26（2026-09-07）修复 Skip authentication 的 currentUser 缺失：UI 必填已有测试用户名，无需密码；只在本次 bootRun 的独立 sourceSet/classpath 注入 initializer/filter，用项目 userDetailsService 加载 principal/既有角色，每请求设置并恢复 SecurityContext。loopback/test + 内存随机 capability 限定 QA 浏览器，不写入配置/报告，不跨 origin 转发。不创建用户、不添加角色；特殊 session/SSO/WebSocket 仍需项目适配。历史 0.1.25 的“仅 HTTP bypass 不提供身份”已被此版本取代。
已安装 0.1.26 后用用户 JAVA_HOME/JDK25 验证：credentials/bypass 各14项 UI，通过显式 action 检查真实 QaAccount/currentUser 和 ROLE_QA（不要使用旧 beforeInterceptor 闭包，Grails7 此路径不执行）；REST beans 和项目test数据库仍保留。identity guards 检查无/错误capability拒绝、无密码身份、请求隔离及跨origin重定向不泄露；安装包面板验证用户名/密码显示及 useCase 安全渲染。每项 Domain/UI 报告加入 useCase 描述，旧plan不重写。用户业务项目尚未重跑，仍需用户提供已有测试用户名。
最终回归：31 Node 单测通过，lint零error/旧6warning；新fixture冻结约束mutation被捕获，恢复后22 Domain/H2通过，显式临时H2模式14 UI通过。最终VSIX 147文件/3.21MB已安装，CLI确认0.1.26，安装目录qa/lib/media逐文件与已验证源码一致。当前窗口需Reload Window。

2026-09-06 后续修复：0.1.20 已本地打包并安装（未发布）。用户 QA 日志报 `'gradlew.bat' is not recognized`，wrapper 文件存在。用 `NoDefaultCurrentDirectoryInExePath=1` 在空白fixture复现，改显式 `.\gradlew.bat` 后通过；26项单测通过，安装目录CLI在相同限制下实际完成Domain inventory（Java17）。当前用户窗口需要Reload Window加载新版本。

后续0.1.21已本地打包安装（未发布）：用户项目inventory报Hibernate DialectResolutionInfo，底层是`Failed to load driver class org.h2.Driver`。QA init在grailsQaTest和本次bootRun缺少H2时用独立configuration补充H2 2.4.240，不改应用依赖/build文件；已有H2则保留。空白fixture移除H2直接依赖，使用安装目录引擎验证。
0.1.21安装后验证完成：继承用户JAVA_HOME/JDK25，缺H2的独立fixture成功导出inventory，mutation被捕获，恢复后20项Domain/H2通过，14项bypass UI通过且实际JDBC连接确认临时H2；26项Node测试通过。用户业务项目尚需Reload Window后重试。

0.1.22已本地打包安装（未发布）：用户实际报告Domain8全部通过、UI0，原因是扫描器把`excluded-datasources`误判成多个datasources。现匹配完整配置键并忽略Groovy注释，运行时刷新旧计划中的datasource能力gap（不改冻结约束），输出Domain通过/失败/跳过及实际执行错误。安装后只读扫描用户项目确认multipleDataSources=false；28项Node测试通过（包含误判、真实声明和旧gap更新回归），lint零error/旧6warning。用户需Reload Window加载0.1.22；实际应用UI尚未通过，不要声称全业务QA通过。

0.1.23已本地打包安装（未发布）：用户run `2026-09-06T23-14-02-599Z-run-130915` Domain通过；真实应用启动Liquibase执行`DEFAULT b'0'`被H2拒绝（Column B not found），同时STOMP localhost:61613拒绝连接。不能声称再修扩展即可全业务UI通过，也不要擅自禁迁移/外部服务。已询问用户可用QA应用URL（尚待回复）。修复引擎遇到`Application run failed`即中止本次进程、写当前report，不再等失败应用的后台线程退出；明确H2迁移失败提示。安装后运行30项Node测试通过，包括故意保持存活的失败进程会迅速停止、生成报告并释放lock。实际UI下一步需要准备可运行QA服务/依赖，用户默认JDK25保持不变。

0.1.24已本地打包安装（未发布）：用户纠正“每个Grails项目自己有test database”，并要求修复QA输出红色ESC。**UI启动现默认project test环境/数据库，不能强行替换H2**；project模式不注入datasource/driver/dbCreate，保留应用迁移/服务配置，多datasource也可按项目配置启动。H2仅显式选项，Domain独立规则/持久化测试仍H2，不能混淆这两层。CLI增加--database=project|h2；SecretStorage区分project-test-local-qa与isolated-local-qa。qa/process使用stripVTControlCharacters过滤颜色/链接控制码并保留脱敏。安装后继承用户JDK25，独立fixture真实JDBC确认project test URL且无dataSource.url覆盖，credentials和bypass各14项UI通过，日志无ESC；31项Node测试通过。用户业务UI尚未验，不要声称全业务通过。

0.1.25已本地打包安装（未发布）：用户bypass启动缺tokenStorageService，dc_classeditor resources里的LinkStompAuthInterceptor强依赖REST服务bean。**废弃active=false的bypass**：只为本次loopback QA设置active=true + filterChain.chainMap=[[pattern:'/**',filters:'none']]，保留安全插件及REST beans，绕过HTTP过滤器。独立fixture加入真实grails-spring-security-rest 7.0.1并强制ref('tokenStorageService')，安装后JDK25 credentials/bypass各14UI通过，验证tokenStorageService注入和项目test数据库。仅fixture使用固定公开JWT测试key；未改用户业务应用或其密钥。HTTP bypass不伪造登录用户，不绕过WebSocket/业务方法自己的权限检查。31项单测通过，错误提示现在显示missing Spring bean名称。

**用户明确要求：使用其配置的JAVA_HOME（当前`C:\Users\gaole\java\jdk-25.0.4.1`），不要自行切换到其他JDK，包括历史测试靶子的JDK17。即使仅临时设置也不要擅自选择。先安装修复版，然后验证已安装代码。** 0.1.20安装目录CLI在用户JDK25上inventory已通过。

- `qa/` 独立 CLI/引擎；`lib/qa-panel.js`、`media/qa.*` 是 `grails.qa` / sidebar Run QA 按钮。
- 运行时导出约束并冻结 `.grails-qa/plan.json`；正常 run 不改基线。独立 HibernateDatastore + H2 真正 flush/clear/reload 和 unique，事务回滚。未知规则/关联/未配置 UI 断言是 gap，不算通过。
- 浏览器真实填表/提交/校验字段错误/截图。登录使用项目+目标 origin 隔离的 SecretStorage；密码不进入生成文件或报告。没有接 AI 自动猜业务期望。
- bypass 仅工具启动的 loopback/test/H2 进程：独立 `qa-overrides.groovy`，通过 `grails.config.locations` 加载 boolean false。**不能直接 `-Dgrails.plugin.springsecurity.active=false`：插件拿到字符串时 Groovy truth 仍为 true，实测安全过滤器仍启用。** 不修改应用配置，也不对已有服务器 bypass。
- QA 的 Domain inventory 使用 mappingContext 的 validator.constrainedProperties；直接 `Domain.constrainedProperties` 需要 GrailsApplication，会失败。Grails inList 错误码是 `not.inList`。
- 新空白靶子 `.vscode-test/qa-fixture` 由 `scripts/prepare-qa-fixture.js` 生成，只借用独立 dapspike 的构建工具链。Grails 7.2.3 + Spring Security 7.2.3 + Java17 + Edge 已验。
- 真实编辑器 QA suite 5项通过：按钮/模式限制、账号登录20 Domain+14 UI、SecretStorage复用、bypass、错误密码失败。改变quantity最小值的mutation被旧计划捕获，恢复后20 Domain通过。
- 最终25项Node测试通过，lint零error（旧6warning）；fixture实际JDBC连接确认临时H2，bypass 14项UI再次通过。`grails-gradle-extension-0.1.19.vsix` 144文件/3.2MB；解包到系统temp后无node_modules，随包driver仍能启动Edge并完成页面断言+截图。该安装包已发布并安装日常profile，CLI确认0.1.19。
- `dist/qa-browser` 通过 `build:qa` 暂存 Playwright driver，VSIX不带浏览器二进制。安装Edge/Chrome或配置路径。说明 `docs/qa.md`，测试 `docs/testing.md`。
- 本地启动仍执行应用bootstrap和外部集成；多datasource禁自动启动，使用预配置QA服务。当前支持范围是Grails7/Hibernate，不声称全项目业务QA或所有版本兼容。

### 设计文档(权威,先读它)

```
docs/2026-08-29-vscode-groovy-debug-adapter.md
```

根因分析、算法、每一次实测的数据都在里面。**§7.x 是按时间排的实测记录**,改任何东西之前
先看对应那一节 —— 里面记着好几个我自己踩过两次的坑。

### 一句话背景

VSCode 里 `.groovy` 断点永远是空心的 —— `vscode-java-debug` 把「文件 URI → 全限定类名」
写死在 JDT 上,而 JDT 不索引 `.groovy`。没有任何配置能改。所以自己写了一个 debug adapter
(类型 `groovy-dap`;`grails.debug.adapter: "java"` 才走旧的 vscode-java-debug 路,并继承这个限制)。

### 核心思路

attach 模式下不需要解析 Groovy:`ReferenceType.locationsOfLine(n)` 就是「这个类是否拥有
该行」的权威答案,闭包合成类的 `sourceName()` 与外层同名 —— 所以闭包问题自动消解。详见 §4。

### 已经做出来的(括号是发布的版本)

| 功能 | 版本 | 验证程度 |
|---|---|---|
| `.groovy` 断点、闭包、签名行下滑、命中去重 | 0.1.0 | harness + 编辑器 |
| 199 条命令走对 wrapper(带连字符的走 `grailsw`) | 0.1.0 | 真实项目实测 |
| 异常断点、Debug Tests、GORM trait 字段过滤 | 0.1.2 | harness |
| devtools 重启后丢弃陈旧请求 | 0.1.6 | harness |
| Grails scope(`params`/`request`/`response`/`session`)、map 展开 | 0.1.7 | harness |
| hover / watch(只读路径求值) | 0.1.8 | harness |
| step over 改用断点拼,不走 JDI stepping | 0.1.9 | harness |
| logpoint、命中次数 | 0.1.10 | harness |
| step into 进 `@Transactional`;**行断点全装 location** | 0.1.11 | harness |
| 条件断点(**只做比较**) | 0.1.11 | harness |
| setVariable | 0.1.12 | harness |
| step assist 按线程分开 | 0.1.13 | harness |
| **`.gsp` 断点** | 0.1.14 | harness |
| GSP 单步;**stepIn 的多包过滤器缺陷**;Marketplace 图标 | 0.1.15 | harness |
| war 里的 GSP 断点(按路径尾部匹配) | 0.1.16 | harness |
| **数据断点**;Attach to Running App | 0.1.17 / 0.1.18 修复 | harness + 真实编辑器 |
| **GSP 高亮** | 0.1.18 本地 | TextMate 测试 + 编辑器截图 |
| **AI 解释异常 / 查看异常上下文** | 0.1.18 本地 | 真实异常/无模型流程 + 模拟 provider；在线生成未验 |
| Grails 8 支持、launch 配置 `Grails: Debug App`、JDK 自动发现、status bar 只在 Grails 工作区显示 | 0.2.0(待发) | 脚本驱动 + 真实窗口(见「Grails 7 / 8 支持」「已修(2026-10-11)」) |
| 调试类型改名 `groovy-dap`(**破坏性**:`launch.json` 必须写 `"type": "groovy-dap"`) | 0.2.0(待发) | 真实窗口(扩展能激活);合并时 `lib/explain-exception.js` 的 tracker 也跟着改 |
| `continued` 事件先于 resume(其它线程不再卡在 PAUSED)、冷 JVM 下 stepIn 的 juli 排除、逐变量读局部变量 | 0.2.0(待发) | `verifyProtocol` + 真实窗口(`continued`);juli:7 / 8 冷热 trace(合并前) |

### 最容易再踩一次的几个坑

1. **JDI 一个请求上的多个 class filter 是「与」不是「或」**(§7.11)。要表达「或」就建多个
   请求。这条曾让 stepIn 在任何有两个顶层包的项目里静默失效,而且是「单包靶子恰好掩盖了它」。
2. **Groovy 一行编译两份、共用同一行号,跑的往往是第二份**(§7.2 ③)。断点必须**全装**,
   去重放在命中侧。这条 §7.2 明明记过,我在 step assist 里又踩了一次(§7.6)。
3. **`invokeMethod` 会让线程先跑起来,之前拿到的 `StackFrame` 全部作废**(§7.8)。
   顺序必须是「先造值,再重新取帧,最后写」。
4. **GSP 没有 SMAP**,行号映射在 `GroovyPageMetaInfo.lineNumbers`(`int[]`,**0 基**:
   `lineNumbers[G-1]` 是生成行 G 的页面行);类名就是页面的绝对路径,部署后会变(§7.9–§7.12)。
5. **一次只跑一个 harness case** —— `runcase.sh` 收尾会杀掉所有 `dapspike` 进程。

### Grails 7 / 8 支持(2026-10-11 脚本驱动实测;主要流程另在真实 VSCode 窗口里抽查过,见本节末尾)

这些改动的提交历史:`git log 37b3c5d^..2cb7cee`。

**注意**:本节的实测数据都是在并入 origin/dev **之前**、用本地 adapter(step over / step into 走 JDI stepping)做的。并入之后 step over / step into 改成 origin/dev 的「断点拼出来」的做法,合并后的重跑与差异见下「合并 origin/dev(2026-10-12)」。

| | Grails 7 | Grails 8 |
|---|---|---|
| 实测版本 | 7.2.3(Groovy 4.0.33) | 8.0.0 GA(2026-10-04 发布;Groovy 5.1.3、Gradle 9.8.0、Spring Boot 4.1.1) |
| 应用所需 Java | 17+ | **21 起**(最低) |
| 靶子 | `dbgtarget` | `dbgtarget8` |

**extension 本身不需要为 8 改任何代码。** adapter(`server/`,Java 17 构建)跑在 JDK 21 上没问题;
要注意的只是 **Grails 8 的应用必须用 Java 21 起**。

8 上已验(与 7 上一致):

- `bootRun --debug-jvm` 仍打印 `Listening for transport dt_socket at address: <port>`,端口解析照旧。
- `grailsw` 仍会生成,`grailsw create-controller` 可用;`genericCommands[]` 的 76 条与 7 完全一致
  (`grailsw help`:7 上 37 条、8 上 38 条,多出 `config-report`)。
- T0 验收 1–5、`Reference` 解包、签名行下滑、命中去重(8 上每个请求停 8 次;7 上记录的是 4 次,
  两个靶子形状不同,**未**核对是否可直接比)、`next` 单步,全部通过(Grails 8 / Groovy 5.1.3)。
- 「step over 回 controller 后跳过剩余代码」那条缺陷**再次未复现**(见 T1 余项第 2 条)。

8 上的差异(**外观,非失败**):

- 闭包合成类编号会变(`_closure2` → `_closure3`)—— 算法不认类名,不受影响。
- `@Transactional` 回调帧在调用栈里显示 **行 0**。推测纯外观,**未验证**。
- Grails 8 / Gradle 9 删掉了 4 个内置 help 任务:`components`、`dependentComponents`、`model`、
  `dependencyManagement`。它们**仍在 `gradleTasks[]` 里**(已知,尚未移除)。

`stepIn` / `stepOut` / step filter 实测(**并入前的本地 adapter**,下列 `@Transactional` 不进入、i=1,2,3、行中段等结论已被合并后的行为取代,
已接受的差异见「合并 origin/dev(2026-10-12)」;脚本驱动 DAP 客户端,JDK 21.0.10,2026-10-11;
Grails 7.2.3 与 8.0.0 在每个用例里的 file:line 序列**完全一致**,只有 bci 差约 3–5):

- `stepIn` 从 controller 进 `@NotTransactional` / 普通 service 方法:落在 service 源码(svc:16),7、8 相同。
- `stepIn` 到 `@Transactional` 调用上**不进入**(已知限制,**已确认**,7、8 相同):调用返回后在
  同一行再停一次,然后继续。
- `stepIn` 在 `.each` 行进入闭包体(`…doCall:23`,i=1,2,3)以及嵌套闭包(closure4:31、closure5:32),
  结果正确 —— 冷 JVM(全新 JVM 的第一个请求)与热 JVM 都对;冷 JVM 曾跳过闭包,已修,见「已修(2026-10-11)」。
- `stepOut` 在 7、8 上都可用(**已验**,此前只经过编译):闭包 → 外层方法行(行中段,`each` 返回之后;
  剩余迭代会跑完、不停);嵌套闭包 → 外层方法 → controller;service 方法 → controller 行。
- `next` ×5 / ×10 没有重复停。`stepIn` 会在同一行、不同 bci 上**连续重复停**(行中段再停;
  一次运行 3 次,×16 的一次运行 4 次);没有 line+bci+depth+locals 全相同的重复。
- step filter:`java.*`、`jdk.*`、`groovy.*`、`org.codehaus.*`、`org.springframework.*`、核心
  `org.grails.*` 里**没有**任何 stop / step 事件(JDI 层 exclude 生效)。排除表之外的帧
  (`org.jboss.logging` 152 个事件,是一次完整 driver 运行(9–11 个场景)的**总数**,不是每个请求;
  `io.micrometer` 7 上 35、8 上 216,原因未知,**且两边不是同口径**:7 那次 `/nested` 返回 404,
  只步进了 9 个场景;8 那次跑满 11 个,是在同一个 JVM 上的第二遍 —— 对比被混淆了)
  会被步进经过、再由 `isOutsideProject` 跳过 —— 代价是多几次往返,不影响正确性。

**还没验证的**(别当成已支持):

1. **VSCode UI**:用户 2026-10-11 在**真实窗口**里见过:status bar 的 Grails / Debug / Stop 按钮、
   经命令 / status bar 启动 Debug、断点命中且为实心、Locals / Watch 的值、Watch 里求变量路径、
   改值(「改值可以了」;发的是 `setVariable` 还是 `setExpression` 不知道;那是合并前的本地实现,合并后 `setExpression` 已不再广告)、类重载后断点重新绑定。
   **另一个 Claude 会话(dc-octo)2026-10-12 驱动用户的真实 VSCode**(装的是 HEAD 8c8e753 构建的 vsix,
   靶子 `dbgtarget8` = Grails 8.0.0,JDK 21.0.10,有截图,用户要求的测试)又验了:launch 路径、JDK 发现
   (`env -i` 启动)、双启动保护、工具栏单步,见「已修(2026-10-11)」第 8、9 条与下面的「真实窗口单步」。
   同日又用 dc-octo(dco)在装了「工作区当前树」构建的 vsix 的真实窗口里验了第 11、12 条的两个修复(见「已修
   (2026-10-11)」)。
   **仍未验**:F5、F10/F11 按键(测试者按不了)、hover、Windows / Linux 路径;暂停时点 Stop(已知限制,见「未修」);
   单步之后再 Continue 的路径。
2. Micronaut 变体(文档说要 JDK 25)。
3. 依赖插件的命令:`dbm-*`、`s2-*`、`asset*`。

副作用:`grailsw` 会把各版本的 CLI 缓存到 `~/.grails/wrapper/`。

### 还没做 / 已知限制

- **本轮已补编辑器 UI 回归**：Attach 输入框、GSP gutter 断点/单步、变量面板右键数据断点、
  真实异常上下文与无模型流程。其余功能不因此视为全部完成 UI 回归。
- **macOS / Linux 的进程管理分支从没执行过**(`lsof` / `ps` / `SIGKILL`)。owner 说他来验。
- **表达式求值**(条件断点的完整形态、Debug Console 的 repl)仍属 T2 —— 需要在目标 VM 内
  编译执行 Groovy。目前只做比较与路径读取。
- **预编译 GSP 不存在**:Grails 7.2.3 没有 `compileGsp` 任务,war 里是页面源码(§7.11)。
- **Watch 里改值(`setExpression`)延后**:合并 origin/dev 时有意没有带入本地的实现(能力不广告);Variables 面板的 `setVariable` 可用。
- **AI 在线生成尚未实测**：本轮隔离 VS Code 没有可用模型/账号，不能将模拟 provider 算成在线验证。

### AI 功能状态

owner 问过。结论是**不要再做一个聊天框**(那是 Copilot 的活),值得做的是把调试器手里的
活数据当上下文:

1. **解释异常** —— 异常断点命中时,把异常类型、message、过滤掉框架帧之后的栈、相关源码行
   一起给模型。**已实现**，只由用户命令触发；模型选择后才发送，上下文可先本地查看。
2. **解释这次停顿** —— 当前帧 + 局部变量 + Grails scope。
3. **自然语言 → 断点条件 / logpoint**,并且能当场用现有的比较式校验。

`engines.vscode` 已提高到 `^1.90`。使用 `vscode.lm.selectChatModels({})`，不绑定 Copilot
或特定模型。没有 provider 时打开本地上下文并提示配置；授权由 VS Code 处理。
最多 12 个工作区栈帧、3 个文件附近源码，不采集局部变量/request/session，不自动发请求。
继续/单步/结束调试会取消请求；读取期间 stop 变化会丢弃旧上下文。

### T1 进行中:`server/` 已建并跑通(2026-08-30)

`server/` = Java 17、**零第三方依赖**、产物 `dist/groovy-dap.jar` 101334 字节(2026-10-12 并入 origin/dev 之后重测;合并前是 76649)。20 个 DAP 命令
(19 个各有 handler,`disconnect` / `terminate` 共用一支;含 `evaluate`、`setVariable`;**不含** `setExpression`:Watch 里改值延后)和 6 类事件已实现;断点绑定、签名行下滑、闭包补装、命中去重、`Reference` 解包、栈帧回映射
都在空白 Grails 7.2.3 应用上用脚本驱动的 DAP 客户端验过。详见文档 **§7.3**。

**T1 余项**(文档 §7.3 有完整列表与实测数据):

1. ~~编辑器里一次都没跑过~~ —— **已在 Extension Development Host 实跑通过**(2026-08-30,
   见文档 §7.4):断点能下并转实心、闭包类加载后自动补装、变量面板正确、单请求总停顿 4 次
   (去重生效)、多线程并发命中互不干扰。JVM 是 JDK 25。那一轮暴露并修掉了 4 个 harness
   不可能发现的缺陷(裸 `gradlew.bat`、变量面板静态字段、toolchain、Stop 不杀应用 JVM)。
   仍未验:编辑器里的单步手感(没按过 F10/F11)。
2. **无法复现,成因不明**:「从 service 方法末行 step over 回到 controller 后再按一次会跳过
   剩余代码」这条,2026-08-31/09-01 用 9 种靶子形状(6 个合成 + 3 个真实 Grails 7.2.3)、
   两个 adapter 版本(HEAD 与记录该缺陷的 `15bf219`)、JDK 21 与 25 全部跑过 —— **每次都正确**。
   连带证伪了原先的成因结论(「行中段的 bci」),也排除了 Groovy indy callsite、反射/native
   调用方、深栈、无行号中间帧、restep 链本身、「那两行不在该方法里」。
   见 `docs/experiments/2026-08-31-step-over-midline/`(harness + 逐组数据)与文档 §7.3。
   (这些实验针对的是并入前的 JDI step over;并入 origin/dev 之后 step over 不再走 JDI stepping,见文档 §7.3「已修(2026-09-04)」。)
   再遇到时必须留 `trace: true` 的完整 step 序列 + `javap -c -l` 的 LineNumberTable。
3. **已知限制**:~~`stepIn` 步不进 `@Transactional` 方法~~(2026-10-11 在 7 / 8 上确认,那是并入前的本地 adapter;并入 origin/dev 之后 S2 在 7.2.3 与 8.0.0 上**能进入**,见「合并 origin/dev(2026-10-12)」);
   ~~`stepOut` 只经过编译没实测~~ —— 已验(2026-10-11,7 / 8,见「Grails 7 / 8 支持」);
   多线程同时命中的行为未验。冷 JVM 下 `stepIn` 跳过闭包、变量面板
   `InconsistentDebugInfoException` 两条已修(2026-10-11),见「已修(2026-10-11)」。
4. 只读变量求值**已做**(见「已修(2026-10-11)」第 3 条;合并后是 origin/dev 的实现);条件断点(只做比较)、logpoint 来自 origin/dev;一般表达式求值、
   非字面量赋值、改对象 / 集合、**Watch 里改值(`setExpression`,合并时有意延后)**仍属 T2;`setVariable`(只改字面量)已做,
   见「已修(2026-10-11)」第 7 条。

### 已定的架构决策

- **不新建仓库**,就在 `dc-grails-vs` 里做;**不改名**(理由见文档 §6.1)
- DAP server **必须跑在 JVM 上**(JDI 是 Java API),extension 侧用
  `DebugAdapterExecutable('java', ['-jar', ...])` 拉起
- **server 用 Java 写**,不用 Kotlin/Groovy —— 决定性理由是自举陷阱:用 Groovy 写的调试器
  自己没法调。其余理由(启动延迟、vsix 体积、JDI 是 Java API)见文档 §10
- 目标结构:`extension.js`(Grails 专属)+ `server/`(面向通用 Groovy,对 Grails 零依赖,
  将来可零重构抽走)→ `dist/groovy-dap.jar` 打进 vsix
- **读的那一侧一行代码都不跑**(hover / logpoint / 条件 / 变量面板 / 数据断点)。
  **唯一的例外是 setVariable 造装箱值时调 `Integer.valueOf`**(只限 8 个 JDK 装箱类,`INVOKE_SINGLE_THREADED`),
  理由与边界见 §7.8;`WriteChecks` 里有检查把「目标里只跑过这 8 个类的 valueOf」钉住
- 编译与运行都需要 `--add-modules jdk.jdi`(该模块不在默认根模块集合中)
- **发布到 Marketplace** —— vsix 体积是真实约束。2026-10-12 并入 origin/dev 之后实测:vsix 3,381,267 字节(3.22 MB)、147 个文件;
  大头是 `dist/qa-browser`(随包的 Playwright driver,115 个文件,解压后约 12.9 MB:`coreBundle.js` 3.5 MB、`utilsBundle.js` 3.0 MB……),
  jar 只有 101334 字节;合并前本地 0.2.0 的 vsix 约 84 KB。QA 子系统与 playwright 按决定保留(随包)。`publisher` 已是合法 id `gaolei8888`
- **Watch 里改值(`setExpression`)延后**:合并时有意不带入,`supportsSetExpression` 不广告


### 合并 origin/dev(2026-10-12)

在隔离 worktree(`~/Documents/work/dc/dc-grails-vs-merge`,分支 `merge-dev`,从本地 `main` 3ce317e 拉出)里 `git merge --no-commit --no-ff origin/dev`(cecc37d),**未提交**。7 个冲突文件手工解决,其余两边的文件都带上。

- **evaluate / 改值取 origin/dev**:`PathEvaluator`、`MapReader`、`Values`、`ValueFactory`、`Variables.Node.set`、`onEvaluate` / `onSetVariable`。本地的 `PathExpression` / `ValueParser` / `EvaluationException` 删除(合并后没有任何东西用它们);**`setExpression`(Watch 里改值)延后**,`supportsSetExpression` 不广告。origin/dev 造装箱值时在目标里调 `valueOf` 被接受,**新增测试把它钉住**(`WriteChecks`:只允许 8 个 JDK 装箱类的 `valueOf`、一个基本类型参数、`INVOKE_SINGLE_THREADED`)。
- **调试类型统一 `groovy-dap`**:`package.json`(debuggers、activationEvents、breakpoints / languages 保留 origin/dev 的 `gsp`)、`extension.js`(含 origin/dev 的 `grails.attach`,它原来写死 `'groovy'`)、`lib/explain-exception.js`(tracker 工厂原来注册在 `'groovy'` 上,不改的话异常解释会悄悄失效)、测试。`grails.debug.adapter` 的取值不变。`grails.attach` 现在也会在同一 host:port 上已有 `groovy-dap` 会话时警告并不再附加一次。
- **从本地移植到 origin/dev 的 server 上**(各有测试):`org.apache.juli.*` 加进 `DEFAULT_STEP_EXCLUDES`;`continued{allThreadsContinued}` 在响应之后、**resume 之前**发,`onContinue` 与 `onStep` 都发(原来 `onContinue` 是 resume 之后);局部变量逐个 `frame.getValue`、只跳过 `InconsistentDebugInfoException`;求值遇到这个异常报 `'<name>' is not readable at this point ...`(origin/dev 的 `resolveRoot` 原来吞掉它并误报「no local, field or request attribute named」);`PathEvaluator.rejectUnsupported` 与括号匹配识别引号(`params['user-id']`、`map['a/b']`、`headers['x?y']`);`DapChannel` 接缝、`LoopbackChannel`、整个 `server/src/test`、`verifyProtocol` JavaExec、`scripts/build-server.js` 的错误报告。
- **测试数**:合并前本地 `verifyProtocol` 有 46 个块、45 项通过;合并后 45 个块、**44 项通过 / 0 失败**。删掉或改写的本地检查:`PathExpression` 文法表(被「调用 / 运算符 / 闭包按名字拒绝」+「引号内字符不算运算符」取代);200 元素上限的求值检查(origin/dev 的求值没有这个上限);「过期帧是错误不是异常」(单元层;e2e 里仍有 stale frame 检查);`ValueParser` 的 100 多行字面量表(十六进制、`L` 后缀、转义、范围措辞……origin/dev 的 `ValueFactory` 没有这些,换成 32 行 `ValueFactory` 表);「装箱 `Integer` 被拒」「`Reference` 被拒」两条拒绝检查(origin/dev 允许,改成正向检查);合成名 / `final` 字段 / 被截断元素的拒绝(origin/dev 的代码里没有这些,`final` 由 JVM 拒绝,e2e 里验过);`setExpression` 检查(延后);JDI `InvalidTypeException` / `ClassNotLoadedException` 的专门措辞(origin/dev 统一报 `could not set ...: <异常>`)。新增:引号内运算符字符、不可读局部变量的求值消息、`valueOf` 钉住、`continued` 覆盖 `stepIn`、条件 / hit count / logpoint 的 e2e。
- **合并后的脚本驱动复验**(2026-10-12,JDK 21.0.10,jar 101334 字节;scratchpad 里的 S1–S10 harness,`sourcePaths` 取扩展 `defaultSourcePaths` 的 artefact 目录;每个场景冷启动一次 `bootRun --debug-jvm`,S3、S6 另有热的第二遍):**两个靶子的 file:line 序列相同**(`dbgtarget` 7.2.3 与 `dbgtarget8` 8.0.0;除闭包编号 `closure2` 对 `closure3`;7 上的 `/nested` 返回 404,S6 / S7 不适用)。与合并前记录(本地 adapter)比:S4、S5a、S5b、S9 相同(8 上 S7 也相同),**其余不同,且是 origin/dev 的 step over / step into 设计带来的**:S1 方法返回后停在调用方**下一行**(10),不是调用行中段(9);S2 `stepIn` **能进入** `@Transactional` 方法(`$tt__transactionalMethod` 21、22、`closure:23`、25),此前是已知限制;S3 / S6 `stepIn` 进 `.each` 闭包停在 `closure:23`(冷、热一致),但**只停第一次迭代**(i=1),此前 i=1,2,3 都停;S10 同 S1。S3 一个请求含断点共停 5 次(合并前记录是 7 次)。**用户已于 2026-10-12 明确接受这一 stepIn 行为变化。**冷 JVM 下 stepIn 仍进闭包(`closure3.doCall:23`)。stepIn 要求 `sourcePaths` 是扩展传的源码目录。**注意**:harness 若把应用根目录当作唯一的 `sourcePaths`,origin/dev 的 step into 什么调用都不进(包过滤器是按 `sourcePaths` 下的目录名建的);扩展本身传的是 artefact 目录,不受影响。
- **没验证**:真实 VSCode 窗口(合并后的 evaluate / 改值 / 条件 / logpoint / 数据断点 / GSP / QA 面板 / 异常解释 / 状态栏 / launch 配置);F5、F10/F11、hover;Windows / Linux。GSP、数据断点、QA 面板没有任何 server 侧或 node 侧测试覆盖到合并后的行为(node 单元测试 34 项:GSP 5 项要 `VSCODE_BUILTIN_EXTENSIONS`)。

## 已知缺陷

### 已修(2026-10-11)

**前两条都只用脚本驱动的 DAP 客户端在 Grails 7.2.3 与 8.0.0(JDK 21)上验过,没在 VSCode UI 里跑过。
证据日志在会话 scratchpad,是临时文件。`server/` 现在有无依赖的 `verifyProtocol` 检查
(见「开发」),已覆盖 juli 排除与逐变量跳过这两个回归的守卫。**仍未覆盖**:`stepOut`(`stepIn` 只测了 `continued` 的顺序)、
异常断点、class-prepare 晚绑定路径;闭包 bci 1 的真实 Groovy 情形在检查里只是代理模拟;
Grails 的 S1–S10 矩阵是 scratchpad 里手动的 harness,不在 `build` 里。**

1. ~~**冷 JVM 下 `stepIn` 跳过闭包**~~ —— 全新 JVM 的第一个请求里,在 `.each` 行 `stepIn` 不进闭包,
   而是停在同一行 / 下一行。**根因**(7、8 上冷、热各 trace 一遍):Groovy 首次使用的初始化链
   `new Closure` → `MetaClassImpl.addProperties` → `Introspector.getBeanInfo` → `Class.forName`
   → Tomcat `WebappClassLoaderBase.loadClass` → `org.apache.juli.logging.DirectJDKLog.isTraceEnabled`
   把 step 落在一个项目之外、且比起点更深的帧上;`onStepDone` 对它发 `STEP_OUT`,而 step out
   **不会回到被打断的那次调用**,而是把那次调用的剩余部分整个跑完(整个 `each`,含闭包体)。
   **修法**:`server/src/main/java/org/groovydap/DebugSession.java` 的 `DEFAULT_STEP_EXCLUDES`
   加 `"org.apache.juli.*"`(一个 token + 注释)。
   **验证**(并入前的本地 adapter;冷、热各一遍,7、8):`.each` 行 `stepIn` 落 closure3:23(i=1,2,3);嵌套 `stepIn` 落
   closure4:31 再 closure5:32;其余用例(普通 `stepIn`、`@Transactional` 不进入且不挂、`stepOut`、
   `next` 无重复)不变。
   之前「闭包类还没加载」的解释是错的 / 不充分;冷、热的 3 帧深度差(7:95 对 92,8:81 对 78)只是
   第一个请求多出来的帧,不是成因。
   试过更通用的规则(重新 step into 那个更深的不可用帧):在 scratch 构建里只修好第二次出现,
   需要更多状态,**没采用**。
   **残留弱点(未测)**:第一次调用里、处在被排除调用链下的**其它**未排除的项目外帧会得到同样的
   处理(例如 Jetty / Undertow 的 class-loader logger)。
2. ~~**变量面板 `InconsistentDebugInfoException`**~~ —— 进入外层闭包(`closure4.doCall:31`,bci 1)
   时每次都出现,局部变量显示 `<error>=InconsistentDebugInfoException`。**根因**:
   `StackFrame.getValue(i)` 抛 `InconsistentDebugInfoException`;原代码一次 `frame.getValues(locals)`
   批量读全部局部变量、包在一个 try 里,一个坏变量就让整个面板变成 `<error>`。`closure4.doCall`
   (7、8 相同)的 LocalVariableTable 里 `i` 出现两次:`Reference` 类型的 `i` 在 slot 2,起点 bci 1、
   长度 58,而 `astore_2` 到 bci 9 才执行,所以 bci 1 时该 slot 是空的,VM 拒绝读;原来的 Object
   参数 `i`(slot 1)只在 bci 0 有效。`closure3`(无装箱)和 `closure5` 的 `j` 没问题。
   **修法**:`server/src/main/java/org/groovydap/jdi/Variables.java` 改成逐个 `frame.getValue`,
   只捕获 `InconsistentDebugInfoException` 并跳过该变量。
   **结果**:不再有 `<error>`;`closure4:31` bci 1 时面板不显示局部变量(`this`、其它局部变量照常),
   `i` 在 bci 58 重新出现(i=1)。
   **诚实的局限**:退回读同名参数试过,JDI 拒绝(`IllegalArgumentException`,`i is not valid at
   this frame location`),所以用户在那一次停下时看不到 `i`。
   **未验证**:JDWP 的错误码本身(机制由表和 `astore_2` 的位置推断,抛出点和字节码已确认)、
   其它装箱参数的形状、VSCode UI。
3. **只读变量求值**(新增,不是修复)—— **合并 origin/dev 之后用的是 origin/dev 的实现**:`jdi/PathEvaluator.java`、
   `jdi/MapReader.java`、`Variables.describeResult`、`DebugSession.onEvaluate`;能力 `supportsEvaluateForHovers=true`。
   (合并前本地写过一套 `PathExpression` / `EvaluationException` / `Variables.evaluate`,已随合并删掉。)
   Watch / hover / Debug Console / logpoint / 条件只**读**变量路径,不在目标里跑任何代码:标识符、`this`、`a.b.c`、
   `list[0]`、`map['k']`、`map.k`、`arr[1]`;查找顺序:帧局部变量 → `this` 字段 → 帧类静态字段 → Grails scope。
   调用、运算符、闭包按名字拒绝(`method calls are not supported: ...` / `operators are not supported: ...` /
   `closures are not supported`);未知名字报 `no local, field or request attribute named <name>`;
   尚未填充的 `Reference` slot(`closure4.doCall:31` bci 1)报 `'<name>' is not readable at this point (it is initialised
   later in this method)`(**合并时移植的修复**:origin/dev 原来把这个异常吞掉,于是误报成上一条)。
   **引号里的运算符字符不算运算符**(`params['user-id']`、`map['a/b']`、`headers['x?y']`、`map['a]b']`;合并时移植,`EvaluateChecks` 有测试)。
   已知限制:整数键的 map(`m[7]`)读不了(`MapReader` 只按字符串键和 ArrayList 下标);没有 200 个元素的上限(那是面板的,不是求值的)。
   **验证**:合并前的本地实现在 Grails 7.2.3 与 8.0.0 上用脚本驱动 DAP 客户端验过;用户在真实 VSCode 里见到 Watch `a = 100` 与
   Locals `a = 100` 一致(截图,2026-10-11;当时是本地实现)。合并后:`verifyProtocol` 的 EvaluateChecks(JDI fake)与
   EndToEnd(真实 JVM)。**未验证**:合并后的实现在真实 VSCode 窗口里的 Watch / hover;VSCode 是否会在 frameId 还不存在时就发 `evaluate(context=watch)`。
4. ~~**`scripts/build-server.js` 吞 gradle 错误**~~ —— 现在失败时报原因、命令、cwd、提示和退出码。
   已在 scratch 场景里覆盖,**没有**在真实树的失败路径上跑过。
5. ~~**扩展激活失败:调试类型 `groovy` 与别的扩展冲突**~~ —— `marlon407.code-groovy@0.3.0` 也为
   `groovy` 注册了 debug adapter factory,而 VSCode 每个 type 只允许一个 factory,于是我们的
   `activate()` 在建 status bar 之前就抛
   `a DebugAdapterDescriptorFactory can only be registered once per a type`,Grails 按钮 / 命令
   一个都没出现(在 extension host 日志里**已验证**)。**修法**:调试类型从 `groovy` 改名
   `groovy-dap`(`package.json` 与 `extension.js` 的 `DEBUG_TYPE`);`activate()` 现在能扛过这类冲突
   (窄 try/catch,写到「Grails - Debug」output channel,弹一条点名冲突扩展的警告)。设置
   `grails.debug.adapter` 的取值仍是 `groovy` / `java`,`groovy` 映射到 `groovy-dap`。
   **用户可见**:`launch.json` 必须写 `"type": "groovy-dap"`。
   提交 fd9e1db。**未验证**:新类型下 `.groovy` 断点在所有环境里都能被接受;Code Groovy 是否仍会干扰 F5。
   下拉里那条 "Groovy / Grails..." 是 Code Groovy 自己的,不是我们的。
   另:Code Groovy 自己的 Gradle init script(classpath resolver)在 Grails 项目上会在**它自己的**
   output channel 里报无害的 `Cannot mutate the state of configuration ':compileOnly'` 警告 ——
   不是我们的,无需处理。
6. **类重载后断点重新绑定**(新增,不是修复)—— 用户 2026-10-11 在**真实 VSCode 窗口**里用新 build 观察到:
   改 `ApplicationController.groovy`(`int a = 100` → `int a = 300`)并重启 / 重载该类后,第 14 行的
   断点停下时 Locals 与 Watch 都显示 `a = 300`;Debug Console 打出
   `[groovy-dap] com.example.ApplicationController was prepared again; dropping 1 stale request(s)` 与
   `[groovy-dap] bound ApplicationController.groovy:14 -> com.example.ApplicationController.index (bci 44)`
   (旧类那次是 bci 62)。即:类重载 → 断点重新绑到新类,新值可见。**只验了这一个场景**(Grails 8 测试
   项目 `dbgtarget8` 的 `index()` action)。
   **让应用加载新类的触发方式不清楚**(手动 `./gradlew classes`、continuous build、devtools 自动
   重编译都有可能)。此前的观察 —— 改了源码后运行中的应用仍显示旧值 100 —— 说明**只保存源码不会生效**。
   同一截图的 Call Stack 里有 Live Reload Server / File Watcher 线程(提示可能是 Spring Boot
   devtools,**推断,未核**)。
7. **修改变量(`setVariable`)**(新增,不是修复)—— **合并 origin/dev 之后用的是 origin/dev 的实现**:`jdi/ValueFactory.java`、
   `Variables.Node.set`、`DebugSession.onSetVariable`;能力 `supportsSetVariable` = true;**`supportsSetExpression` 不设**
   (Watch 里改值延后:合并前本地有一套 `setExpression` / `ValueParser`,有意没带进来)。
   **能改**:局部变量、实例字段、数组元素;基本类型与 `String`;**装箱类型 / `Object` 槽也能改**:数字、`true` / `false`、字符被读成对应的
   装箱值,这要在目标里对 8 个 JDK 装箱类之一调用 `valueOf`(§7.8;`WriteChecks` 把「只跑过这 8 个类的 valueOf、单线程、一个基本类型参数」钉住)。
   被闭包捕获的变量(`groovy.lang.Reference`)改的是盒子里的 `value`,盒子本身不换。`final` 字段由 JVM 拒绝
   (`could not set fixed: java.lang.IllegalArgumentException: Cannot set value of final field`,真实 JVM 的 e2e 里验过)。
   **新值**:字面量。十进制数(**不支持十六进制和 `L` 后缀**)、`true` / `false`、`'c'`、`"text"`(**不处理转义**:`"a\"b"` 会保留反斜杠)、`null`;
   范围检查,不静默收窄(`int ← 3.5`、`byte ← 300` 拒绝)。
   **与合并前本地实现的行为差异**(读 remote 代码并用 fake / 真实 JVM e2e 看到,**没有向用户确认**):`String` 槽接受不带引号的 `7`(得到 `"7"`,本地会拒绝);
   非装箱、非 `String` 的引用类型槽(`demo.Widget`、`List`、`BigDecimal`、`Number`)写数字时,会先在目标里 `Integer.valueOf` 出一个盒子,再被 JVM 拒绝
   (多一次无害的调用,报 `could not set ...`);没有对合成局部变量 / 字段(`$callSiteArray`)的拒绝(本地有;remote 代码里没有这一检查,未实测)。
   **验证**:合并前本地实现 —— `verifyProtocol: 44 passed, 0 failed`(当时),Grails 8.0.0 与 7.2.3 测试靶子的 scratch 副本上停住时设 `a=7`、
   `b=true`、`d=2`、`s="new \"q\""`、`arr[1]=99`,continue 后 HTTP 响应显示新值;用户在真实窗口确认「改值可以了」(本地实现)。
   合并后:fake 检查 + 真实 JVM 的 e2e(`x`、`flag`、`ratio`、`ch`、`label`、`gone`、`boxed`(`Integer` ← 7,真的调了 `valueOf`)、`nums[1]`、`hold.count`,
   目标打印出新值)。**未验证**:合并后的实现在 Grails 靶子 / 真实窗口里的行为;双击改值发的是哪个请求。
   **仍未实现(T2)**:一般表达式求值、非字面量赋值、改对象 / 集合、Watch 里改值。
8. **JDK 自动发现**(ca3e2fc)—— 起因是观察到过一次:运行中的 VSCode 的 PATH 是
   `/usr/bin:/bin:/usr/sbin:/sbin`、没有 `JAVA_HOME`,`gradlew` 撞上 macOS 的 `/usr/bin/java` 桩
   (`Unable to locate a Java Runtime`)。**更正(2026-10-12)**:这不是 Dock / `open -a` 启动的通则 ——
   VSCode 通常自己解析登录 shell 环境(`launchctl getenv JAVA_HOME` 为空,但 plugin host 里仍有
   `JAVA_HOME=~/.sdkman/candidates/java/current`)。那次失败的**成因不明**(可能是 VSCode 的
   shell 环境解析失败或超时,**未验证**);这里的搜索是它不解析时的兜底。
   `findJdk` 顺序:`grails.debug.javaPath`、`grails.run.env` / 进程环境里的 `JAVA_HOME`、macOS
   `java_home`、SDKMAN、各平台安装目录(含 Homebrew / Linux / Windows)、`PATH` 上的 `java`(排除那个桩)。
   只补缺口,不覆盖用户已设的值。adapter 需要 Java 17+ 且带 `jdk.jdi`;找不到时报一条清楚的错并列出
   三条出路;`grails.debug.javaPath` 无效时对 adapter 报错。用户也可设 `grails.run.env`
   `{"JAVA_HOME": ...}` / `grails.debug.javaPath`,或从终端用 `code .` 启动 VSCode。
   **已在真实窗口验证**(2026-10-12,`env -i` 启动的 VSCode:无 `JAVA_HOME`,
   `PATH=/usr/bin:/bin:/usr/sbin:/sbin`):应用照样起来,Output「Grails - Debug」打出
   `using JDK ~/.sdkman/candidates/java/current (~/.sdkman/candidates/java)`,
   attach 成功,断点命中。`PATH` 完全清空时 `gradlew` 自己就失败(`uname: command not found`),是人为
   情形,不处理。**未验证**:Windows / Linux 路径。
9. **launch 配置「Grails: Debug App」**(2cb7cee)—— Run and Debug 下拉(Dynamic
   `DebugConfigurationProvider` + initial configuration / snippet;标签 "Grails (Groovy debugger)");
   Grails 工作区里没有 `launch.json` 时按 F5 经共用的 `launchDebuggee()` 起 `./gradlew bootRun
   --debug-jvm`,再把 attach 配置交给 VSCode;已有监听时 attach 而不是再起一个;超时 / 提前退出会停掉
   我们起的进程;结束会话会停应用。launch 属性:`projectDir`、`args`、`env`、`startTimeout`、
   `sourcePaths`。`debugPort` 不可配(Gradle 写死 5005)。
   **真实窗口验证(2026-10-12,dc-octo,Grails 8.0.0 / JDK 21.0.10)**:Run and Debug 按钮 → 「Select
   debugger」quick pick(9 项:1 「Grails (Groovy debugger)」[Suggested]、2 「Groovy / Grails」[Code
   Groovy 的]、「More ... options...」若干含「More Grails (Groovy debugger) options...」、「Install an
   extension for Groovy...」)→ 选「Grails (Groovy debugger)」立刻起 `./gradlew bootRun --debug-jvm`;
   约 10 秒内 JDWP 5005 与应用 8080 都起来;Debug Console:`[groovy-dap] attached to localhost:5005
   (OpenJDK 64-Bit Server VM 21.0.10)`、`bound ApplicationController.groovy:14 -> ...index (bci 63)`;
   请求 `http://localhost:8080/` 停在第 14 行,Locals / Watch `a = 300`;Continue 继续;status bar 的
   Stop 约 5 秒内释放 5005 / 8080,弹 toast「Grails debug process exited with code 143」。之后重启时按钮
   直接启动、不再弹 picker(VSCode 记住了选择)。
   「No Configurations」下拉里有「Grails (Groovy debugger)...」;点它出「Select Launch Configuration」,
   当时里面有**两个**同名的 `Grails: Debug App`,一个是工作区文件夹(dbgtarget8)的,一个是 "user settings" 的
   (用户没选,没有生成 `launch.json`);此重复已修,见第 11 条。
   **双启动保护**:status bar 的 Debug 起来并 attach 后,再启动 launch 配置,弹警告「The running Grails app
   already has a debugger attached.」,没有第二个 bootRun(进程数不变)。
   **没测**:F5、F10/F11 按键、hover、Windows / Linux。
10. **status bar 只在 Grails 工作区显示**(b492a5e)—— `looksLikeGrailsProject`:顶层有 `grails-app`
   目录,或 `gradle.properties` / `build.gradle` / `build.gradle.kts` 里含 "grails"(不分大小写)。
   用户在真实窗口里见到按钮能出现。**已知弱点**:只是提到 grails 的普通 Gradle 项目会误判;子目录里的
   Grails 应用识别不到;多根工作区只看 `workspaceFolders[0]`。后续 TODO:收紧匹配、往下找一层。
11. ~~**「Select Launch Configuration」里有两个同名的 `Grails: Debug App`**~~(2026-10-12 的修复,
   `extension.js`)—— 起因:VSCode 对每个 launch 来源都问一次动态配置提供者,包括没有工作区文件夹的
   "user settings";提供者在 `folder` 为 undefined 时也返回了配置,于是出现重复。**修法**:`folder`
   为 undefined 时返回 `[]`,只给工作区文件夹提供。**真实窗口验证**(2026-10-12,用 dc-octo(dco)
   驱动用户的真实 VSCode:`dbgtarget8` 窗口,Grails 8.0.0,JDK 21.0.10,装的是用工作区当前树 = HEAD + 本条 +
   第 12 条构建的 vsix,`vscode:prepublish` 重建了 jar,所以字节与 `dist/` 不同但条目相同,装完 Developer:
   Reload Window):reload 后 status bar 出现 Grails 与 Debug 按钮;Run and Debug 下拉 →「Grails (Groovy
   debugger)...」→「Select Launch Configuration」现在**只有一项** `Grails: Debug App  dbgtarget8`
   (之前两项,其中一项 "user settings");Esc 关掉,没选,没生成 `launch.json`。
   status bar 的 Stop 约 1 秒内释放 5005 / 8080,没留下 groovy-dap / bootRun / dbgtarget8 进程。
12. ~~**其它线程在单步后仍显示 PAUSED**~~(2026-10-12 的修复,`server/` 的 `DebugSession`)——
   现象:单步后 Call Stack 面板里其它线程一直标 PAUSED,而应用其实在响应。**修法**:对 `next` /
   `stepIn` / `stepOut` / `continue`,在回应之后、**resume 之前**发 DAP `continued` 事件,带
   `allThreadsContinued=true`(此前单步没有这个事件,`continue` 的则发得晚)。`verifyProtocol` 当时
   **45 项**(新增一个端到端检查:断言每次单步 / continue 恰好一个 `continued`,且排在下一次停之前;
   据告知该检查在没有修复时会失败)。**合并后**这条在 origin/dev 的 assist 式 step over / step into 上同样成立:检查覆盖 `next`、`stepIn`、`continue`,`verifyProtocol` 现为 44 项,见「开发」。**真实窗口验证**(2026-10-12,dc-octo(dco),同第 11 条的 vsix):
   从侧边栏 Run and Debug 按钮启动;请求停在 `ApplicationController.groovy:14`,Call Stack 里
   `http-nio-8080-exec-1` 为 PAUSED ON BREAKPOINT,其它线程(Catalina-utility-2、container-0、Live Reload
   Server、File Watcher、http-nio-8080-exec-2、-3)为 PAUSED;点 Step Over 后(请求跑完,HTTP 200)Call
   Stack 头显示 "Running",所有线程(Catalina-utility-2、container-0、Live Reload Server、File Watcher、
   exec-1..exec-6)显示 RUNNING。**没验证**:单步之后再 Continue 的路径(竞态假设没被触发);「`continued`
   发得晚」那种竞态仍**未复现**。

### 已修(2026-08-31)

1. ~~**build output 里满屏 ANSI escape**~~ —— OutputChannel **不是终端,不渲染 ANSI**,Boot 的
   `%clr` 色码于是以字面文本出现(红色 `ESC` 块 + `[0;39m` 包住每个字段)。stdout 已经重定向
   到文件仍然有色 —— Boot 的着色不看 stdout 是不是 tty。改为在读出侧剥:CSI / OSC / 双字符
   escape 三条 replace,另加 `escHold` 留住被一次读劈成两半的 escape 尾巴。行解析器吃的也是
   剥完的文本,`dt_socket` 那条匹配不受影响。**已实机验证**:剥掉后 VSCode output 面板用它自己
   的 log 着色器染色(时间戳绿 / INFO 蓝 / WARN 红),跟主题走,比原来更好读。
2. ~~**status bar 的绿色按钮在蓝底上看不清**~~ —— `charts.green` / `charts.red` 是给编辑器背景
   调的,status bar 背景是主题 accent 蓝(调试中还会换成 VSCode 自己的调试色),绿字在上面比白字
   更糊。四个 item 的 `.color` 全去掉,走默认 `statusBar.foreground`,含义交给图标
   (`$(play)` / `$(debug-alt)` / `$(debug-stop)`),跟 VSCode 自带 run/debug 一致。
3. ~~**`server/gradlew` 在 git 里是 `100644`**~~ —— 没有可执行位。Windows 上无感,macOS 上
   `npm run build:server` 直接 EACCES,而脚本只打一句 `server build failed` 把真实错误吞了。
   已 `git update-index --chmod=+x`。~~`scripts/build-server.js` 吞错误这件事没修~~ —— 已修,见「已修(2026-10-11)」第 4 条。

### 已修(2026-08-30)

1. ~~`setTimeout(..., 3000)` 竞态~~ —— 改为盯 stdout 等
   `Listening for transport dt_socket at address: <port>` 出现再 attach,端口也从该行取。
   加了「只认第一次」的守卫:`server=y` 的 JDWP agent 在调试器断开后会**再打印一遍**这行。
2. ~~`exec` 应换成 `spawn`~~ —— `runApp` / `debug` / 那 199 条命令全部改用 `spawn`,不再受
   `maxBuffer` 默认 1 MB 的限制(超限会**直接杀掉子进程**)。
3. ~~环境变量不透传~~ —— 新增设置 `grails.run.command` / `grails.run.debugCommand`
   (自定义启动命令行,给 `run_dev.sh` 这类包装脚本用)、`grails.run.env`(注入的环境变量)、
   `grails.run.args`(附加给 bootRun 的参数)。
4. ~~**199 条命令一条都没真正执行**~~ —— helper 拼的是 `gradlew -Pargs="<name>"`,
   **完全没有任务名**;`-P` 只设项目属性,Gradle 于是回落到默认任务(未配置即 `help`)。
   已按名字形态分流:**带连字符的走 `grailsw`**(Grails CLI wrapper,Grails 3+ 生成的项目
   都自带,首次使用会自己下载对应版本的 CLI);其余是真正的 Gradle 任务名,走 `gradlew`。
   顺带:输入框按 Esc 取消现在会中止,以前会照跑;参数现在作为独立 argv 传,不再需要拼
   `-Pargs="..."` 那种带引号的单字符串。

   **已在 Grails 7.2.3 上实测**:老形式 `gradlew -Pargs="build"` 只跑出 `:help SKIPPED`;
   `gradlew runCommand -Pargs="create-controller Book"` 报
   **`Command not found for name: create-controller`** —— `runCommand` 跑的是应用自己的
   `ApplicationCommand`,代码生成命令不是那类东西;而
   `grailsw create-controller Book` 真正生成了 `BookController.groovy` 与其 Spec。

**注意:第 1–3 条只做到语法检查通过,没有在编辑器里实跑过。** 第 4 条的命令行形态已在真实
Grails 项目上验过,但同样没有从 VSCode UI 走过一遍。

### 未修

- **已知限制(用户决定不处理)**:JVM 停在断点上时点 Stop,只会结束 `gradlew` 客户端;应用 JVM 与
  adapter 要等到 Continue 才会走。
- **已知限制**:`stopEverything` 会杀掉任何监听 5005 的 java 进程;端口不可配(Gradle 写死 5005)。
- **真实窗口单步(2026-10-12,dc-octo,工具栏点击;`dbgtarget8` 的 `index()`,断点在第 13 行
  `int a = 300` 与第 14 行(结束该 action 的 map 字面量))**:停在 13:1(Locals 只有 `this`;Watch `a` →
  `'a' is not defined in this frame`);Step Over → 14:1,Locals 与 Watch 里 `a = 300`;在第 14 行 Step
  Into → 无可见变化(仍 14:1,无可进入的);在第 14 行 Step Out 或 Step Over → 请求跑完(HTTP 200),
  不会停在框架调用方里(设计如此:step filter 不在项目源码之外停)。
- **Step Into 在没有可进入内容的行上什么也不发生**(合并前本地 adapter 的观察,合并后未重测;保留:文档记载的有意行为,改动有回归风险;
  以后用 `trace: true` 抓一份并重跑 S1–S10 矩阵时再看)。原先同批的另两个小怪现象(重复的
  `Grails: Debug App`、其它线程仍标 PAUSED)已修,见「已修(2026-10-11)」第 11、12 条。
- **热重载**:改源码不会自动生效,要重新编译并让类重载;已观察到重载后断点重新绑定且新值可见(见
  「已修(2026-10-11)」第 6 条),但触发重载的是什么不清楚。
- ~~**vsix 里还带着三个很小的 `.github/modernize/java-upgrade` hook 脚本**~~ —— `.vscodeignore` 现在含 `.github/**`;合并后的 vsix 核对过,里面没有 `.github`。
- **收紧 `looksLikeGrailsProject`**(见「已修(2026-10-11)」第 10 条的弱点)。
- Code Groovy 自己的 classpath init script 在 Grails 项目上会打无害的
  `Cannot mutate the state of configuration ':compileOnly'` 警告(它的 output channel,不是我们的)。
- ~~`devDependencies` 只声明了废弃的 `vscode: ^1.1.37`~~ —— origin/dev 已重建 devDependencies 与 lockfile;新 worktree 里要先 `pnpm install`,才能 lint / 跑 Node 测试。

### 本轮已修(0.1.18 本地)

- 开发依赖与 lockfile 已重建，移除废弃 `vscode` 包及占位测试；lint 能运行。
- GSP grammar 和语言配置已补，复用 VS Code 内建 HTML/Groovy/JS/CSS grammar。
- `grails.attach` 引用了未定义 `workspaceFolder`，输入地址就失败；已补工作区检查并实测连接。

## 开发

```bash
pnpm install
npm run build:server   # 必须:构建 dist/groovy-dap.jar,否则调试 adapter 起不来
# F5 启动 Extension Development Host
pnpm test              # Node 单元测试(34 项;其中 GSP 的 5 项要 VSCODE_BUILTIN_EXTENSIONS=<VS Code>/Contents/Resources/app/extensions)
pnpm test:editor       # 独立 Grails 靶子 + 隔离 VS Code + Playwright
pnpm lint              # 0 error / 10 warning(都是 extension.js 里没用的 `err`)
(cd server && ./gradlew verifyProtocol)   # server/ 的无依赖检查(44 项);`./gradlew build` 也会跑
```

装到真实 VSCode 里试(不是 F5 那个 Extension Development Host,两者是两份代码):

```bash
npm run build:server
npx --yes @vscode/vsce package --no-dependencies    # --no-dependencies 必须:仓库用 pnpm,
                                                    # 默认的生产依赖核对会炸
"/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" \
  --install-extension grails-gradle-extension-0.2.0.vsix --force
```

装完必须 `Cmd+Shift+P` → `Developer: Reload Window`,否则跑的还是旧 extension;已经写进
output channel 的旧文本也不会回头重写,要重新 Run 一次才看得到新行为。

扩展**已经在 Marketplace 上**(最新已发布的 pre-release 是 0.1.29;0.1.30 在 origin/dev 上,未发布)。需要 PAT,先
`npx @vscode/vsce login gaolei8888`(交互式,**别把 token 放进 argv**)。**已定的计划(2026-10-12)**:把 `0.2.0` 作为 pre-release
做一次**更新发布**(不是首发):`npx @vscode/vsce publish --pre-release --no-dependencies`(版本号已写在 `package.json`,
所以**不要**用 `publish patch`,它会把版本顶到 0.2.1;`vscode:prepublish` 现在还会 `build:qa` 暂存 Playwright driver);之后再以正式版发 `0.2.1`
(预发布与正式版的版本号必须不同;VS Code 只支持 major.minor.patch,不支持 semver 后缀;奇偶约定只是官方建议,有意不遵循 ——
origin/dev 的旧说法「次版本号必须是奇数才算 pre-release」不准确,本次合并没有去核实官方文档)。PAT 的 scope 要
Marketplace → Manage,organization 必须选 All accessible organizations,否则 401。

`server/` 自带 gradle wrapper(8.14.3),用 JDK 17 构建。编译和运行都需要
`--add-modules jdk.jdi` —— 该模块不在默认根模块集合里。

本机依赖已修复。此前 pnpm virtual store 还指向移动前目录，`pnpm install --force` 后恢复。
编辑器测试默认靶子 `../grails-dap-testbed/dapspike`，一次只跑一个 suite。
具体环境覆盖参数、测试范围、输出位置见 `docs/testing.md`。

publisher 是 `gaolei8888`,marketplace id 是 `gaolei8888.grails-gradle-extension`。

图标是 `media/icon.png`,由 `scripts/make-icon.py` 生成(本机没有 PIL 也没有 ImageMagick,
所以那个脚本用 stdlib 直接写 PNG)。

## 本机环境坑

现在的开发机是 **macOS**(darwin,Apple silicon),`java` 是 **21.0.10**。`server/` 的
gradle toolchain 写的是 17,但 21 下 `./gradlew build` 实测能过(2026-08-31)。
这个 21 正好满足 Grails 8 应用的最低要求。

- `code` 这个 CLI shim 不在 PATH。要么用全路径
  `/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code`,要么在 VSCode 里跑
  `Shell Command: Install 'code' command in PATH`。**`code not found` 不等于没装 VSCode。**
- `node_modules/` 是 gitignore 的:新 clone / 新 worktree 要先 `pnpm install`(`--frozen-lockfile` 在合并时可用),之后 `npx eslint .` 才能跑。

历史坑(Windows 开发机时期,留作参照):`JAVA_HOME` 曾被设成
`C:\Users\gaole\bin\jdk-24\jdk-17.0.11`(两段路径粘连,目录不存在),任何 `./gradlew` 直接
`ERROR: JAVA_HOME is set to an invalid directory`,而 PATH 上的 `java` 是好的 —— 所以
`javac`/`java` 单独用毫无异常,只有 gradlew 炸。用 gradlew 前先 `ls "$JAVA_HOME"` 验一下。

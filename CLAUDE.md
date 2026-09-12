# CLAUDE.md

本文件为在此仓库工作的 Claude Code 提供指引。

## 这是什么

`dc-grails-vs` —— 一个 VSCode 扩展,给 **Grails/Groovy 项目**提供命令面板集成。

- `package.json`:`name: "grails-gradle-extension"`,`publisher: "Lei Gao"`,version 0.0.1
- `extension.js`:394 行 JS,注册 **199 条命令**,全部转译成 `./gradlew <task>` 执行:
  - 4 条显式命令 —— `grails.runApp` / `stopApp` / `debug` / `stopDebug`
  - `genericCommands[]` 76 条 —— 注册成 `grails.<cmd>`(`create-*`、`generate-*`、`dbm-*`、`s2-*`……)
  - `gradleTasks[]` 119 条 —— 注册成 `gradle.<task>`(`bootRun`、`build`、`test`、`idea`……)
  - 199 条声明与 199 条注册**完全对齐**,零缺口零重复(2026-08-30 核对)
- `snippets/`:Groovy/Grails 代码片段
- 尚未发布到 Marketplace(已决定要发,见下)

**这是一个通用工具,不绑定任何具体业务项目。** 需要一个 Grails 应用做靶子时,自建空白
Grails 7 应用,不要借用别的业务仓库。

## 当前状态(2026-09-06)

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
写死在 JDT 上,而 JDT 不索引 `.groovy`。没有任何配置能改。所以自己写了一个 debug adapter。

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

### 还没做 / 已知限制

- **本轮已补编辑器 UI 回归**：Attach 输入框、GSP gutter 断点/单步、变量面板右键数据断点、
  真实异常上下文与无模型流程。其余功能不因此视为全部完成 UI 回归。
- **macOS / Linux 的进程管理分支从没执行过**(`lsof` / `ps` / `SIGKILL`)。owner 说他来验。
- **表达式求值**(条件断点的完整形态、Debug Console 的 repl)仍属 T2 —— 需要在目标 VM 内
  编译执行 Groovy。目前只做比较与路径读取。
- **预编译 GSP 不存在**:Grails 7.2.3 没有 `compileGsp` 任务,war 里是页面源码(§7.11)。
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

### 已定的架构决策

- **不新建仓库**,就在 `dc-grails-vs` 里做;**不改名**(理由见文档 §6.1)
- DAP server **必须跑在 JVM 上**(JDI 是 Java API),extension 侧用
  `DebugAdapterExecutable('java', ['-jar', ...])` 拉起
- **server 用 Java 写**,不用 Kotlin/Groovy —— 决定性理由是自举陷阱:用 Groovy 写的调试器
  自己没法调。其余理由见文档 §10
- **读的那一侧一行代码都不跑**(hover / logpoint / 条件 / 变量面板 / 数据断点)。
  **唯一的例外是 setVariable 造装箱值时调 `Integer.valueOf`**,理由与边界见 §7.8
- 编译与运行都需要 `--add-modules jdk.jdi`


## 已知缺陷

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

### 本轮已修(0.1.18 本地)

- 开发依赖与 lockfile 已重建，移除废弃 `vscode` 包及占位测试；lint 能运行。
- GSP grammar 和语言配置已补，复用 VS Code 内建 HTML/Groovy/JS/CSS grammar。
- `grails.attach` 引用了未定义 `workspaceFolder`，输入地址就失败；已补工作区检查并实测连接。

## 开发

```bash
pnpm install
npm run build:server   # 必须:构建 dist/groovy-dap.jar,否则调试 adapter 起不来
# F5 启动 Extension Development Host
pnpm test              # Node 单元测试（上下文/模型路径/TextMate grammar）
pnpm test:editor       # 独立 Grails 靶子 + 隔离 VS Code + Playwright
pnpm lint
```

`server/` 自带 gradle wrapper(8.14.3),用 JDK 17 构建。编译和运行都需要
`--add-modules jdk.jdi` —— 该模块不在默认根模块集合里。

本机依赖已修复。此前 pnpm virtual store 还指向移动前目录，`pnpm install --force` 后恢复。
编辑器测试默认靶子 `../grails-dap-testbed/dapspike`，一次只跑一个 suite。
具体环境覆盖参数、测试范围、输出位置见 `docs/testing.md`。

发布:`npx @vscode/vsce package --pre-release --no-dependencies` 然后
`npx @vscode/vsce publish --pre-release --no-dependencies --packagePath <vsix>`。
**次版本号必须是奇数**才算 pre-release,所以正式版之前只动 patch 位。
publisher 是 `gaolei8888`,marketplace id 是 `gaolei8888.grails-gradle-extension`。

图标是 `media/icon.png`,由 `scripts/make-icon.py` 生成(本机没有 PIL 也没有 ImageMagick,
所以那个脚本用 stdlib 直接写 PNG)。

## 本机环境坑

`JAVA_HOME` 曾被设成 `C:\Users\gaole\bin\jdk-24\jdk-17.0.11`(两段路径粘连,目录不存在),
导致任何 `./gradlew` 直接以 `ERROR: JAVA_HOME is set to an invalid directory` 失败 ——
而 PATH 上的 `java` 是好的(17.0.11),所以 `javac`/`java` 单独用毫无异常,只有 gradlew 炸。
真实安装在 `C:\Users\gaole\bin\jdk-17.0.11`。用 gradlew 前先 `ls "$JAVA_HOME"` 验一下。

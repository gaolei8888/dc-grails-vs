# CLAUDE.md

本文件为在此仓库工作的 Claude Code 提供指引。

## 这是什么

`dc-grails-vs` —— 一个 VSCode 扩展,给 **Grails/Groovy 项目**提供命令面板集成。

- `package.json`:`name: "grails-gradle-extension"`,`publisher: "gaolei8888"`,version 0.1.7
- `extension.js`:1831 行 JS(2026-10-11 数;此前写 1347),注册 **202 条命令**(该数未重数;`package.json` 现声明 201 条):
  - 7 条显式命令 —— `grails.runApp` / `stopApp` / `debug` / `debugTests` / `stopDebug` /
    `openApp` / `showOutput`
  - `genericCommands[]` 76 条 —— 注册成 `grails.<cmd>`(`create-*`、`generate-*`、`dbm-*`、`s2-*`……),
    走 `grailsw`
  - `gradleTasks[]` 119 条 —— 注册成 `gradle.<task>`(`bootRun`、`build`、`test`、`idea`……),
    走 `gradlew`
  - `package.json` 声明 201 条,零缺口零重复;唯一差额是 **`grails.openApp` 注册了但没声明**
    —— 它只作为 status bar 的点击目标,故意不进命令面板(2026-08-31 逐 id 核对)
- `snippets/`:Groovy/Grails 代码片段
- 尚未发布到 Marketplace(已决定要发,见下)

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

## 当前主线任务:Groovy 断点绑定

**设计已完成;T0 spike 验收 1–5 全部通过(2026-08-30)。生死线解除,下一步进 T1。**

### 设计文档(权威,先读它)

```
docs/2026-08-29-vscode-groovy-debug-adapter.md
```

含:根因分析(本机实测证据)、IntelliJ `GroovyPositionManager` 参照实现、核心算法、
T0/T1/T2 分档计划、**§7.0 T0 实测结果**、**§7.1 Grails AST 变换与行号(静态)**、
**§7.2 第 5 条实机复验**、§11 T0 spike 完整源码、§10 决策记录与未决问题。

### 一句话背景

VSCode 里 `.groovy` 断点永远是空心的 —— `vscode-java-debug` 把「文件 URI → 全限定类名」写死在
JDT 上,而 JDT 不索引 `.groovy`。没有任何配置能改。要解决必须自己提供 debug adapter。

本仓库的 `grails.debug` 命令(`extension.js:86-124`)**当时**用 `type: 'java'` 把调试转交给
`vscode-java-debug`,**因此原封不动继承了这个限制**。现在默认走自带 adapter(类型 `groovy-dap`;
`grails.debug.adapter: "java"` 才走旧路),见「已修(2026-10-11)」。

### 核心思路

attach 模式下不需要解析 Groovy:`ReferenceType.locationsOfLine(n)` 就是"这个类是否拥有该行"的
权威答案,而闭包合成类 `Foo$_bar_closure1` 的 `sourceName()` 同样是 `Foo.groovy` —— 所以闭包
问题自动消解,只需要从文件头的 `package` 行正则出前缀。详见设计文档 §4。

### T0 验收结果(2026-08-30,全部通过)

§11 的 spike 源码已编译验证(JDK 17 + `--add-modules jdk.jdi`)。1–4 条用自建的独立 Groovy
靶子实测,第 5 条用**自建的空白 Grails 7.2.3 应用**(forge 生成的 rest_api + 一个 class 级
`@Transactional` service,不借业务仓库)实测:

| # | 验收项 | 结果 |
|---|---|---|
| 1 | attach | 通过 |
| 2 | 普通方法行 | 通过 |
| 3 | 闭包行 / 嵌套闭包行 | 通过 |
| 4 | 类未加载时靠 `prefix + "*"` 补装 | 通过(Grails 上再次成立:attach 时匹配 0 个类) |
| 5 | 行号准确性(Grails AST 变换) | **通过** —— 行号逐行精确,详见文档 §7.2 |

实测得到的新结论,已写进文档,实现时必须考虑:

1. **闭包捕获的局部变量显示为 `groovy.lang.Reference`**,变量面板必须解包,不只是过滤合成变量。
2. **方法签名行装不上断点** —— 该行没有行号项。注意这**不只是** `@Transactional` 把方法体搬进
   `$tt__xxx` 的锅:`@NotTransactional` 的普通方法一样如此,纯 Groovy 项目同样会踩。需要
   "向下吸附到第一条可执行行"的兜底。
3. **一行会绑到同一方法内的多个 bci,且不能只取第一个** —— Groovy 4 给每条语句生成 callsite
   慢路径 + primitive 快路径两份字节码,共用同一行号。实测行 34 只命中快路径(bci 73),取最小
   bci(26)会让断点**永远不响**;而行 20 的两处在一次调用里都经过,会连停两次。
   **结论:location 全装,去重放在命中侧(thread + frame + line)。**
4. **`Foo$_bar_closure1` 不一定是用户闭包** —— `@Transactional` 生成的事务回调也占这个命名
   (`doCall(TransactionStatus)`,无行号表)。§4 算法靠 `locationsOfLine()` 为空自动排除,
   不需要认名字;但靠类名做启发式的实现会翻车。

### Grails 7 / 8 支持(2026-10-11 脚本驱动实测;主要流程另在真实 VSCode 窗口里抽查过,见本节末尾)

这些改动的提交历史:`git log 37b3c5d^..2cb7cee`。

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

`stepIn` / `stepOut` / step filter 实测(脚本驱动 DAP 客户端,JDK 21.0.10,2026-10-11;
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
   改值(「改值可以了」;发的是 `setVariable` 还是 `setExpression` 不知道)、类重载后断点重新绑定。
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

### T1 进行中:`server/` 已建并跑通(2026-08-30)

路线**已定:自写 DAP server,不 fork `microsoft/java-debug`**(理由见文档 §10 已决表)。

`server/` = Java 17、**零第三方依赖**、产物 `dist/groovy-dap.jar` 76649 字节(2026-10-11 重测;此前写 44 KB)。20 个 DAP 命令
(18 个各有 handler,`disconnect` / `terminate` 共用一支;含 `evaluate`、`setVariable`、`setExpression`)和 6 类事件已实现;断点绑定、签名行下滑、闭包补装、命中去重、`Reference` 解包、栈帧回映射
都在空白 Grails 7.2.3 应用上用脚本驱动的 DAP 客户端验过。详见文档 **§7.3**。

`dist/` 是构建产物,已 gitignore。**F5 之前先 `npm run build:server`**,否则 adapter 不存在。

`next` 与 `stepIn` 也已实机验证。step filter 是**两层**的:JDI 的包排除只管成本,
「落点源文件在不在 `sourcePaths` 下」才是决定停不停的规则(见 §7.3「step filter」)。

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
   再遇到时必须留 `trace: true` 的完整 step 序列 + `javap -c -l` 的 LineNumberTable。
3. **已知限制**:`stepIn` 步不进 `@Transactional` 方法(2026-10-11 在 7 / 8 上再次确认);
   ~~`stepOut` 只经过编译没实测~~ —— 已验(2026-10-11,7 / 8,见「Grails 7 / 8 支持」);
   多线程同时命中的行为未验。冷 JVM 下 `stepIn` 跳过闭包、变量面板
   `InconsistentDebugInfoException` 两条已修(2026-10-11),见「已修(2026-10-11)」。
4. 只读变量求值**已做**(2026-10-11,见「已修(2026-10-11)」第 3 条);条件断点、一般表达式求值、
   非字面量赋值、改对象 / 集合仍属 T2;`setVariable` / `setExpression`(只改字面量)已做,
   见「已修(2026-10-11)」第 7 条。

### 已定的架构决策

- **不新建仓库**,就在 `dc-grails-vs` 里做;**不改名**(理由见文档 §6.1)
- DAP server **必须跑在 JVM 上**(JDI 是 Java API,JS 不可选),extension 侧用
  `DebugAdapterExecutable('java', ['-jar', ...])` 拉起
- **server 用 Java 写**,不用 Kotlin/Groovy —— 决定性理由是自举陷阱:用 Groovy 写的调试器
  自己没法调。其余理由(启动延迟、vsix 体积、JDI 是 Java API)见文档 §10
- 目标结构:`extension.js`(Grails 专属)+ `server/`(面向通用 Groovy,对 Grails 零依赖,
  将来可零重构抽走)→ `dist/groovy-dap.jar` 打进 vsix
- 编译与运行都需要 `--add-modules jdk.jdi`(该模块不在默认根模块集合中)
- **要发布到 Marketplace** —— 因此 vsix 体积是真实约束(2026-10-11 前的 vsix 约 80 KB,手头旧 vsix 83.7 KB;jar 现 76649 字节,vsix **未重测**)。`publisher`
  已是合法 id `gaolei8888`

## 已知缺陷

### 已修(2026-10-11)

**前两条都只用脚本驱动的 DAP 客户端在 Grails 7.2.3 与 8.0.0(JDK 21)上验过,没在 VSCode UI 里跑过。
证据日志在会话 scratchpad,是临时文件。`server/` 现在有无依赖的 `verifyProtocol` 检查
(见「开发」),已覆盖 juli 排除与逐变量跳过这两个回归的守卫。**仍未覆盖**:`stepIn` / `stepOut`、
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
   **验证**(冷、热各一遍,7、8):`.each` 行 `stepIn` 落 closure3:23(i=1,2,3);嵌套 `stepIn` 落
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
3. **只读变量求值**(新增,不是修复)—— `server/` 里实现:`jdi/PathExpression.java`、
   `EvaluationException.java`、`Variables.evaluate`、`DebugSession.onEvaluate`;能力
   `supportsEvaluateForHovers=true`。Watch / hover / Debug Console **只求变量路径**,不在目标里
   跑任何代码:标识符、`this`、`a.b.c`、`list[0]`、`map['k']`、`map.k`、`arr[1]`;查找顺序:帧局部变量
   → `this` 字段 → Grails scope。`list[n]` 只处理 `ArrayList`。其余(运算符、调用、赋值、`new`、
   三元、cast、字面量、`?.`)一律报 `only variable paths can be evaluated here (the debugger does not
   run code in the application)`;未定义报 `'<name>' is not defined in this frame`;尚未填充的
   `Reference` slot(`closure4.doCall:31` bci 1)报 `'<name>' is not readable at this point (it is
   initialised later in this method)`。
   **验证**:脚本驱动 DAP 客户端,Grails 7.2.3 与 8.0.0(`x`、`sum`、`this`、`i`、`j` 与变量面板一致;
   S1–S10 停点序列不变);另有用户在真实 VSCode 里装新 build 后见到 Watch `a = 100` 与 Locals
   `a = 100` 一致(截图,2026-10-11)。**未验证**:VSCode 是否会在 frameId 还不存在时就发
   `evaluate(context=watch)`;hover。
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
7. **修改变量(`setVariable` / `setExpression`)**(新增,不是修复)—— `server/` 里实现:
   `jdi/ValueParser.java`(新)、`Variables.change()`、`DebugSession` 的 `setVariable` / `setExpression`
   handler;能力 `supportsSetVariable`、`supportsSetExpression` = true。引擎**不在目标里跑代码**
   (检查里的 fake 对任何 `invoke*` / `newInstance` 都抛)。
   **能改**:局部变量、实例字段、数组元素,类型为基本类型(byte/short/int/long/float/double/boolean/char)
   或 `String`;引用类型目标可设 `null`。新值必须是**字面量**:数字(含负数 / 十六进制,须在有符号范围内)、
   `true` / `false`、`'c'`、带转义的 `"text"`、`null`;严格范围检查,不静默收窄(`int ← 3.5` 拒绝,
   `double ← 3` 可,`byte ← 300` 拒绝)。裸的单个字符只在不可能是变量名时才被接受给 `char`
   (`7`、`#` 可,`y` 拒绝)。
   **拒绝(报清楚的错,旧值保留)**:被闭包捕获的变量(`groovy.lang.Reference`:改它要新建装箱对象,
   且可能破坏共享的 `Integer` 缓存对象)、持有装箱 / `Object` 值的变量(要创建对象)、`final` 字段、
   `this`、map 条目与 Grails scope、面板没显示的元素、非字面量(如 `y + 1`)、给基本类型设 `null`、
   给 `String` 变量设数字(必须加引号)、后面才初始化的局部变量(`closure4.doCall:31` bci 1)、
   resume 之后的过期帧。静态字段面板不显示,所以不可写。
   **验证**:脚本驱动 DAP 客户端 + gradle build:`verifyProtocol: 44 passed, 0 failed`(提交时的数;原 32,之后第 12 条又加一项成 45;新增
   `WriteChecks.java` 12 项加端到端补充)。在 Grails 8.0.0 与 7.2.3 测试靶子的 scratch 副本上:停住时
   设 `a=7`、`b=true`、`d=2`、`s="new \"q\""`、`arr[1]=99`,continue 后 HTTP 响应显示新值;被捕获的
   闭包变量被拒;闭包参数 `k`(未被捕获)可写。
   **真实窗口**:用户确认改值能用(「改值可以了」)。**未验证**:双击改值与 Watch 里改值分别发
   `setVariable` 还是 `setExpression`(不知道);编辑体验的细节。**没发** `invalidated` 事件。
   **仍未实现(T2)**:条件断点、一般表达式求值、非字面量赋值、改对象 / 集合。
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
   `allThreadsContinued=true`(此前单步没有这个事件,`continue` 的则发得晚)。`verifyProtocol` 现在
   **45 项**(新增一个端到端检查:断言每次单步 / continue 恰好一个 `continued`,且排在下一次停之前;
   据告知该检查在没有修复时会失败)。**真实窗口验证**(2026-10-12,dc-octo(dco),同第 11 条的 vsix):
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
- **Step Into 在没有可进入内容的行上什么也不发生**(保留:文档记载的有意行为,改动有回归风险;
  以后用 `trace: true` 抓一份并重跑 S1–S10 矩阵时再看)。原先同批的另两个小怪现象(重复的
  `Grails: Debug App`、其它线程仍标 PAUSED)已修,见「已修(2026-10-11)」第 11、12 条。
- **热重载**:改源码不会自动生效,要重新编译并让类重载;已观察到重载后断点重新绑定且新值可见(见
  「已修(2026-10-11)」第 6 条),但触发重载的是什么不清楚。
- **vsix 里还带着三个很小的 `.github/modernize/java-upgrade` hook 脚本**(Copilot 遗留);以后把
  `.github/modernize/` 加进 `.vscodeignore`。
- **收紧 `looksLikeGrailsProject`**(见「已修(2026-10-11)」第 10 条的弱点)。
- Code Groovy 自己的 classpath init script 在 Grails 项目上会打无害的
  `Cannot mutate the state of configuration ':compileOnly'` 警告(它的 output channel,不是我们的)。
- `devDependencies` 只声明了废弃的 `vscode: ^1.1.37`,而实际要的是 eslint / vscode-test;
  且本机 `node_modules/` **整个不存在**,`npx eslint .` 跑不了。要 lint 先 `pnpm install`。

## 开发

```bash
pnpm install
npm run build:server   # 必须:构建 dist/groovy-dap.jar,否则调试 adapter 起不来
# F5 启动 Extension Development Host
pnpm test              # .vscode-test.mjs
npx eslint .
(cd server && ./gradlew verifyProtocol)   # server/ 的无依赖检查(45 项);`./gradlew build` 也会跑
```

装到真实 VSCode 里试(不是 F5 那个 Extension Development Host,两者是两份代码):

```bash
npm run build:server
npx --yes @vscode/vsce package --no-dependencies    # --no-dependencies 必须:仓库用 pnpm,
                                                    # 默认的生产依赖核对会炸
"/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code" \
  --install-extension grails-gradle-extension-0.1.7.vsix --force
```

装完必须 `Cmd+Shift+P` → `Developer: Reload Window`,否则跑的还是旧 extension;已经写进
output channel 的旧文本也不会回头重写,要重新 Run 一次才看得到新行为。

发布到 Marketplace 还没做,缺 PAT:`npx @vscode/vsce login gaolei8888`(交互式,**别把 token
放进 argv**),再 `npx @vscode/vsce publish patch --no-dependencies`。PAT 的 scope 要
Marketplace → Manage,organization 必须选 All accessible organizations,否则 401。

`server/` 自带 gradle wrapper(8.14.3),用 JDK 17 构建。编译和运行都需要
`--add-modules jdk.jdi` —— 该模块不在默认根模块集合里。

## 本机环境坑

现在的开发机是 **macOS**(darwin,Apple silicon),`java` 是 **21.0.10**。`server/` 的
gradle toolchain 写的是 17,但 21 下 `./gradlew build` 实测能过(2026-08-31)。
这个 21 正好满足 Grails 8 应用的最低要求。

- `code` 这个 CLI shim 不在 PATH。要么用全路径
  `/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code`,要么在 VSCode 里跑
  `Shell Command: Install 'code' command in PATH`。**`code not found` 不等于没装 VSCode。**
- `node_modules/` 不存在,`npx eslint` 跑不了(见上「未修」)。

历史坑(Windows 开发机时期,留作参照):`JAVA_HOME` 曾被设成
`C:\Users\gaole\bin\jdk-24\jdk-17.0.11`(两段路径粘连,目录不存在),任何 `./gradlew` 直接
`ERROR: JAVA_HOME is set to an invalid directory`,而 PATH 上的 `java` 是好的 —— 所以
`javac`/`java` 单独用毫无异常,只有 gradlew 炸。用 gradlew 前先 `ls "$JAVA_HOME"` 验一下。

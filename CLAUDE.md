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

本仓库的 `grails.debug` 命令(`extension.js:86-124`)当前用 `type: 'java'` 把调试转交给
`vscode-java-debug`,**因此原封不动继承了这个限制**。

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

### T1 进行中:`server/` 已建并跑通(2026-08-30)

路线**已定:自写 DAP server,不 fork `microsoft/java-debug`**(理由见文档 §10 已决表)。

`server/` = Java 17、**零第三方依赖**、产物 `dist/groovy-dap.jar` 约 44 KB。16 个 DAP 请求
和 6 类事件已实现;断点绑定、签名行下滑、闭包补装、命中去重、`Reference` 解包、栈帧回映射
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
2. ~~从行中段发起的 step over 会冲出整个方法体~~ —— **已修(2026-09-04)**:step over 不再走
   JDI stepping,改成「本方法其余每一行的首个 location 各下一个线程过滤的断点 + 一个
   MethodExit」,谁先到算谁。三次刻画触发条件的尝试全部被实验证伪,所以是绕开而不是诊断。
3. **已知限制**:`stepIn` 步不进 `@Transactional` 方法;`stepOut` 只经过编译没实测;
   多线程同时命中的行为未验。
4. 条件断点(Condition 框)属 T2 —— 需要在目标 VM 内编译执行 Groovy。**Hit Count 与
   Log Message 不需要**,已实现并实测(2026-09-05,见文档 §7.5);hover / watch 走
   `PathEvaluator` 只读路径(0.1.8)。

### 已定的架构决策

- **不新建仓库**,就在 `dc-grails-vs` 里做;**不改名**(理由见文档 §6.1)
- DAP server **必须跑在 JVM 上**(JDI 是 Java API,JS 不可选),extension 侧用
  `DebugAdapterExecutable('java', ['-jar', ...])` 拉起
- **server 用 Java 写**,不用 Kotlin/Groovy —— 决定性理由是自举陷阱:用 Groovy 写的调试器
  自己没法调。其余理由(启动延迟、vsix 体积、JDI 是 Java API)见文档 §10
- 目标结构:`extension.js`(Grails 专属)+ `server/`(面向通用 Groovy,对 Grails 零依赖,
  将来可零重构抽走)→ `dist/groovy-dap.jar` 打进 vsix
- 编译与运行都需要 `--add-modules jdk.jdi`(该模块不在默认根模块集合中)
- **要发布到 Marketplace** —— 因此 vsix 体积是真实约束,且 `publisher` 必须换成合法 id

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

### 未修

- **`publisher` 字段 `"Lei Gao"` 含空格,不是合法的 marketplace publisher id** —— 发布前必须
  换成你注册的 id(需要你提供)。仓库名与 marketplace id 是两回事,`package.json` 的
  `"name": "grails-gradle-extension"` 才是发布标识。
- `devDependencies` 只声明了废弃的 `vscode: ^1.1.37`,而实际装的是 eslint / vscode-test;
  `node_modules/eslint/` 包目录缺失,`npx eslint` 直接 MODULE_NOT_FOUND。

## 开发

```bash
pnpm install
npm run build:server   # 必须:构建 dist/groovy-dap.jar,否则调试 adapter 起不来
# F5 启动 Extension Development Host
pnpm test              # .vscode-test.mjs
npx eslint .
```

`server/` 自带 gradle wrapper(8.14.3),用 JDK 17 构建。编译和运行都需要
`--add-modules jdk.jdi` —— 该模块不在默认根模块集合里。

注意:本机 `node_modules` 是坏的 —— `.bin/eslint` 在但 `node_modules/eslint/` 包目录不存在
(pnpm 软链没建成),`npx eslint` 直接 MODULE_NOT_FOUND。重装一次即可。

发布前注意:`publisher` 字段现值 `"Lei Gao"` 含空格,不是合法的 marketplace publisher id,
需换成注册的 id。仓库名与 marketplace id 是两回事,后者是 `package.json` 的 `name` 字段。

## 本机环境坑

`JAVA_HOME` 曾被设成 `C:\Users\gaole\bin\jdk-24\jdk-17.0.11`(两段路径粘连,目录不存在),
导致任何 `./gradlew` 直接以 `ERROR: JAVA_HOME is set to an invalid directory` 失败 ——
而 PATH 上的 `java` 是好的(17.0.11),所以 `javac`/`java` 单独用毫无异常,只有 gradlew 炸。
真实安装在 `C:\Users\gaole\bin\jdk-17.0.11`。用 gradlew 前先 `ls "$JAVA_HOME"` 验一下。

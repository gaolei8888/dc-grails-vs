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

**Groovy 调试适配器已经做出来并发布**,Marketplace 上是 `0.1.17`(pre-release,
publisher `gaolei8888`)。主线任务从「让断点能绑上」变成「继续把这个调试器做好用」。

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
| **数据断点**;Attach to Running App | 0.1.17 | harness / 未验 |

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

- **编辑器 UI 自 0.1.9 之后一次没走过**。0.1.16 已装到本机。GSP 的 `contributes.languages`
  是纯 UI 侧的东西,harness 完全不经过。
- **macOS / Linux 的进程管理分支从没执行过**(`lsof` / `ps` / `SIGKILL`)。owner 说他来验。
- **表达式求值**(条件断点的完整形态、Debug Console 的 repl)仍属 T2 —— 需要在目标 VM 内
  编译执行 Groovy。目前只做比较与路径读取。
- **预编译 GSP 不存在**:Grails 7.2.3 没有 `compileGsp` 任务,war 里是页面源码(§7.11)。
- **AI 功能**(用 `vscode.lm` 把断点上下文喂给模型)已提出未开工,见下。

### 可能的下一步:AI 功能

owner 问过。结论是**不要再做一个聊天框**(那是 Copilot 的活),值得做的是把调试器手里的
活数据当上下文:

1. **解释异常** —— 异常断点命中时,把异常类型、message、过滤掉框架帧之后的栈、相关源码行
   一起给模型。(owner 未选,我建议先做这条:那一刻上下文最全)
2. **解释这次停顿** —— 当前帧 + 局部变量 + Grails scope。
3. **自然语言 → 断点条件 / logpoint**,并且能当场用现有的比较式校验。

代价要先说清楚:`engines.vscode` 要从 `^1.63` 提到 `^1.90`,且**用户没有 Copilot 时功能是灰的**。

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

### 未修

- `devDependencies` 只声明了废弃的 `vscode: ^1.1.37`,而实际装的是 eslint / vscode-test;
  `node_modules/eslint/` 包目录缺失,`npx eslint` 直接 MODULE_NOT_FOUND。重装一次即可。
- **`.gsp` 的语法高亮没有做** —— 只贡献了语言 id(为了能下断点),没有 grammar,
  所以 GSP 文件在编辑器里是纯文本。

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

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

### 下一步:开 `server/` 进 T1

生死线已过,路线**已定:自写 DAP server,不 fork `microsoft/java-debug`**(2026-08-30,
理由见文档 §10 已决表)。

T1 = 最小 DAP server(约 15 个 DAP 请求 + 6 类事件),见文档 §7 的 T1 小节,其中列了 4 个坑与
2 条补充验收。

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

## 已知缺陷(与断点无关,可顺手修)

1. **`extension.js:108` 的 `setTimeout(..., 3000)` 是竞态** —— 冷启动时 Gradle 配置+编译远超
   3 秒,JVM 还没开始监听,attach 失败。应盯 stdout 等
   `Listening for transport dt_socket at address: 5005` 出现再 attach。
2. **`extension.js:99` 的 `exec` 应换成 `spawn`** —— `child_process.exec` 的 `maxBuffer` 默认
   1 MB,超限**直接杀掉子进程**。Grails 日志几分钟就顶破,服务会莫名其妙死掉且报错不指向此处。
3. **环境变量不透传** —— 很多 Grails 项目用包装脚本(`run_dev.sh` 之类,通常就是
   `exec ./gradlew bootRun "$@"`)来 export 应用需要的环境变量,扩展直接 `exec gradlew` 会全丢。
   应支持配置自定义启动命令,或在设置里声明要注入的 env。

## 开发

```bash
pnpm install
# F5 启动 Extension Development Host
pnpm test          # .vscode-test.mjs
npx eslint .
```

发布前注意:`publisher` 字段现值 `"Lei Gao"` 含空格,不是合法的 marketplace publisher id,
需换成注册的 id。仓库名与 marketplace id 是两回事,后者是 `package.json` 的 `name` 字段。

## 本机环境坑

`JAVA_HOME` 曾被设成 `C:\Users\gaole\bin\jdk-24\jdk-17.0.11`(两段路径粘连,目录不存在),
导致任何 `./gradlew` 直接以 `ERROR: JAVA_HOME is set to an invalid directory` 失败 ——
而 PATH 上的 `java` 是好的(17.0.11),所以 `javac`/`java` 单独用毫无异常,只有 gradlew 炸。
真实安装在 `C:\Users\gaole\bin\jdk-17.0.11`。用 gradlew 前先 `ls "$JAVA_HOME"` 验一下。

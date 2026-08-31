# VSCode Groovy 断点绑定 —— 根因、IntelliJ 参照实现与 DAP 方案

> 状态:**设计完成;T0 spike 验收 1–5 全部通过**(见 §7.0、§7.2)。下一步是开 `server/` 进 T1。
> 日期:2026-08-29(创建) / 2026-08-30(T0 结果、第 5 条实机复验、决策更新、迁入本仓库)
> 实现仓库:**本仓库** `dc-grails-vs`(不新建仓库)
> 本文是交接文档,面向"另开 session 直接开工"的场景,证据锚点全部为本机实测。

---

## 0. 一句话

VSCode 里 `.groovy` 断点绑不上,不是配置问题,是 `vscode-java-debug` 的类名解析写死在 JDT 上、没有可插拔的位置管理器;IntelliJ 靠 `PositionManager` 扩展点解决,而**在 attach 模式下我们可以用一条更便宜的路绕过去 —— 让 JVM 自己当权威,完全不解析 Groovy 源码**。

---

## 1. 问题

在一个 Grails 7.2.1 应用里用 VSCode 调试(本文证据均取自该环境的本机实测):

- attach 到 `./gradlew bootRun --debug-jvm` 的 5005 端口是通的;
- 但 `.groovy` 文件上的断点保持**空心(unverified)**,执行到该行不会停;
- 少数情况下断点变实心却仍不触发 —— 那是闭包被编译进了独立的合成类。

两种失败要分清:

| 现象 | 含义 | 原因 |
|---|---|---|
| 空心断点 | 调试器没能把"文件+行"翻译成 JVM 类名,断点从未注册进 JVM | JDT 不索引 `.groovy` |
| 实心但不停 | 断点装在了外层类上,而该行的字节码属于闭包合成类 | `Foo$_bar_closure1` 是独立的 `ReferenceType` |

> 注意:**attach 之前所有断点都显示空心,这是正常的**。判断必须在 attach 成功之后。

---

## 2. 根因(本机核验)

断点绑定的链路是:

```
VSCode 编辑器
  → DAP setBreakpoints(文件 URI, 行号)
    → debug adapter 把文件 URI 解析成全限定类名 (FQN)     ← 断在这一步
      → JDI EventRequestManager.createBreakpointRequest(Location)
        → 目标 JVM 装载断点
```

**证据一:`redhat.java` 不认 `.groovy`。**
`~/.vscode/extensions/redhat.java-1.55.0-win32-x64/server/plugins/` 里只有 m2e(Maven)系列和 `org.gradle.toolingapi_8.9.0`,**没有任何 Groovy 插件**。JDT 只对"java-like 扩展名"(由 Eclipse content-type `org.eclipse.jdt.core.javaSource` 决定)建立编译单元模型;`.groovy` 要靠 Groovy-Eclipse 这类插件注册进去,它不在这个发行包里。

**证据二:没有开关可调。**
`~/.vscode/extensions/vscjava.vscode-java-debug-0.59.0/package.json` 中全部 47 个 `java.debug.*` 配置项里,没有一个与源文件/FQN 解析相关。`sourcePaths` 只影响**反向**查找(见 §5)。

**证据三:上游状态。**
- [microsoft/vscode#190364](https://github.com/microsoft/vscode/issues/190364) "Breakpoint is not enabling for Groovy files" — 已关闭,未实现。
- [redhat-developer/vscode-java#205](https://github.com/redhat-developer/vscode-java/issues/205) "Mixed Java/Groovy project support" — 长期开放。
- Marketplace 上没有独立的 Groovy debug adapter;`marlon407.code-groovy` 等均为纯语法/LSP 扩展,不参与调试。

结论:**这不是"再装个扩展"能补的,必须改 debug adapter 本身。**

---

## 3. IntelliJ 参照实现

### 3.1 开源情况

| | 仓库 | 许可 | 状态 |
|---|---|---|---|
| **Groovy 插件**(断点绑定靠它) | `JetBrains/intellij-community` → `plugins/groovy/` | Apache 2.0 | 一直开源,随 IDE 打包 |
| **Grails 插件**(GSP、run config) | [`apache/grails-intellij-plugin`](https://github.com/apache/grails-intellij-plugin) | Apache 2.0 | 原为 Ultimate 闭源,JetBrains 已捐给 ASF(自 `intellij-obsolete-plugins` 的 grails 目录导入),现已不随 IDE 打包 |

本机核对一致:`C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.1\build.txt` = `IU-252.28539.97`(Ultimate 2025.2),`plugins/` 下**只有 `Groovy`,没有 Grails**。

**要点:Grails 插件与本课题无关。** 它管 GSP、run configuration、taglib/domain 补全。让 Groovy 断点能绑的是 Groovy 插件里的 `GroovyPositionManager`。

### 3.2 关键结构:PositionManager 是可插拔扩展点

IntelliJ 的调试引擎不假设"源文件 = .java",而是把"源码位置 ↔ JVM 类"的映射抽成接口,由语言插件注册实现。以下为本机 `Groovy.jar` 中 `META-INF/plugin.xml` 原文:

```xml
<debugger.positionManagerFactory id="groovyPositionManager"
    implementation="org.jetbrains.plugins.groovy.debugger.GroovyPositionManagerFactory" />
<debugger.positionManagerFactory order="after groovyPositionManager"
    implementation="org.jetbrains.plugins.groovy.springloaded.SpringLoadedPositionManagerFactory" />
<extensionPoint name="positionManagerDelegate" dynamic="true"
    interface="org.jetbrains.plugins.groovy.extensions.debugger.ScriptPositionManagerHelper" />
```

`positionManagerDelegate` 是二级扩展点,Gant 通过 `GantPositionManagerHelper` 接入;GSP 同理(GSP 编成 Groovy 类,行号需再翻一层)。

### 3.3 `GroovyPositionManager` 的三个核心方法

`GroovyPositionManager extends PositionManagerEx`(Apache 2.0)。`javap` 出的方法表与我们缺的三件事一一对应:

**① `getAllClasses(SourcePosition) → List<ReferenceType>`** —— 源码行 → 已加载的 JVM 类。
分三种情况:命名类定义、脚本、**闭包/lambda**。源码注释原文:*closures and lambdas are transformed to a local class extending `groovy.lang.Closure`*。闭包分支走 `findReferenceTypeSourceImage()` 定位外层类,再从 outer 的 nested types 中按位置挑出 `$_xxx_closure1`。

**② `createPrepareRequest(ClassPrepareRequestor, SourcePosition)`** —— 类尚未加载时。
先尝试 `getOuterClassName()`;拿不到则用 `findEnclosingName()`,并以**通配模式 `qName + "$*"`** 注册 `ClassPrepareRequest`。**这是闭包类后加载也能补装断点的关键。**

**③ `getSourcePosition(Location)`** —— 反向,栈帧 → 源码行。
用 Groovy short-names cache 按类名找 PSI 文件,配 `getExtraScriptIfNotFound()` / `addModuleContent()` 做多级 scope 兜底。

配套处理的 Groovy 编译产物(字节码常量池可见):

- `$Trait$Helper` —— trait 编译出的辅助类
- `getRuntimeScriptName()` / `getScriptFQName()` —— 脚本类名与文件名不一致
- `getOriginalQualifiedName()`、`calcLineIndex()`
- `evaluateCondition()` —— 条件断点的 Groovy 表达式求值
- `createStackFrame()` → `GroovyStackFrame` —— 隐藏 `owner` / `thisObject` 等合成变量

完整方法表(本机 `javap -p` 输出,供实现时对照):

```
locationsOfLine(ReferenceType, SourcePosition)
evaluateCondition(EvaluationContext, StackFrameProxyImpl, Location, String)
createStackFrame(StackFrameDescriptorImpl)
isInGroovyFile(Location)
findReferenceTypeSourceImage(SourcePosition)
getEnclosingPsiForElement(PsiElement)
findEnclosingTypeDefinition(SourcePosition)
checkGroovyFile(SourcePosition)
createPrepareRequest(ClassPrepareRequestor, SourcePosition)
findEnclosingName(SourcePosition) / getOuterClassName(SourcePosition)
getClassNameForJvm(PsiClass)
getScriptQualifiedName(SourcePosition)
getSourcePosition(Location)
calcLineIndex(Location) / getPsiFileByLocation(Project, Location)
getExtraScriptIfNotFound(Project, ReferenceType, String, GlobalSearchScope)
addModuleContent(GlobalSearchScope)
getOriginalQualifiedName(ReferenceType, String)
getAllClasses(SourcePosition)
getScriptFQName(GroovyFile) / getRuntimeScriptName(GroovyFile)
```

---

## 4. 核心洞察:attach 模式不需要解析 Groovy

IntelliJ 用 PSI/AST 算类名,是因为它还要支持**未启动 JVM 时**的能力:断点预校验、gutter 图标、无运行时的表达式类型推断。

**我们只要 attach 模式的行断点,可以直接把 JVM 当权威**,因为:

- 一个类是否拥有某源码行,`ReferenceType.locationsOfLine(n)` 的返回值就是答案,**这是权威的**;
- 闭包合成类 `Foo$_bar_closure1` 的 `sourceName()` **同样是** `Foo.groovy`,并且自己持有那几行的 `LineNumberTable`;
- 因此闭包问题**自动消解**,不需要任何 AST 分析。

算法:

```
setBreakpoints(file = "Foo.groovy", line = 42)

  pkg     ← 文件头 `package` 行,一条正则,不需要解析器
  prefix  ← pkg + "." + 文件基名           例:com.example.FooController

  已加载:vm.allClasses()
            filter name == prefix || name.startsWith(prefix + "$")   ← 先按名字筛,便宜
            filter sourceName() == "Foo.groovy"                       ← 再确认
            → locationsOfLine(42) 非空则 createBreakpointRequest(loc)

  未加载:createClassPrepareRequest().addClassFilter(prefix + "*")
            → ClassPrepareEvent 到达时重跑上面那步
```

**为什么先按 `name()` 筛**:Grails 应用加载数万个类,对每个调 `sourceName()` 是一场 JDWP 往返风暴。`allClasses()` 返回时名字已在本地,先筛名字再对小集合调 `sourceName()`。

**可行性的既有证据**:`jdb -attach 5005` 后 `stop at com.example.FooService:42` 能正常工作 —— 说明 Groovy 默认就产出 `SourceFile` 属性和 `LineNumberTable`,信息是齐的,缺的只是"谁来算类名"。

---

## 5. 半个已有的好消息:反向映射本来就是通的

`launch.json` 里的 `sourcePaths` 管的是**反向**查找:JVM 给出栈帧 → 找回源文件显示。这条路不经过 JDT 索引。

因此:**"从 Groovy 行主动发起暂停"不行,但只要用别的方式停下来,单步、调用栈、变量展开、悬停求值在 `.groovy` 文件上都是正常的。**

本仓库已写入 `.vscode/launch.json`(两个 attach 配置,端口 5005,含 `sourcePaths` 覆盖 `grails-app/{controllers,services,domain,init,utils}` 与 `src/main/{groovy,java}`)。

DAP server 做出来之前的临时手段:

- **异常断点** —— 按异常类名匹配,不需要源文件映射,对 Groovy 完全有效。勾选 `Caught Exceptions`,在目标行插 `try { throw new RuntimeException("BP") } catch (ignored) {}`,即可就地挂起并查看局部变量,继续执行后逻辑不受影响。
- **jdb** —— `stop at` 由人手写 FQN,绕开整个解析环节,100% 能装上。闭包用合成类名:`stop in 'com.example.FooController$_listItems_closure1'.doCall`。
- **IntelliJ attach 同一个 5005** —— 启动方式完全不用改,Groovy 断点全功能可用。

---

## 6. 方案

### 6.1 落在哪里

**不新建仓库,就在本仓库做。**

```
C:\Users\gaole\Documents\work\dc\dc-grails-vs
  package.json  name: "grails-gradle-extension", publisher: "Lei Gao", version 0.0.1
                contributes.commands 199 条
  extension.js  394 行;4 条显式命令 + genericCommands[76] + gradleTasks[119]
                = 199,与 package.json 完全对齐(2026-08-30 核对,零缺口零重复)
  git           7 commits, HEAD = a45b0d5 "update package.json"
```

命名决策(2026-08-29,owner 拍板):**保留 `dc-grails-vs`,不改名**。

曾评估并否决的备选:`dc-vscode-grails-plugin`(VSCode 术语是 extension,且 "Grails plugin" 在 Grails 生态里是另一个概念)、`dc-vscode-grails-extension`(`vscode-` 前缀已蕴含 extension,后缀冗余)、`dc-vscode-grails` / `dc-groovy-debug`(名字更准,但改名收益不抵成本,且会与既有目录名产生两个近似仓库的混淆风险)。

**扩展整体是 Grails 专属的**(`create-controller`、`create-taglib`、一长串 `dbm-*`),名字带 grails 正当;**只有 DAP server 是 Groovy 通用的**(对 Gradle 脚本、Spock、Jenkinsfile 一样管用)。因此靠目录结构隔离,而不是靠仓库名:

```
dc-grails-vs/
  extension.js          ← Grails 命令 + DebugAdapterDescriptorFactory
  server/               ← 纯 Groovy 的 DAP server,不出现任何 Grails 概念
      → groovy-dap.jar
  dist/groovy-dap.jar   ← 打进 vsix
```

`server/` 保持对 Grails 零依赖,将来真需要独立发布可零重构抽成 `dc-groovy-debug`。**现在不为这个可能性提前拆仓库。**

### 6.2 现状:`grails.debug` 正好卡在这个点上

`extension.js:86-124` 当前实现:

```js
gradleDebugProcess = exec(`${gradleCmd} bootRun --debug-jvm`, { cwd: workspaceFolder });
// ...
setTimeout(async () => {
  const debugConfig = { name:'Attach to Grails', type:'java', request:'attach',
                        hostName:'localhost', port:5005 };
  await vscode.debug.startDebugging(undefined, debugConfig);
}, 3000);
```

`type: 'java'` 把活转交给 `vscode-java-debug`,于是**原封不动继承了 §2 的限制**。能一键起服务、能自动 attach,唯独断点是空心的,原因就在这一行。

### 6.3 要加什么

`package.json` 注册新的 debug type:

```json
"contributes": {
  "debuggers": [{
    "type": "groovy",
    "label": "Groovy (JDI)",
    "configurationAttributes": {
      "attach": {
        "required": ["port"],
        "properties": {
          "hostName": { "type": "string", "default": "localhost" },
          "port":     { "type": "number", "default": 5005 },
          "sourcePaths": { "type": "array", "items": { "type": "string" } }
        }
      }
    }
  }]
}
```

然后把 `grails.debug` 里的 `type:'java'` 改成 `type:'groovy'` 即切换过去。

**硬约束:DAP server 不能用 JS 写。** JDI(`com.sun.jdi`)是 Java API,只存在于 JVM 上。extension 侧用 `DebugAdapterDescriptorFactory` 返回 `vscode.DebugAdapterExecutable('java', ['-jar', jarPath])` 把它拉起来 —— 这是 VSCode 的标准做法(`vscode-java-debug` 自己也是如此,只是它的 server 寄生在 jdt.ls 进程里)。

注意 `jdk.jdi` 模块不在默认根模块集合中,编译与运行都需要 `--add-modules jdk.jdi`(或在 jar 的构建里配置模块路径)。

---

## 7. 分档计划

### T0 — 可行性验证(半天)

**这一步性价比最高:成立则后面纯工程量;不成立就别做了。**

一个约 200 行的独立 Java 程序(源码见 §11 附录),验证 §4 的算法在真实 Grails 应用上成立。

**验收标准:**

1. attach 到 `./gradlew bootRun --debug-jvm` 的 5005;
2. 在某个 controller/service 的**普通方法行**上装上断点并命中,能打印局部变量与调用栈;
3. 在某个**闭包体内的行**上装上断点并命中,`HIT` 行显示的类名形如 `com.example.FooController$_listItems_closure1`;
4. 对**尚未加载**的类,`ClassPrepareRequest` 的 `prefix + "*"` 通配能在类加载后补装成功;
5. 行号与源码实际对得上(重点验证 Grails 的 AST 变换 —— GORM/artefact 增强 —— 是否扰乱了 `LineNumberTable`)。

**若 T0 失败**,大概率是第 5 条(AST 变换导致行号错位)。那种情况下 §4 的捷径不成立,必须回到 IntelliJ 那种基于源码结构的做法,工作量翻数倍 —— 届时应重新评估是否继续。

### 7.0 T0 实测结果(2026-08-30)

**§11 的 spike 源码已编译验证**:JDK 17.0.11 + `javac --add-modules jdk.jdi`,零报错。唯一改动:
每次命中的 `System.in.read()` 换成自动 resume + 命中预算(`--max-hits`)+ 空闲超时(`--timeout`),
否则无法脚本化驱动。

靶子是一个自建的独立 Groovy 程序(`com.spike.SpikeTarget`,groovy 4.0.32 编译),
以 `-agentlib:jdwp=...,suspend=y` 启动。

| # | 验收项 | 结果 |
|---|---|---|
| 1 | attach | **通过** |
| 2 | 普通方法行 | **通过** —— `BOUND SpikeTarget.groovy:16 -> com.spike.SpikeTarget.plainMethod` 并命中 |
| 3 | 闭包体内的行 | **通过** —— 命中 `SpikeTarget$_plainMethod_closure1.doCall`;嵌套闭包命中 `SpikeTarget$_plainMethod_closure2$_closure3.doCall` |
| 4 | 类尚未加载时补装 | **通过** —— attach 时匹配 0 个类,全靠 `prefix + "*"` 的 `ClassPrepareRequest` 在类加载后补装 |
| 5 | 行号准确性(Grails AST 变换) | **通过** —— 空白 Grails 7.2.3 应用实机复验,行号逐行精确,详见 §7.2 |

**算法本身按预期工作**:命中前 spike 打印
`. com.spike.SpikeTarget$_plainMethod_closure2 matches source but owns none of [22]` ——
`sourceName()` 相同但 `locationsOfLine()` 为空的类被正确排除,无需任何 AST 分析。

**一条比预期好的结论**:`allClasses()` 返回 2143 个类耗时 **9 ms**。§4 担心的"JDWP 往返风暴"
不存在 —— 类名随 `allClasses()` 一次性带回本地,先按 `name()` 筛的策略成本可忽略。

**两条比预期坏的结论**(都落在 T1 的坑里,且比原描述更具体):

1. **闭包捕获的局部变量显示为 `groovy.lang.Reference`**,不是原值
   (`doubled = instance of groovy.lang.Reference(id=1867)`)。T1 坑 ② 不只是"过滤合成变量",
   还必须**解包 Reference** 才能显示有意义的值。
2. **调用栈噪声有两种不同形态**:普通方法帧被 invokedynamic 的
   `LambdaForm$MH` / `IndyInterface.fromCache` 淹没;闭包帧则是
   `NativeMethodAccessorImpl` / `CachedMethod.invoke` / `ClosureMetaClass.invokeMethod` 一串。
   T1 坑 ③ 的 step filter 要分别处理这两条调用路径。

**第 5 条**:静态旁证见 §7.1,**实机复验见 §7.2(已通过)**。

### 7.1 Grails AST 变换与行号(静态核验)

`@Transactional` + `@WithoutTenant` 会把方法体搬到另一个方法,形成三层,**只有最内层带 `LineNumberTable`**:

| 方法 | LineNumberTable |
|---|---|
| `init()` —— public 包装 | 无 |
| `$mt__init()` —— 多租户包装 | 无 |
| `$tt__init(TransactionStatus)` —— 真正的方法体 | **有,行号与源码逐行吻合** |

闭包同理:`Foo$__tt__bar_closure2`、嵌套的 `Foo$__tt__bar_closure4$_closure6` 都带正确行号,
且 `SourceFile` 属性一律是外层的 `Foo.groovy`。

**这不破坏 §4 的算法** —— `locationsOfLine(n)` 是按 `ReferenceType` 查、横跨该类所有方法的,
自然会在 `$tt__init` 里命中;算法根本不需要知道 `__tt__` / `__mt__` 的存在。

**但带来一条原设计没写的限制:方法签名行装不上断点。** `def init() {` 那一行的字节码在没有
行号表的包装方法里。IDE 用户习惯在方法签名行下断点,实现时要么在 UI 上明确报"不可绑定",
要么做"向下吸附到第一条可执行行"的兜底。这一条要写进 T1 的验收。

> §7.2 的实机复验确认了以上全部结论,并补充了一条更要紧的:**签名行装不上断点不是 AST 变换
> 的锅** —— `@NotTransactional` 的普通方法一样没有签名行的行号项,纯 Groovy 项目同样如此。

### 7.2 T0 第 5 条实机复验(2026-08-30)—— **通过**

靶子是**自建的空白 Grails 7.2.3 应用**(`latest.grails.org` forge 生成的 rest_api 骨架 +
一个 class 级 `@Transactional` 的 service + 一个 domain,JDK 17,H2),不借任何业务仓库。
service 源码(行号即下表所指):

```groovy
 6  @Transactional
 7  class SpikeService {
 9      Map readOnlyMethod(int seed) {
10          int base = seed * 2
11          List<Integer> nums = (1..5).collect { it * base }
12          int total = 0
13          nums.each { n ->
14              total += n
15          }
16          return [base: base, total: total]
17      }
19      Map transactionalMethod(int seed) {
20          int base = seed + 100
21          List<Integer> doubled = []
22          (1..3).each { i ->
23              int inner = i * base
24              [1, 2].each { j ->
25                  doubled << (inner + j)
26              }
27          }
28          long widgets = Widget.count()
29          return [base: base, doubled: doubled, widgets: widgets]
30      }
32      @NotTransactional
33      int plainMethod(int seed) {
34          int x = seed * 3
35          return x + 1
36      }
37  }
```

`bootRun` 带 `-agentlib:jdwp=...,suspend=y`(并 `-Dspring.devtools.restart.enabled=false`),
spike attach 后 resume,一次 `GET /spike` 触发三个方法。结果:

| 请求行 | 内容 | 绑定到 | 命中次数 | 报告行号 |
|---|---|---|---|---|
| 19 | 方法签名行 | **无任何类拥有该行** | — | — |
| 20 | `int base = seed + 100` | `$tt__transactionalMethod` bci 20 **与** bci 79 | 2 | **20** ✔ |
| 22/23 | `(1..3).each { i ->` / 闭包体 | `SpikeService$__tt__transactionalMethod_closure5.doCall` | 3 = `(1..3)` | **23** ✔ |
| 25 | 嵌套闭包体 | `...closure5$_closure6.doCall` | 6 = 3×2 | **25** ✔ |
| 28 | `Widget.count()`(GORM) | `$tt__transactionalMethod` bci 155 | 1 | **28** ✔ |
| 11 | `(1..5).collect { ... }` | 外层 `$tt__readOnlyMethod` bci 95 **和** `closure3.doCall` bci 5 | 1 + 5 | **11** ✔ |
| 14 | `total += n` | `closure4.doCall` | 5 | **14** ✔ |
| 33 | 方法签名行(`@NotTransactional`) | **无任何类拥有该行** | — | — |
| 34 | `int x = seed * 3` | `plainMethod` bci 26 与 bci 73 | 1(**bci 73**) | **34** ✔ |

**行号逐行精确,闭包(含嵌套、含被 AST 变换重命名进 `__tt__` 的)全部自动落位,
`sourceName()` 一律是 `SpikeService.groovy`。§4 的算法在真实 Grails 上成立 —— 生死线通过。**

同时,attach 时 `allClasses()` 386 个类里匹配到 **0 个**,所有断点都是靠 `prefix + "*"` 的
`ClassPrepareRequest` 在类加载后补装的 —— 验收第 4 条在 Grails 环境下再次成立。

#### 两条实机才暴露的新结论(T1 必须处理)

**① 一个源码行会绑定到同一方法内的多个 location,且"每行只取第一个 location"是错的。**

`javap -c` 给出原因:Groovy 4 为每条语句生成**双代码路径** —— callsite/metaclass 慢路径与
primitive 快路径,运行时由
`BytecodeInterface8.isOrigInt() && !__$stMC && !BytecodeInterface8.disabledStandardMetaClass()`
选一条,**两条挂着同一个行号**。实测:

- 行 20 → bci 20(分派判断本身)与 bci 79(快路径体),**一次调用两处都经过**,连停两次;
- 行 34 → bci 26(慢路径)与 bci 73(快路径),**只命中 bci 73**,慢路径从未执行。

所以:**location 必须全装**(否则像行 34 那样装在慢路径上会永远不响),**去重必须放在命中侧**
——同一 thread、同一 frame、同一行的第二次停顿丢弃。注意 `plainMethod` 是 `@NotTransactional`,
这是 Groovy 编译器本身的行为,**与 Grails 无关,纯 Groovy 项目一样会踩**。

**② `Foo$_bar_closure1` 不一定是用户闭包。**

`@Transactional` 变换生成的事务回调也占用这个命名 —— `SpikeService$_readOnlyMethod_closure1`
的唯一方法是 `doCall(TransactionStatus)`,**没有 LineNumberTable**;用户写的闭包被搬进了
`SpikeService$__tt__readOnlyMethod_closure3/4`、`$__tt__transactionalMethod_closure5$_closure6`。
§4 的算法靠 `locationsOfLine()` 为空把它自动排除掉了(spike 日志里的
`matches source but owns none of [...]`),**不需要认名字**;但任何想靠类名做启发式的实现
都会在这里翻车。

另外 `SpikeService$readOnlyMethod`、`SpikeService$transactionalMethod$0`、
`SpikeService$plainMethod$1` 这类类**没有 source info**(`AbsentInformationException`),
install 时必须 try/catch,不能假设匹配前缀的类都能问出 `sourceName()`。

#### 复现方式

spike 源码见 §11(附录已同步加上 `loc.codeIndex()` —— 它是上面①的关键证据)。
靶子与驱动脚本跑在临时目录,未进仓库。要重跑:forge 拉一个空白 rest_api 应用,
贴上上面那份 service,`./gradlew bootRun -PdbgPort=5007`(bootRun 里加
`jvmArgs "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5007"` 与
`systemProperty 'spring.devtools.restart.enabled', 'false'`),等 `Listening for transport`
出现后跑 spike。

### T1 — 能用(1~2 周)

最小 DAP server。约 15 个请求:

```
initialize / attach / setBreakpoints / configurationDone / threads /
stackTrace / scopes / variables / continue / next / stepIn / stepOut /
pause / disconnect / evaluate(可选)
```

5 类事件:`initialized` / `stopped` / `continued` / `thread` / `terminated` / `output`。

**真正吃时间的不是协议,是三个"不写不知道"的坑:**

1. **JDI EventQueue 循环与 suspend policy 的竞态** —— `EventSet` 的 resume 语义、多线程同时命中、`vm.resume()` 与 `eventSet.resume()` 的区别。
2. **变量面板要过滤 Groovy 合成变量** —— `owner`、`thisObject`、`this$0`、`$callSiteArray`、`$staticClassInfo` 等。IntelliJ 的 `GroovyStackFrame` 干的正是这件事,可直接对照。
3. **单步会掉进 Groovy 运行时** —— `CallSiteArray`、`ScriptBytecodeAdapter`、`DefaultTypeTransformation` 等。必须配 stepping filter,否则 step over 一步能走进十几帧。参照 `GroovyDebuggerClassFilterProvider`。
4. **一行多 location,必须在命中侧去重** —— Groovy 的双代码路径让同一行有多个 bci,全装才不会漏(§7.2 ①),但会导致一次调用连停两次。去重键是 thread + frame + line。

**T1 验收里要补的两条**(均来自 §7.1 / §7.2):

- **方法签名行**要么明确报"不可绑定",要么"向下吸附到第一条可执行行"。
- **变量面板要解包 `groovy.lang.Reference`**(被闭包捕获的局部变量都是这个形态),不只是过滤合成变量。

### 7.3 T1 实现现状(2026-08-30)

`server/` 已建,Java 17,**零第三方依赖**,产物 `dist/groovy-dap.jar` 约 44 KB。

```
server/
  build.gradle                     jar 直接落到 ../dist/,vsix 从那里取
  src/main/java/org/groovydap/
    Main.java                      stdio 入口。把 System.out 换成 stderr ——
                                   stdout 是协议通道,一条 println 就能把客户端搞错位
    DebugSession.java              DAP 请求分发 + JDI 事件循环
    dap/Json.java                  够用的 JSON 读写(不引 Gson:vsix 体积)
    dap/DapTransport.java          Content-Length 分帧,按字节读,send 加锁
    jdi/SourceRef.java             文件 → 类名前缀 + 「这行有没有可执行代码」
    jdi/BreakpointBinder.java      §4 算法
    jdi/StopDeduper.java           §7.2 ① 的命中侧去重
    jdi/Variables.java             Reference 解包 / 合成变量过滤 / 装箱值展开
    jdi/SourceLocator.java         栈帧 → 源文件(反向映射,本来就是通的)
```

**已实现的 DAP 请求**:`initialize` / `attach` / `setBreakpoints` /
`setExceptionBreakpoints`(空实现)/ `configurationDone` / `threads` / `stackTrace` /
`scopes` / `variables` / `continue` / `next` / `stepIn` / `stepOut` / `pause` /
`disconnect` / `terminate`。事件:`initialized` / `stopped` / `continued` /
`breakpoint` / `terminated` / `output`。

**实机验证**(空白 Grails 7.2.3 应用 + 一个照着 VSCode 说话的 DAP 客户端脚本,非编辑器):

| 验的东西 | 结果 |
|---|---|
| attach + `configurationDone` 放行 `suspend=y` 的目标 | 通过 |
| 方法签名行 19 / 33 自动下滑到 20 / 34 | 通过,响应里带 `message` 说明移动原因 |
| 闭包行 23、嵌套闭包行 25 在类加载后补装并发 `breakpoint changed` | 通过 |
| 一次调用只停一次(§7.2 ① 的去重) | 通过 |
| 栈帧回映射到 `.groovy` 文件 | 通过 |
| `groovy.lang.Reference` 解包 + 装箱值显示 | 通过(`base = 0` 而非 `Integer (id=…)`) |

停顿序列 20 → 23 → 25 → 25 → 23 → 25 → 25 → 23,与源码循环结构完全吻合。

**去重踩到的一个坑**(已修):两条源码行 snap 到同一行时,同一 location 上会有**两个**
`BreakpointRequest`,JDI 把它们放进**同一个 `EventSet`**。原先的去重器在抑制时把记录删了,
于是同一个 set 里的第二个事件又被当成新停顿放行 —— 行 20 停了两次。现在(a)一个 `EventSet`
只认第一个断点事件,(b)抑制时**保留**记录:下一次调用会在**同一个 bci** 重新进入,这正是
区分「同一次调用的后半段」和「新的一次调用」的依据。

**extension 侧接线**:`package.json` 加了 `contributes.breakpoints`(language `groovy`,
**没有这一条 VSCode 根本不允许在 .groovy 上打断点**)、`contributes.debuggers`(type `groovy`)
和两个设置(`grails.debug.adapter` 默认 `groovy`,`grails.debug.javaPath`);`extension.js`
注册 `DebugAdapterDescriptorFactory`,用 `java --add-modules jdk.jdi -jar dist/groovy-dap.jar`
拉起,并用 `DebugConfigurationProvider` 自动填 `sourcePaths`。

**还没做的 T1 余项**:

1. **编辑器里没跑过** —— 上面全部是脚本驱动的验证。Extension Development Host 里的实际
   体验(断点图标、变量面板渲染、单步手感)未验。
2. **单步没有实机验证** —— `next`/`stepIn`/`stepOut` 已实现并带 step filter
   (`org.codehaus.groovy.*`、`groovy.lang.*`、`java.lang.invoke.*`、反射系列),但只经过编译,
   没在 Grails 上跑过。§7 T1 坑 ③ 说的两条不同调用路径(invokedynamic vs metaclass)是否都被
   filter 盖住,要实测。
3. `evaluate` / 条件断点 —— 属 T2。
4. 多 `EventSet` 并发命中(多线程同时停)的行为未验。

### T2 — 好用(再 2~4 周)

- **条件断点** —— 需在目标 VM 内求值 Groovy 表达式。最省事的路子是把表达式编成闭包后在目标 VM 里 `invokeMethod`。**这是最大的一块。**
- watch / evaluate 面板
- 异常断点
- hot swap
- launch(非 attach)模式
- GSP 行号映射(需要 JSR-45 SMAP)

到 T2 就是在做产品了,要长期跟 Groovy/Grails 版本与 DAP 演进。

---

## 8. 备选路线:改 `microsoft/java-debug` 而非从零写

`ISourceLookUpProvider` 是干净的接口,非 deprecated 的那个方法签名就是这件事本身:

```java
JavaBreakpointLocation[] getBreakpointLocations(String sourceUri, SourceBreakpoint[] sourceBreakpoints)
        throws DebugException;
```

[scalacenter/scala-debug-adapter](https://github.com/scalacenter/scala-debug-adapter) 正是这么做的 —— README 明说 *based on and extends microsoft/java-debug*。Scala 的问题与 Groovy 同构(lambda 编成合成类、源文件名 ≠ 类名),他们真正的重活是**表达式编译器和 step filter**,不是断点绑定。这是很好的规模参照。[zed-industries 也 fork 了 java-debug](https://github.com/zed-industries/java-debug),说明这条路走得通。

| | 自写 DAP server | fork java-debug |
|---|---|---|
| 省掉 | — | DAP 协议层、变量渲染、stepping 基础设施 |
| 多出 | — | fork 两个仓库(`java-debug` + `vscode-java-debug`)、自己发扩展、跟上游同步、与 `redhat.java` 共存(它是 provider 宿主) |
| 控制力 | 完全 | 受上游结构约束 |

**建议**:T0 走自写(独立、无依赖、结论清晰);T0 通过后在 T1 开始前重新比较一次 —— 那时对工作量的判断会准得多。

---

## 9. 现有 extension 的两个已知缺陷

与断点无关,但都会咬人,建议顺手修:

**① `setTimeout(..., 3000)` 是竞态**(`extension.js:108`)
冷启动时 Gradle 光配置+编译就远超 3 秒,JVM 那时还没开始监听,attach 直接失败。可靠做法是盯 stdout,等 `Listening for transport dt_socket at address: 5005` 出现再 attach。

**② `exec` 应换成 `spawn`**(`extension.js:99`)
`child_process.exec` 会把全部输出攒在内存,`maxBuffer` 默认 1 MB,超限直接**杀掉子进程**。Grails 的日志几分钟就能顶破 1 MB —— 服务会莫名其妙死掉,且报错很难指向这里。`spawn` 没有这个缓冲区。

**③ 环境变量不透传**(次要)
很多 Grails 项目用一个包装脚本(`run_dev.sh` 之类,通常就是 `exec ./gradlew bootRun "$@"`)来
export 应用需要的环境变量。扩展直接 `exec gradlew` 会把这些变量全丢掉,应用起来后行为与手工
启动不一致。应支持配置:①自定义启动命令(让用户指向自己的包装脚本),或②在设置里声明
要注入的 env。

---

## 10. 决策记录与未决问题

### 已决

| 决策 | 结论 | 日期 |
|---|---|---|
| 是否新建仓库 | **否**,用既有 `dc-grails-vs` | 2026-08-29 |
| 仓库名 | **保留 `dc-grails-vs`**,不改名 | 2026-08-29 |
| DAP server 语言 | **Java**。JDI 是 Java API,JS 不可选;Kotlin/Groovy 均否决,理由见下 | 2026-08-30 |
| 是否发布 Marketplace | **是**。因此 `publisher` 必须换成注册的 id | 2026-08-30 |
| server 与 Grails 的关系 | `server/` 对 Grails 零依赖,靠目录隔离而非拆仓库 | 2026-08-29 |
| T0 前是否建 `server/` | **否**,spike 先在临时目录跑通再进仓库 | 2026-08-29 |
| T0 第 5 条(行号准确性) | **通过**。空白 Grails 7.2.3 应用实机复验,行号逐行精确,§4 算法成立 | 2026-08-30 |
| 自写 DAP 还是 fork java-debug | **自写**。第 5 条通过后不再需要 Groovy 解析,剩下的就是协议本身;可控、无上游包袱、对 Grails 零依赖便于将来抽走 | 2026-08-30 |

### 未决

1. ~~T0 第 5 条(行号准确性)~~ —— **已通过**(2026-08-30,见 §7.2)。生死线解除,可以开 `server/`。
2. ~~T1 用 Java 还是 Kotlin~~ —— **已决:Java**(2026-08-30)。
3. ~~走自写 DAP 还是 fork java-debug~~ —— **已决:自写**(2026-08-30)。第 5 条通过后 §4 的捷径
   成立,不需要 Groovy 解析器,剩下的工作量就是 T1 那 15 个请求 + 4 个坑;fork java-debug 要在
   别人的架构里塞 position manager,还要长期跟上游同步,而它的 FQN 解析本来就绑死在 JDT 上。
   §8 的备选路线就此归档。
4. ~~是否发布到 Marketplace~~ —— **已决:发布**(2026-08-30)。遗留待办:`publisher` 现值
   `"Lei Gao"` 含空格,不是合法 publisher id,必须换成注册的 id。仓库名与 marketplace id 是
   两回事,`package.json` 的 `"name": "grails-gradle-extension"` 才是发布标识。
5. 条件断点的表达式求值方案(T2)未设计。
6. GSP 调试是否要做?需要 SMAP,且要先确认 Grails 7 的 GSP 编译是否产出 SMAP。

### 为什么 server 用 Java 而不是 Kotlin / Groovy

1. **自举陷阱(决定性)** —— 我们造的就是"Groovy 断点绑不上"的解药。server 自己用 Groovy 写,
   它出 bug 时没法调,只能退回 `println`。用 Java 写,server 可以直接被现成的
   `vscode-java-debug` 调,断点全功能可用。
2. **启动延迟每次 F5 都要付** —— server 是 `DebugAdapterExecutable` 每个调试会话拉起一次的
   进程,Groovy runtime 初始化约 200–400 ms;Java 是即时的。
3. **vsix 体积** —— 已决定发 Marketplace,Groovy 运行时约 7 MB 要打进包;Java 版只需 JDI
   (JDK 自带,零第三方依赖)。
4. **JDI 是重 interface + 事件分发的 Java API** —— Groovy 下要么退化成动态分发,要么全程
   `@CompileStatic`,那就是在用 Groovy 语法写 Java。Kotlin 则是给一个 JS 扩展额外引入一整套
   工具链,收益不抵成本。
5. **若改走 fork `microsoft/java-debug`**(§8),那边是 Java 代码库,混语言没意义。

唯一真正需要 Groovy 的地方是 **T2 的条件断点** —— 把表达式编成字节码推到目标 VM 里执行。
但那是**调用 Groovy 编译器 API**,Java 侧加个 `groovy-core` 依赖即可,与 server 用什么语言写无关。

---

## 11. 附录:T0 spike 源码

放在临时目录跑,**不要**先提交进 `dc-grails-vs`。

```bash
java --add-modules jdk.jdi GroovyBpSpike.java localhost:5005 \
     grails-app/controllers/com/example/FooController.groovy 42
```

(JDK 11+ 的单文件源码启动模式;本机 JDK 为 17.0.11。)

```java
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * T0 spike: can we bind a breakpoint on a .groovy line WITHOUT parsing Groovy?
 *
 * Thesis: in attach mode the JVM itself is the oracle. A class owns a source line
 * iff ReferenceType.locationsOfLine(n) is non-empty, and closures report the same
 * sourceName() as their enclosing class.
 */
public class GroovyBpSpike {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: GroovyBpSpike <host:port> <path/to/File.groovy> <line> [<line> ...]");
            System.exit(2);
        }
        int colon = args[0].lastIndexOf(':');
        String host = args[0].substring(0, colon);
        String port = args[0].substring(colon + 1);

        Path src = Paths.get(args[1]);
        String fileName = src.getFileName().toString();
        String base = fileName.replaceFirst("\\.(groovy|gvy)$", "");
        String pkg = readPackage(src);
        String prefix = pkg.isEmpty() ? base : pkg + "." + base;

        List<Integer> lines = new ArrayList<>();
        for (int i = 2; i < args.length; i++) lines.add(Integer.parseInt(args[i]));

        System.out.printf("[spike] file=%s  package=%s  prefix=%s  lines=%s%n",
                fileName, pkg.isEmpty() ? "<default>" : pkg, prefix, lines);

        VirtualMachine vm = attach(host, port);
        System.out.printf("[spike] attached: %s %s%n", vm.name(), vm.version());
        EventRequestManager erm = vm.eventRequestManager();

        // (1) classes that will be loaded later -- the wildcard trick
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.addClassFilter(prefix + "*");
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();
        System.out.println("[spike] class-prepare filter: " + prefix + "*");

        // (2) classes already loaded. Filter on name() first -- calling sourceName()
        //     on all ~50k classes of a Grails app would be a JDWP round trip storm.
        int found = 0, installed = 0;
        for (ReferenceType rt : vm.allClasses()) {
            if (!matches(rt, prefix)) continue;
            found++;
            installed += install(erm, rt, fileName, lines);
        }
        System.out.printf("[spike] already-loaded classes matching prefix: %d, breakpoints installed: %d%n",
                found, installed);
        if (found == 0) System.out.println("[spike] (target still starting up? class-prepare will catch it)");

        vm.resume();
        System.out.println("[spike] resumed, waiting for a hit... (Ctrl-C to quit)\n");
        loop(vm, erm, prefix, fileName, lines);
    }

    /** com.example.Foo plus every synthetic nested class Groovy generates for it. */
    static boolean matches(ReferenceType rt, String prefix) {
        String n = rt.name();
        return n.equals(prefix) || n.startsWith(prefix + "$");
    }

    static int install(EventRequestManager erm, ReferenceType rt, String fileName, List<Integer> lines) {
        String sn;
        try {
            sn = rt.sourceName();
        } catch (AbsentInformationException e) {
            System.out.printf("  ~ %s has no source info, skipped%n", rt.name());
            return 0;
        }
        if (!fileName.equals(sn)) return 0;

        int n = 0;
        for (int line : lines) {
            List<Location> locs;
            try {
                locs = rt.locationsOfLine(line);
            } catch (AbsentInformationException e) {
                continue;
            }
            for (Location loc : locs) {
                BreakpointRequest bp = erm.createBreakpointRequest(loc);
                bp.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                bp.enable();
                System.out.printf("  + BOUND %s:%d -> %s.%s%n",
                        fileName, line, rt.name(), loc.method().name());
                n++;
            }
        }
        return n;
    }

    static void loop(VirtualMachine vm, EventRequestManager erm, String prefix,
                     String fileName, List<Integer> lines) throws Exception {
        EventQueue queue = vm.eventQueue();
        while (true) {
            EventSet set = queue.remove();
            boolean resume = true;
            for (Event ev : set) {
                if (ev instanceof ClassPrepareEvent cpe) {
                    ReferenceType rt = cpe.referenceType();
                    if (matches(rt, prefix)) {
                        System.out.println("[spike] class prepared: " + rt.name());
                        install(erm, rt, fileName, lines);
                    }
                } else if (ev instanceof BreakpointEvent be) {
                    resume = false;
                    report(be);
                } else if (ev instanceof VMDeathEvent || ev instanceof VMDisconnectEvent) {
                    System.out.println("[spike] target VM gone");
                    return;
                }
            }
            if (resume) {
                set.resume();
            } else {
                System.out.print("[spike] suspended -- press ENTER to continue: ");
                System.in.read();
                set.resume();
            }
        }
    }

    static void report(BreakpointEvent be) {
        Location loc = be.location();
        // codeIndex() 是必须的:同一行会有多个 bci(§7.2 ①),不打出来就分不清
        // 命中的是哪条代码路径。
        System.out.printf("%n=== HIT  %s.%s  line %d  (bci %d) ===%n",
                loc.declaringType().name(), loc.method().name(),
                loc.lineNumber(), loc.codeIndex());
        try {
            StackFrame frame = be.thread().frame(0);
            List<LocalVariable> vars = frame.visibleVariables();
            if (vars.isEmpty()) {
                System.out.println("   (no visible locals)");
            } else {
                Map<LocalVariable, Value> values = frame.getValues(vars);
                for (LocalVariable v : vars) {
                    System.out.printf("   %-28s %-18s = %s%n",
                            v.typeName(), v.name(), abbreviate(values.get(v)));
                }
            }
            System.out.println("   --- stack ---");
            int i = 0;
            for (StackFrame sf : be.thread().frames()) {
                if (i++ >= 12) { System.out.println("   ..."); break; }
                Location l = sf.location();
                System.out.printf("   at %s.%s(%s:%d)%n",
                        l.declaringType().name(), l.method().name(), safeSource(l), l.lineNumber());
            }
        } catch (Exception e) {
            System.out.println("   <frame read failed: " + e + ">");
        }
        System.out.println();
    }

    static String abbreviate(Value v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    static String safeSource(Location l) {
        try {
            return l.sourceName();
        } catch (AbsentInformationException e) {
            return "?";
        }
    }

    static String readPackage(Path p) throws java.io.IOException {
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    static VirtualMachine attach(String host, String port) throws Exception {
        AttachingConnector conn = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(c.name())) { conn = c; break; }
        }
        if (conn == null) throw new IllegalStateException("SocketAttach connector not available");
        Map<String, Connector.Argument> a = conn.defaultArguments();
        a.get("hostname").setValue(host);
        a.get("port").setValue(port);
        return conn.attach(a);
    }
}
```

> 此源码**未经编译验证**(本 session 未跑通编译),另一 session 开工时应先 `javac --add-modules jdk.jdi` 过一遍。

---

## 12. Grails 应用侧调试环境速查

以下是"被调试的那个 Grails 应用"需要做的事,与本仓库无关,但实现和测试时天天用到。

```bash
# 带调试端口启动(默认 5005,suspend=y,会等 attach)
./gradlew bootRun --debug-jvm

# 调测试
./gradlew test --tests com.example.FooSpec --debug-jvm
```

- 用 `./gradlew`,不要用 `./grailsw`。
- `--debug-jvm` 作用于 fork 出的**应用 JVM**,不是 Gradle daemon。
- 默认 `suspend=y`,JVM 会停在启动前等 attach。不想每次被挡住,可在项目的
  `bootRun.jvmArgs` 里加
  `'-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005'`。
  反过来,`suspend=y` 对调试**启动期代码**(BootStrap、init service)是刚需。
- **devtools 会打断调试** —— 若项目依赖 `spring-boot-devtools`,classpath 一变就 restart,
  新 classloader 会让已挂断点失效、需重新 attach。

  **关不掉的坑(2026-08-30 实测)**:环境变量 `SPRING_DEVTOOLS_RESTART_ENABLED=false`
  **无效**。实测日志里 devtools 照常初始化:

  ```
  [  restartedMain] o.s.b.devtools.restart.ChangeableUrls  : The Class-Path manifest ...
  [  restartedMain] .e.DevToolsPropertyDefaultsPostProcessor : Devtools property defaults active!
  ```

  线程名仍是 `restartedMain`(restart classloader 已生效)。原因是 restart 的开关在
  `Environment` 建好之前就被读走了,走的是 **System property**,不吃 relaxed binding、
  也不吃环境变量。必须让 `-Dspring.devtools.restart.enabled=false` 落到**被 fork 出的应用
  JVM** 上——注意 `./gradlew -D...` 只会传给 Gradle daemon,传不到应用 JVM。可行的两条:

  ```groovy
  // build.gradle
  bootRun { systemProperty 'spring.devtools.restart.enabled', 'false' }
  ```

  或干脆在调试时把 devtools 移出 runtime classpath。

- **JDWP 的 "Listening" 行可能出现不止一次** —— `server=y` 的 agent 在调试器断开后会重新
  监听并**再打印一遍**。实测捕获到:

  ```
  ERROR: transport error 202: recv error: Connection reset by peer
  Listening for transport dt_socket at address: 5005
  ```

  所以"盯 stdout 等这行再 attach"的实现必须**只认第一次**(加 attached 守卫),否则一次
  断开会触发第二个调试会话。
- **`JAVA_HOME` 必须有效** —— 无效时 `gradlew` 直接以
  `ERROR: JAVA_HOME is set to an invalid directory` 失败,且报错不指向真正原因。
  (本机 2026-08-30 实测踩过:`JAVA_HOME` 被设成两段路径粘连的不存在目录,
  而 PATH 上的 `java` 是好的,所以 `javac`/`java` 单独用毫无异常,只有 `gradlew` 炸。)

---

## 13. 参考

- [microsoft/java-debug](https://github.com/microsoft/java-debug) · [ISourceLookUpProvider.java](https://raw.githubusercontent.com/microsoft/java-debug/main/com.microsoft.java.debug.core/src/main/java/com/microsoft/java/debug/core/adapter/ISourceLookUpProvider.java)
- [scalacenter/scala-debug-adapter](https://github.com/scalacenter/scala-debug-adapter) · [zed-industries/java-debug](https://github.com/zed-industries/java-debug)
- [GroovyPositionManager.java](https://raw.githubusercontent.com/JetBrains/intellij-community/master/plugins/groovy/src/org/jetbrains/plugins/groovy/debugger/GroovyPositionManager.java)(Apache 2.0)
- [apache/grails-intellij-plugin](https://github.com/apache/grails-intellij-plugin) · [Marketplace](https://plugins.jetbrains.com/plugin/18504-apache-grails)
- [IntelliJ PositionManager API](https://dploeger.github.io/intellij-api-doc/com/intellij/debugger/PositionManager.html)
- [Debug Adapter Protocol 规范](https://microsoft.github.io/debug-adapter-protocol/)
- [microsoft/vscode#190364](https://github.com/microsoft/vscode/issues/190364) · [redhat-developer/vscode-java#205](https://github.com/redhat-developer/vscode-java/issues/205)
</content>
</invoke>

# 让 AI 也能用 —— 在 `server/` 之上加 MCP(stdio)入口的设计

> 状态:**设计稿,未实现,未动工(T3.1 起需用户明确说开始)**。
> 日期:2026-10-11
> 范围:只有设计。T3.0(传输层接缝)与 T3.4(测试)已提交(见 §9);本文的 MCP 层本身仍未实现。
> 标注约定:外部 API / 行为的每条论断都标 **已验证(附 URL)** 或 **未验证 / 假设**,不互相升级。
> 「已验证的代码事实」指读 `server/` 源码得来,行号是设计时的,会漂移。

---

## 0. 目标

让扩展对 AI 也可用:在现有 `server/` DAP 服务之上加 MCP(stdio)入口,AI 可以 attach 到运行中的
Grails/Groovy JVM、在 `.groovy` 设断点、触发请求、读栈和变量、单步。**人和 AI 共用 `server/`**。

---

## 1. 已验证的代码事实(来自 `server/` 源码)

| 事实 | 位置 | 含义 |
|---|---|---|
| `DebugSession` 原本只有 `(InputStream, OutputStream)` 构造器、自己持有 `DapTransport`;**现在**(dc32b37)另有 `DebugSession(DapChannel)` 构造器,`DapChannel` 是接口(`receive` / `send` + 默认 `sendResponse` / `sendError` / `sendEvent`),`DapTransport` 是它的 stdio 实现 | `DebugSession.java:131-135`、`dap/DapChannel.java`(提交后行号,设计时的 128-170 已过期) | 接缝已在,进程内回环不必再改 `DebugSession`;MCP 回环客户端本身(T3.1)仍未写 |
| stdin EOF 结束 `run()`,调用 `detach()`:清断点、`vm.resume()`、`vm.dispose()` | `131-146`、`831-848` | 「AI 中途消失」的安全网 |
| 断点 / 异常断点 / 单步全是 `SUSPEND_ALL` | `DebugSession.java:444,674`;`BreakpointBinder.java:152,251` | 一停,整个 JVM(含 Tomcat)不再服务请求;全代码**没有**挂起超时 / 看门狗 |
| `hostName` 不受限,默认 `localhost` | `DebugSession.java:198` | 远程 attach 没有任何拦截 |
| 不在目标里执行代码:`Variables` 刻意不调 `toString()`(会逼 resume)。**更新(817b3dd)**:adapter 现在有只读的变量路径 `evaluate`,以及只接受字面量的 `setVariable` / `setExpression`(基本类型与 `String`),引擎不 `invoke*` / `newInstance`;一般表达式求值仍被拒 | — | 给人用的这些已在 adapter 里;**MCP 层 v1 按设计仍只读**,AI 的写权限须显式确认后再开 |
| `Json.write` 转义换行 / 控制字符;变量输出已限 200 数组元素 / 200 map 条目 | — | 适合 MCP stdio「一行一条」 |
| jar 现在 76649 字节、38 个条目(2026-10-11 重测;设计时是 55 KB / 27 个条目,`CLAUDE.md` 的 44 KB 更早已过期);手头的旧 vsix 是 83.7 KB(9 月构建,**未重测**);`engines.vscode` 是 `^1.63.0`;设置沿用 `grails.run.*` / `grails.debug.adapter` / `grails.debug.javaPath` | — | 体积预算见 §3 |

---

## 2. 外部 API 核查表

### 已验证

| 论断 | 来源 |
|---|---|
| `registerMcpServerDefinitionProvider(id, provider)` 存在,需在 `contributes.mcpServerDefinitionProviders` 声明 `[{id,label}]` | <https://code.visualstudio.com/api/extension-guides/ai/mcp> 、<https://code.visualstudio.com/api/references/vscode-api> |
| `McpStdioServerDefinition` 字段 `label/command/args/cwd/env/version`(**字段来自示例而非正式签名**) | 同上 |
| `provideMcpServerDefinitions` / `resolveMcpServerDefinition`;后者返回 `undefined` 表示不启动 | 同上 |
| 无 `readOnlyHint` 的工具调用会弹确认 | 同上 |
| 「扩展可发布 MCP server 集合」出现在 VS Code 1.101 发布说明 | <https://code.visualstudio.com/updates/v1_101> |
| `vscode.lm.registerTool` 自 1.95 定稿,需在 `languageModelTools` 声明 | <https://code.visualstudio.com/updates/v1_95> 、<https://code.visualstudio.com/api/extension-guides/ai/tools> |
| MCP stdio 传输:一行一条 JSON;stdout 只放 MCP 消息;stderr 放日志;客户端关 stdin 即关闭 | <https://modelcontextprotocol.io/specification/2025-06-18/basic/transports> |
| `tools/list`、`tools/call`、`isError`、`structuredContent`、`annotations` | <https://modelcontextprotocol.io/specification/2025-06-18/server/tools> |
| Java MCP SDK 需 Java 17+,带 Jackson 3 / Reactor / SLF4J,明显破坏「零依赖」 | <https://github.com/modelcontextprotocol/java-sdk> |
| Claude Code:`claude mcp add --transport stdio --scope project NAME -- cmd args...`,`--` 必需,支持 `--env`;输出 10k token 警告、默认 25k 上限;`MCP_TIMEOUT` / `MCP_TOOL_TIMEOUT` | <https://code.claude.com/docs/en/mcp> |

### 未验证 / 假设

| 论断 | 状态 |
|---|---|
| 该 API 的**确切**最低 `engines.vscode` | 只能从发布说明推断 ≥ 1.101 |
| 是否需要 Copilot / 订阅 | 未验证 |
| Cursor 等 fork 是否支持 | 论坛有人请求支持(<https://forum.cursor.com/t/support-vs-codes-register-mcp-server-definition-provider-api/133031>,**只读了标题**);推断大概率没有 |
| `lm.registerTool` 的消费方是否仅 Copilot | 未验证 |
| 比 2025-06-18 更新的 MCP 版本 | 未验证;server 须回显客户端 `protocolVersion` |
| TS MCP SDK 的体积与依赖 | 未验证 |
| Java SDK 的传递依赖与 jar 体积 | 未验证 |
| 旧版 VS Code(1.63)是否忽略 `mcpServerDefinitionProviders` 贡献点 | 未验证 |

**结论**:`engines.vscode` 保持 `^1.63.0`,运行时 feature-detect
`vscode.lm && vscode.lm.registerMcpServerDefinitionProvider`。

---

## 3. 架构方案对比与结论

| 方案 | 做法 | 评价 |
|---|---|---|
| **A1(推荐)** | 同一 jar 加 `--mcp` 入口,手写 MCP JSON-RPC(复用现有 `Json`),进程内与**未改动的** `DebugSession` 通过**阻塞队列**回环 | 约 600–800 行,零依赖,复用已在 7/8 验证过的逻辑(`server/` 主源码现约 3561 行,设计时数的是约 2756 行);vsix 约 +14 KB(按 20 B/行估算,**假设**) |
| A2 | 把 `DebugSession` 拆成核心 + DAP 适配器 + MCP 适配器 | 更好;`server/` 现在有 `verifyProtocol`(44 项)兜底,但覆盖不含 stepIn / stepOut、异常断点、晚绑定路径,**未重新评估**是否值得现在做 |
| B | 扩展里用 Node 包装并说 DAP | 两套运行时,vsix 变大,独立使用需 Node,最差 |
| C | 经 `vscode.debug.activeDebugSession.customRequest` 驱动**人的**会话 | 只限 VS Code;作为「共享会话」模式放第二阶段 |

**最终:A1 为主,C 以后加。**

A1 不用 `PipedInputStream` / `PipedOutputStream`:writer 线程退出会抛 `Write end dead` / `Read end dead`,
`jdi-events` 线程会在 VM 死亡时退出 —— 这是**假设**(按 JDK 已知行为,未在本项目里测)。

---

## 4. MCP 工具(第一版 9 个)

服务名 `groovy-debug`,一个 MCP 进程一个会话;已 attach 时再 attach 到别处报错;`stopId` 单调递增。

| 工具 | 入参 → 出参 | 备注 |
|---|---|---|
| `attach` | `{port?=5005, host?="localhost", sourcePaths?[], breakpoints?:[{file,lines[]}], resume?=true}` → `{attached,vm,port,running}` | `initialize` → `attach` → 可选 `setBreakpoints` → `configurationDone`;断点**必须**在 `configurationDone` 之前设,否则 `suspend=y` 的启动期断点会漏 |
| `set_breakpoints` | `{file(绝对路径), lines[]}` → `{breakpoints:[{requestedLine,line,verified,message?}]}` | 按文件替换,`[]` 清除 |
| `wait_for_stop` | `{timeoutMs?=30000(上限 55000), afterStopId?}` → `{status:"stopped"\|"running_timeout"\|"terminated"\|"auto_resumed", stopId, threadId, reason, breakpointIds, top:{file,line,frame}}` | 超时是正常返回,让 AI 重调 |
| `stack` | `{threadId?, maxFrames?=20}` → `frames[{index,name,file,line,inProject}]` | 只读 |
| `variables` | `{frameIndex?=0, scope?="locals"\|"grails", path?:string[], depth?=1, maxItems?=50}` → `{vars,truncated,redacted}` | **按路径寻址**(DAP 句柄一 resume 就失效);复用 `Variables.children`(`Reference` 解包、数组 / map 上限、Grails scope) |
| `continue` | `{stopId?, waitMs?=0}` | |
| `step` | `{kind:"over"\|"in"\|"out", stopId?, waitMs?=5000}` | |
| `detach` | `{}` | 幂等 |
| `status` | `{}` → attached、state、当前 stop、断点、`suspendedForMs` | 只读 |
| `trigger_http` | (**用户已定要做**)限本机(loopback)、**异步**:立即返回请求 id,结果经 `wait_for_stop` / `status` 取 | 同步 curl 会被已停住的线程卡住,AI 必须后台发请求再 `wait_for_stop`;**须写进 server instructions 和 `wait_for_stop` 描述** |

以后:`threads`、`set_exception_breakpoints` / `exception_info`、`pause`、`evaluate`(adapter 里只读的变量路径版已有,MCP 层尚未开放)和条件断点 / 一般表达式求值(T2,须显式确认)。

### 设计要点

- 阻塞的 stopped 事件 → 入环形缓冲;`wait_for_stop` 阻塞至多 55 s,超时正常返回 `running_timeout`(不是错误)。
- 响应 `notifications/cancelled` 提前解除阻塞 —— **客户端是否支持:假设**。
- `step` / `continue` 带 `waitMs`,一次拿到下一个停点。
- 幂等:`attach` 同目标返回现有会话;`set_breakpoints` 按文件替换;`wait_for_stop(afterStopId)` 立即返回已记录的更新停点;`continue` / `step` 带 `stopId` 过期则报 stale-stop;`detach` 幂等。
- 错误用 `isError:true` 并附一行修复提示。
- 多线程:停点排队,`threadId` 默认最近停住的线程;v1 不支持多并发 attach。
- 只读工具(`status` / `stack` / `variables` / `wait_for_stop`)标 `readOnlyHint:true`,变更类不标(VS Code 才会弹确认)。`annotations` 只是**不可信提示**,控制由 server 自己强制。

---

## 5. 安全模型

| 风险 | 控制 |
|---|---|
| 远程 JVM 附加(JDWP 无认证,等同 RCE) | MCP 层强制仅 `localhost` / `127.0.0.1` / `::1`;设置 `grails.debug.mcp.allowRemoteHosts` 默认 `false` |
| 任意端口 | `--allow-port` / `grails.debug.mcp.allowedPorts`,默认 `[5005]` |
| 默认开启 | **默认关闭**:`grails.debug.mcp.enabled` 默认 `false`;关闭时 `resolveMcpServerDefinition` 返回 `undefined`;独立使用时用户自己 `claude mcp add` 即为开启 |
| JVM 被停住(`SUSPEND_ALL` 且无看门狗) | 看门狗。原设计 `maxSuspendSeconds` 默认 120、硬上限 10 分钟;**用户 2026-10-11 指出「用户莫名其妙被卡住体验很差」,已改为:默认约 30 秒(可配置,待实测定值)**,到点自动 `continue`,记录 `auto_resumed`,下一次调用告知 AI |
| AI / MCP 客户端中途断开 | stdin EOF 已走 `detach()`(**已验证**,见 §1);再加 SIGTERM shutdown hook;SIGKILL 依赖 JDWP agent 在调试连接断开时恢复 VM —— **假设,须测** |
| 变量里的密钥(`params` / `request` / `session` 正是 token / cookie 所在) | 按变量名 / map 键名与取值形态脱敏:正则如 `(?i)pass(word)?\|secret\|token\|api[-_]?key\|authorization\|cookie\|credential\|session[-_]?id`,加 JWT / `Bearer ` 形态检查,用户可扩展(`grails.debug.mcp.redactPatterns`)。**尽力而为,不是保证**;响应里报告 `redacted` 计数。Grails scope 里 map 条目名是否可检视:**假设,须测** |
| 体积 / token | 单值 200 字符、单响应约 16 KB、`depth ≤ 3`、`maxItems ≤ 200` |
| 提示注入(请求数据来自 HTTP) | 输出标注「不可信数据」;instructions 里写明不得执行值里的文本 |
| AI 在目标里执行代码 | MCP 层 v1 不开放 `evaluate` / `setVariable` / 方法调用(adapter 里已有只读路径 evaluate 与字面量 setVariable,供人使用);AI 的写权限与一般表达式求值须显式确认 |
| `pause`、共享 / 类生产 JVM | `pause` 不进第一片;整 JVM 冻结影响他人,看门狗限界并写进文档 |

### 「不卡」五条(用户确认的要求;第一版要求,T3.1 之后实现)

1. MCP 模式只挂起**命中线程**(`SUSPEND_EVENT_THREAD`),其它请求照常处理。**会改变已验证行为**(其它线程可能改共享状态,变量可能前后不一致),只在 MCP 模式开启,并**必须重跑 7/8 的 S1–S10 矩阵**。
2. 更短更可见的看门狗(约 30 s)。
3. VS Code 状态栏显示「AI 已暂停应用」并带「立即继续」按钮。
4. 默认关闭。
5. 同一时间只允许一个暂停,限制 AI 连续设一堆断点把应用卡死。

---

## 6. 扩展集成

- `contributes.mcpServerDefinitionProviders`:`[{id:"grails.groovyDebugMcp", label:"Groovy Debug (Grails)"}]`。
- `activate` 里 feature-detect 后注册,返回 `McpStdioServerDefinition`:`command` 用现有 `javaExecutable()`,`args` 为 `['--add-modules','jdk.jdi','-jar',jar,'--mcp','--allow-port',...,'--workspace',folder]`,`cwd` = 工作区,`version` 随设置变化以便重启;`onDidChangeConfiguration` 触发变更事件;复用 `makeAdapterFactory` 的 jar 存在性检查。
- 不推荐 v1 用 `lm.registerTool`:重复 MCP 面、DAP 逻辑要进扩展宿主、消费方未验证。

### 设置(沿用 `grails.debug.*`,不放任何密钥)

| 设置 | 默认 |
|---|---|
| `mcp.enabled` | `false` |
| `mcp.allowedPorts` | `[5005]` |
| `mcp.allowRemoteHosts` | `false` |
| `mcp.maxSuspendSeconds` | 30(待实测) |
| `mcp.valueMaxChars` | 200 |
| `mcp.redactPatterns` | 见 §5 |

### 独立使用(Claude Code)

```bash
claude mcp add --transport stdio --scope project groovy-debug -- \
  java --add-modules jdk.jdi -jar <abs>/groovy-dap.jar --mcp --allow-port 5005
```

**用户已定:jar 走扩展安装路径**(带版本号,更新会变),因此加命令 **「Grails: Copy MCP Setup Command」**
把命令复制到剪贴板,不写文件。

### 与人的调试会话共存

- HotSpot JDWP 一次只服务一个调试器(**部分证据**:`CLAUDE.md` 记录 `server=y` agent 在调试器断开后才再次打印 `Listening`)。
- `grails.debug` / `grails.runApp` 的自动 attach(`extension.js:979-1040`)会先占端口,AI 的 attach 则失败或超时,**错误信息须点明此原因**。
- `bootRun --debug-jvm` 是 `suspend=y`,JVM 在有人 attach 并 resume 之前一直冻着。
- **用户已定:第一版「人或 AI 二选一」**(AI 自己后台起 `bootRun --debug-jvm` 并读 `dt_socket` 行);**人和 AI 共用同一 JVM 要做,放第二阶段,走方案 C**。
- 可考虑给现有自动 attach 加开关让出端口(**待定**)。

---

## 7. 验证计划

现状:`server/` 已有无依赖的 `verifyProtocol`(c851735,接进 `./gradlew build`;817b3dd 之后共 44 项),其中含 2026-10-11 两个修复(37b3c5d `org.apache.juli` 排除、8f2e72d `InconsistentDebugInfoException` 逐变量跳过)的回归守卫。**MCP 层自己的检查还不存在**,下面是它要补的。

### 最小无依赖测试

纯 `main()` 测试类 + `JavaExec` 任务 `verifyProtocol`,不用 JUnit、不下载。

- **单元部分**:`Json` 往返(含换行 / Unicode);MCP 一行一条且 stdout 无杂输出;脱敏与大小封顶;看门狗(假时钟);`stopId` 过期保护;`StopDeduper` / `SourceRef`。
- **端到端,无需 Grails**:测试起一个纯 JDK 小目标 `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0`(ephemeral 端口:**假设,须在 JDK 21 验证**),走回环通道跑 attach → 断点 → stack → variables → step → continue → detach,不占 5005。

### 真实靶子

`dbgtarget`(Grails 7.2.3)和 `dbgtarget8`(Grails 8.0.0,另含 `/nested`),各 `./gradlew bootRun --debug-jvm`(JDWP 5005),脚本化 MCP 客户端(换行 JSON 管道):

1. `initialize`、`tools/list`。
2. `attach` + 断点(含闭包行、签名行)。
3. 后台 curl 触发,`wait_for_stop` / `stack` / `variables`(`Reference` 已解包;planted 的 `password` / `Authorization` 被脱敏)。
4. `step` over / in / out、`continue`、`detach`(curl 回 200,应用继续服务)。
5. 7 与 8 的 file:line 序列须一致。**不要断言命中次数** —— `CLAUDE.md` 已记录 7 与 8 的命中次数比较有混淆。

### 断开 / 看门狗

- 停在断点后 SIGTERM 与 SIGKILL 杀 MCP 进程(后者验证「JDWP 自动恢复」的假设)。
- 停住后空闲,验证看门狗触发且 `status` 报 `auto_resumed`。
- VS Code 会话占着端口时,`attach` 报清晰错误;第二次 `attach` 同端口幂等。

### 仍未验证

Cursor 等 fork 的行为;VS Code agent 模式是否真的列出扩展注册的 server(需手工确认);各客户端的 `cancelled` 处理。

---

## 8. 风险与已定 / 未定问题

### 风险

- 脱敏是启发式的。
- 请求数据不可信,可携带提示注入。
- 整 JVM 冻结影响他人。
- 各客户端工具超时不同,`wait_for_stop` 55 s 上限刻意保守。
- Grails 8 的 `@Transactional` 回调帧显示行 0(推测纯外观,**未验证**)可能迷惑 AI。

### 已定(用户,2026-10-11)

| # | 决定 |
|---|---|
| 1 | 人 / AI 共用 JVM:**要**,第二阶段(方案 C) |
| 2 | 「不卡」:五条见 §5(体验差的担忧成立) |
| 3 | `trigger_http`:**做**,限本机、异步 |
| 4 | jar:走扩展安装路径 + 复制命令按钮 |
| 5 | 同日已修 `scripts/build-server.js` 吞错误(f0ce465) |
| 6 | **本次只做设计**:若改动过大就先只设计,T3.1 起不开工,除非用户明确说开始 |

### 未定

- 目标是否**只做独立 MCP 客户端**(Claude Code 等)而不做 VS Code agent 模式的扩展注册。建议先做前者,工作量小很多 —— **用户未明确答复**。
- jar 是否另作 release 资产:用户答「好」,按扩展路径,未再细问。

---

## 9. 任务拆分(T 编号接现有文档 T0–T2)

| 编号 | 内容 | 文件 | 状态 |
|---|---|---|---|
| T3.0 | 传输层接缝(`DapChannel` 接口或带传输参数的构造器),行为不变,回归 7/8 | `dap/DapTransport.java`、`DebugSession.java` | **已完成**(dc32b37,新增 `dap/DapChannel.java`;7/8 回归 S1–S10 的结果以 `CLAUDE.md` 为准,本文未核) |
| T3.1 | MCP 核心:换行 JSON-RPC 循环、`initialize` / `ping`、`tools/list`、`tools/call` 工作池、取消、版本回显、阻塞队列回环 DAP 客户端、`--mcp` 入口 | `mcp/McpServer.java`、`mcp/DapClient.java`、`Main.java` | 未动工 |
| T3.2 | 九个(含 `trigger_http`)工具、`stopId` 队列、按路径 `variables` | `mcp/Tools.java`、`mcp/StopQueue.java` | 未动工 |
| T3.3 | 安全层与「不卡」五条(含 `SUSPEND_EVENT_THREAD` 模式及 7/8 矩阵重跑) | `mcp/Safety.java`、`mcp/Redactor.java` | 未动工 |
| T3.4 | 测试(`verifyProtocol`) | `server/build.gradle`、`server/src/test/java` | **已完成**(c851735;817b3dd 加了 evaluate / 写值的检查;MCP 层的检查属 T3.1 起) |
| T3.5 | 扩展集成;状态栏提示 + 「立即继续」按钮;复制 MCP 配置命令 | `extension.js`、`package.json`、`README`、`CLAUDE.md` | 未动工 |
| T3.6 | 真实靶子验证(本文新增章节、`docs/experiments/`) | — | 未动工 |
| T3.7 | 以后:`threads`、异常断点、`pause`、方案 C 共享会话 | — | 未动工 |

已完成:`scripts/build-server.js` 报错修复(2026-10-11,f0ce465)。

---

## 10. 参考

- <https://code.visualstudio.com/api/extension-guides/ai/mcp>
- <https://code.visualstudio.com/api/references/vscode-api>
- <https://code.visualstudio.com/updates/v1_101>
- <https://code.visualstudio.com/updates/v1_95>
- <https://code.visualstudio.com/api/extension-guides/ai/tools>
- <https://modelcontextprotocol.io/specification/2025-06-18/basic/transports>
- <https://modelcontextprotocol.io/specification/2025-06-18/server/tools>
- <https://github.com/modelcontextprotocol/java-sdk>
- <https://code.claude.com/docs/en/mcp>
- <https://forum.cursor.com/t/support-vs-codes-register-mcp-server-definition-provider-api/133031>(只读了标题)
- 同目录 `2026-08-29-vscode-groovy-debug-adapter.md`(DAP server 的根因与实现现状)

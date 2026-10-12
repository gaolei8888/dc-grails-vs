# step over 从行中段发起 —— 证伪实验(2026-08-31)

设计文档 §7.3 记着一条缺陷与结论:

> 在**一行的中段**创建的 `STEP_OVER` 请求会冲出整个方法体,而在**行首**创建的正常。

**这条结论是错的。** 本目录的 harness 在 JDK 21 上把「行中段」单独隔离出来测,
6 种形状全部正确走到下一行。行中段本身不是原因。

## 跑法

```bash
G=$(find ~/.gradle/caches -name 'groovy-4*.jar' | grep -v sources | head -1)
javac -g:none Mid.java
javac Target.java
java -cp "$G" org.codehaus.groovy.tools.FileSystemCompiler -cp . -d . *.groovy
javac --add-modules jdk.jdi Repro.java Repro2.java Repro4.java

java --add-modules jdk.jdi -cp . Repro  linestart .          # 对照:行首发起
java --add-modules jdk.jdi -cp . Repro  midline  .           # 纯 Java 行中段
java --add-modules jdk.jdi -cp . Repro2 GTarget ".:$G" 3 ""  # Groovy,无 filter
java --add-modules jdk.jdi -cp . Repro2 GTarget ".:$G" 3 "$EX"   # Groovy + filter
java --add-modules jdk.jdi -cp . Repro3 RTarget ".:$G" 3 "$EX"   # 反射调用方(Repro2 改 action)
java --add-modules jdk.jdi -cp . Repro4 TTarget ".:$G" 3 "$EX"   # 无行号中间帧 + restep 链
```

`EX` 用 `DebugSession.DEFAULT_STEP_EXCLUDES` 的前半段(`java.*` … `org.objectweb.asm.*`)。

## 结果

| # | 靶子形状 | 落点 | 下一次 STEP_OVER |
|---|---|---|---|
| 1 | 纯 Java,`callee()` 返回落行中段 | `Target.main:7 bci 5`(行 7 = bci 2–7) | → `line 8` 正确 |
| 2 | 对照:断点下在行首 bci 2 | `Target.main:7 bci 2` | → `line 8` 正确 |
| 3 | Groovy 4 indy callsite,无 filter | `GTarget.main:7 bci 11`(行 7 = bci 4–22) | → `line 8` 正确 |
| 4 | Groovy + 整张 exclusion filter | 同上(13 个事件压成 1) | → `line 8` 正确 |
| 5 | 反射调用方(native 帧,depth 33) | `RTarget.action:7 bci 11` | → `line 8` 正确 |
| 6 | 中间夹无行号帧(`Mid` 用 `-g:none`)+ 模拟 server 的 restep 链 | `TTarget.action:7 bci 20`,经 `Mid.wrap line -1` 续步回来 | → `line 8` 正确 |

第 6 组是形状上最接近真实失败场景的:`@Transactional` 合成回调同样是「用户源文件里、无行号表」的帧,
harness 里 `Repro4` 连 `DebugSession.onStepDone` 的判定(`line < 0 || excluded` → 深了就 `STEP_OUT`,
否则用用户那次的 depth)都照抄了。

## 于是被证伪的假设(连同 §7.3 原有两条,共三条)

1. ~~JDI class exclusion filter 导致~~(§7.3 已证伪)
2. ~~`addCountFilter(1)` 与 Step 修饰符冲突~~(§7.3 已证伪)
3. ~~**行中段的 bci**~~ —— 本目录证伪。另外顺带排除:Groovy indy callsite、反射/native 调用方、
   深栈(33 帧)、无行号中间帧、restep 链本身。

## 真实 Grails 靶子上的复现尝试(2026-09-01)—— 也没复现

靶子:`~/Documents/work/dc/dc-grails-vs-target/dbgtarget`,forge 生成的空白 Grails **7.2.3**
rest_api(`javaVersion=JDK_17`),加了两个文件:

- `ExampleService.groovy` —— 类级 `@Transactional`,`transactionalMethod` 里有个
  `(1..3).each { }`;另有一个 `@NotTransactional` 的 `plainMethod`
- `ExampleController.groovy` —— `index()` 第 10 行调 `transactionalMethod`,第 11、12 行在后面

`javap -c -l` 出来的 `index` 行号表,形状与文档 §7.3 那次一致:

```
line 8:  59
line 9:  63
line 10: 86      <- 事务方法调用;返回落在 86..114 的中段(§7.3 那次是 index:9 bci 104)
line 11: 115
line 11: 164     <- 同一行两段字节码(callsite 慢路径 + primitive 快路径)
line 12: 172
```

**第 11、12 行确实在 `index` 方法里** —— 所以「那两行的字节码在别的方法」这个猜测,对这个形状不成立。

驱动方式:`dapclient.js`(本目录,DAP over stdio,VSCode 干的那套)对
`gradlew bootRun --debug-jvm` 起的 JVM attach。

| 跑法 | adapter | JVM | 结果 |
|---|---|---|---|
| 断点 `ExampleService:25`(`$tt__` 方法末行),连续 next | 当前 HEAD | JDK 21.0.10 | `index:10 bci 103`(中段)→ **11 → 12**,正确 |
| 同上 | 当前 HEAD | JDK 25.0.3 | 同样正确 |
| 同上 | **`15bf219`**(缺陷记录当时那版) | JDK 25.0.3 | **10 → 11 → 12 也正确**;差别只在方法结束之后:旧版会停进 `org.grails.core...ReflectionInvoker:215` 这些框架帧(新版 `d793d59` 按源文件判定,不在项目里就继续走) |
| 断点 `ExampleService:23`(闭包体内),连续 next | 当前 HEAD | JDK 25.0.3 | 闭包内循环重复命中 3 次 → `$tt__:22` → `:25` → `index:10` → **11 → 12**,正确 |

trace 里那条自动续步链与 §7.3 记录的完全同形:

```
$tt__transactionalMethod:25            depth 92
  closure1.doCall line -1 bci 38/40/43/46   depth 77
  transactionalMethod line -1 bci 69/72     depth 61
  transactionalMethod$0.call line -1 bci 20/23  depth 60
index line 10 bci 103                  depth 59   <- 行中段
index line 11 bci 115                  depth 59   <- 没被跳过
index line 12 bci 172                  depth 59
```

**结论:缺陷在当前代码、当前 HEAD 与 `15bf219` 两个 adapter 版本、JDK 21 与 25、
9 种靶子形状(6 个合成 + 3 个真实 Grails)下都复现不出来。** 原始观察的成因仍不明 ——
可能是当时那个靶子的某个细节(controller 形状 / 断点位置 / 那次 `next` 之前的操作序列)没被记下来。

**如果再遇到**:第一件事是带 `trace: true` 把 `step event:` 行完整留下,并且同时记下
`javap -c -l` 里该方法的 LineNumberTable —— 判断「跳过」还是「该行确实结束了」只能靠这两样对齐。

## 下一步(未做)

真实失败只在 Grails 应用里见过,所以下一步必须回到真实靶子(空白 Grails 7.2.3),
带 `trace: true` 复现,并且**先验证一个更朴素的可能**:那两行「被跳过」的代码是不是根本不在
`index` 这个方法里。具体取证:

- 对真实 controller 类 `javap -c -l`,看 `index` 的 LineNumberTable 里到底有没有第 10、11 行,
  有几段;
- JDI 侧 `referenceType.locationsOfLine(10)` / `(11)`,看这两行的 location 落在哪个方法/合成类。

如果第 10、11 行的字节码在别的方法(或另一个合成类)里,那「冲出方法体」就是观察被贴错了标签:
`index` 帧确实结束了,step 没有错。

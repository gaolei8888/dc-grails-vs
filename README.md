# DC-Grails-VS

> **Pre-release.** The debugger works: breakpoints bind in .groovy files, closures
> included, and scripted sessions have been driven end to end against Grails 7.2.3
> and 8.0.0. The main flows have also been seen working in a real VS Code window
> (see Requirements for what has not). But it has been exercised on two
> applications, by one person. Expect to find things. Known gaps are listed at the
> bottom; the issue tracker is
> [on GitHub](https://github.com/gaolei8888/dc-grails-vs/issues).

| | |
|---|---|
| **Marketplace** | [gaolei8888.grails-gradle-extension](https://marketplace.visualstudio.com/items?itemName=gaolei8888.grails-gradle-extension) |
| **Source** | [github.com/gaolei8888/dc-grails-vs](https://github.com/gaolei8888/dc-grails-vs) |
| **Issues** | [github.com/gaolei8888/dc-grails-vs/issues](https://github.com/gaolei8888/dc-grails-vs/issues) |
| **Changelog** | [CHANGELOG.md](https://github.com/gaolei8888/dc-grails-vs/blob/main/CHANGELOG.md) |

Installing from the command line — the `--pre-release` is needed while this is one:

```
code --install-extension gaolei8888.grails-gradle-extension --pre-release
```

Grails and Gradle commands from the command palette and a sidebar tree — and a
debugger that can actually stop on a line of Groovy.

## Breakpoints in `.groovy` files

VSCode does not bind breakpoints in Groovy. The Java debugger resolves "file →
class name" through JDT, and JDT does not index `.groovy`, so the breakpoint is
never registered with the JVM and the dot stays hollow. No setting changes this.

This extension ships its own debug adapter. In attach mode it does not parse
Groovy at all: it asks the running JVM which class owns a line, which is the only
authoritative answer there is. Closures come out for free — a synthetic closure
class reports the same source file as the class that encloses it — and classes
that are not loaded yet are picked up when they load.

What that means in practice:

- Breakpoints bind in controllers, services, domain classes and **inside closures**,
  including nested ones.
- They bind in methods rewritten by Grails' AST transforms — a `@Transactional`
  method whose body has been moved into `$tt__…` still stops on the right line.
- A breakpoint on a line with no bytecode (a method signature, a blank line) slides
  down to the first line that can hold one, and says so.
- Variables captured by a closure are shown as their values, not as the
  `groovy.lang.Reference` the compiler boxed them in.
- **A Grails scope** beside Locals whenever the thread is serving a request, with
  `params`, `request`, `response` and `session`. None of those is a local or a
  field of anything on the stack — they belong to the request, which Spring keeps
  in a thread local — so nothing else shows them.
- Maps show their entries. `params` reads as `controller`, `action`, `id`, not as
  a wrapper around a table and a modCount.
- A domain object shows its own properties. GORM's traits compile a field into
  every domain class they touch, and a class with one property of its own arrives
  carrying half a dozen of them; those are hidden, and `id` and `version` are not.
- Stepping skips the framework: the Groovy runtime, reflection, the transaction
  template and the servlet container are stepped over rather than into.
- **Exception breakpoints**, caught and uncaught, filtered the same way: it stops
  where your code threw, not in the framework. A Grails request throws and catches
  its way through startup and dispatch, and stopping for all of it would be
  unusable.

Exception breakpoints are in the **Breakpoints** section of the Run and Debug
view. The exception's message is read from the object rather than by calling
`getMessage()`, so nothing runs in the application to describe a stop.

Run **Grails: Debug App**. It starts `gradlew bootRun --debug-jvm`, waits for the
JVM to report that it is listening, and attaches. Or write your own attach
configuration:

```json
{
  "type": "groovy-dap",
  "request": "attach",
  "name": "Attach to Grails (Groovy)",
  "hostName": "localhost",
  "port": 5005
}
```

The type is `groovy-dap`, not `groovy`: another Groovy extension may already own
`groovy`, and VS Code allows one debug adapter per type. If you have Code Groovy
installed, its own "Groovy / Grails..." entry in the Run and Debug dropdown is
Code Groovy's, not this extension's.

### Launch configuration (new, not yet verified in a real window)

A **Grails: Debug App** launch configuration (offered in the Run and Debug
dropdown and under Add Configuration; the debugger itself is labelled "Grails
(Groovy debugger)") starts `./gradlew bootRun --debug-jvm` and attaches; F5
with no `launch.json` in a Grails workspace does the same. It attaches instead of
starting a second app if one is already listening, and ending the session stops an
app it started. Launch attributes: `projectDir`, `args`, `env`, `startTimeout`,
`sourcePaths`. The debug port is not configurable, because Gradle hard-codes 5005.
**This has not been checked in a real VS Code window yet**; use Grails: Debug App
from the command palette or status bar, which has.

## Debugging tests

**Grails: Debug Tests** runs `gradlew test --debug-jvm` and attaches to the test
JVM, which is the same machinery the application uses — Gradle forks it with the
same agent on the same port and announces it on the same stream. It offers the
spec in the active editor as the filter, so debugging the failure you are looking
at is one command; leave the box empty to debug the whole run.

## Commands

Every Grails command and Gradle task this project knows about is registered — 201
of them. The ones people reach for are in the **Grails** view in the activity bar,
under Run, Create (empty), Scaffold from domain class, Database Migration and
Gradle; the rest are in the command palette under `Grails:` and `Gradle:`.

Create and Scaffold are not the same thing. `create-controller` writes an empty
controller; `generate-controller` scaffolds one, actions and all, from a domain
class that already exists — which is why every entry under Scaffold asks for one.

Run, Debug and Stop are also in the status bar (shown only in a Grails workspace:
a top-level `grails-app` folder, or a `gradle.properties` / `build.gradle` /
`build.gradle.kts` that mentions "grails"; a Grails app in a subfolder is not
detected). While a build is
running the pair is replaced by a spinner that turns into a check and the address
once the application reports that it is serving; clicking it opens the app, or
shows the build output while it is still starting.

The application's log goes to an output channel — **Grails - Normal** for Run App,
**Grails - Debug** for Debug App — reachable any time with **Grails: Show Output**.

## Requirements

- A Grails project with a Gradle wrapper (`gradlew`) in the folder you open.
- **Grails 7.x or 8.x.** Checked against 7.2.3 and 8.0.0 by scripted debug sessions.
  In a real VS Code window (2026-10-11) the status bar buttons, Debug App,
  breakpoint hits, Locals and Watch values, evaluating a variable path in Watch,
  changing a value, and breakpoint rebinding after a class reload were seen working.
  **Not yet seen in a real window:** the launch configuration, hover, F10/F11 feel,
  JDK discovery from a Dock-launched window, and Windows and Linux paths. A Grails 8
  application itself needs Java 21 or newer.
- **A full JDK, 17 or newer** (not a JRE: the adapter uses the `jdk.jdi` module).
  The JDK your application runs on can be a different one.
- **JDK discovery.** The debug adapter's JDK is searched in this order, and the
  first usable one wins: `grails.debug.javaPath`, `JAVA_HOME` from `grails.run.env`
  or the environment, macOS `java_home`, SDKMAN, the usual install folders
  (Homebrew, Linux, Windows), then `java` on `PATH` (the macOS `/usr/bin/java` stub
  is skipped). A usable JDK is Java 17 or newer with the `jdk.jdi` module. For the
  application's build (`gradlew`) the only check is that `JAVA_HOME`'s `bin/java`
  is executable: a valid `JAVA_HOME` of any version is left alone, and the search
  only fills one in when there is none. The single error naming the ways out
  appears only when there is no usable `java` at all (none found and none on
  `PATH`, or only the macOS stub). A `java` that is on `PATH` but too old or
  without `jdk.jdi` is still handed to the adapter, which then fails to start with
  Java's own message; point `grails.debug.javaPath` at a full JDK 17+. A VS Code
  started from the Dock has no `JAVA_HOME`; set `grails.run.env`
  (`{"JAVA_HOME": "..."}`) or `grails.debug.javaPath`, or start VS Code from a
  terminal with `code .`. Discovery from a Dock-launched window is not yet verified.
- For `grails.debug.adapter: "java"` only: the
  [Debugger for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-debug)
  extension.

## Settings

| Setting | Default | What it does |
|---|---|---|
| `grails.run.command` | `""` | Command line to start the app instead of the Gradle wrapper. For projects that boot through a script which exports the environment the app needs. |
| `grails.run.debugCommand` | `""` | Same, for Debug App. Defaults to `grails.run.command` with `--debug-jvm` appended. |
| `grails.run.env` | `{}` | Environment variables to add for the app process. |
| `grails.run.args` | `[]` | Extra arguments for `bootRun`. |
| `grails.debug.adapter` | `groovy` | `groovy` uses the adapter in this extension. `java` hands the session to vscode-java-debug, which cannot bind a breakpoint in a `.groovy` file. |
| `grails.debug.javaPath` | `""` | The `java` used to run the debug adapter. Defaults to the discovery order above. |

Attach configurations also take `sourcePaths` (roots for resolving stack frames,
defaulted from the project layout), `stepFilters` (packages to step over),
`stepIntoProjectCodeOnly` and `trace`.

## Stopping

Stop reaches the application, not just the build. Gradle forks the app from its
daemon, and the daemon is reused between builds, so the app is only sometimes a
descendant of the process this extension started — killing that process tree
leaves the app running about as often as not, holding the debug port and 8080.
Stop kills the tree and, separately, whatever JVM is listening on the ports the
app announced.

Debug App also checks the debug port before starting. If something already holds
it, it says what, and offers to stop it — because a JVM that cannot open its debug
port produces a build that stops after `:findMainClass` and prints nothing at all,
which is indistinguishable from a hang.

## Known issues

- **Step over once skipped code.** It was reported that after stepping over the last
  line of a service method back into the controller, a further step over skipped
  the remaining code. It could not be reproduced afterwards (several target
  shapes, JDK 21 and 25, Grails 7 and 8), and the cause is unknown.
- **Step into does not enter a `@Transactional` method.** Its entry runs through
  the transaction template, and stepping climbs back out to the calling line. Put a
  breakpoint in the method instead.
- **Watch, hover and the Debug Console evaluate variable paths only** (`a.b.c`,
  `list[0]`, `map['k']`); nothing is run in the application. **Conditional
  breakpoints and general expressions are not implemented.** They need Groovy
  expressions compiled and evaluated inside the target VM.
- **Simple variable values can be changed** in the Variables and Watch panels:
  literals only, for primitives and `String` (not captured closure variables,
  boxed values, objects or collections). Changing a value was seen working in a
  real VS Code window; which request VS Code sends for it is not known.
- **Hot reload needs a recompile.** Saving a source file alone does not change the
  running app. Once the class was reloaded (what triggers that was not
  established), breakpoints rebound to the new class and showed the new value; seen
  in one scenario in a real window.
- **Stop while paused:** if the JVM is stopped at a breakpoint, Stop only ends the
  Gradle client; the application and the adapter stay until you Continue.
- Stopping kills any `java` process listening on the debug port (5005), which
  Gradle hard-codes.
- Code Groovy's own classpath script may print `Cannot mutate the state of
  configuration ':compileOnly'` in its output channel on Grails projects; it is
  harmless and not from this extension.
- GSP files are not mapped.

## Star it

If you use this, clone it, or build something of your own on top of it, please
star the project on [GitHub](https://github.com/gaolei8888/dc-grails-vs). It takes
one click, and it is the main way anyone else finds out this exists.

## License

MIT

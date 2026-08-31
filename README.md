# Grails & Gradle

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
- Stepping skips the framework: the Groovy runtime, reflection, the transaction
  template and the servlet container are stepped over rather than into.

Run **Grails: Debug App**. It starts `gradlew bootRun --debug-jvm`, waits for the
JVM to report that it is listening, and attaches. Or write your own attach
configuration:

```json
{
  "type": "groovy",
  "request": "attach",
  "name": "Attach to Grails",
  "hostName": "localhost",
  "port": 5005
}
```

## Commands

199 Grails and Gradle commands are registered. The ones people reach for are in
the **Grails** view in the activity bar, grouped into Run, Create, Generate,
Database Migration and Gradle; the rest are in the command palette under `Grails:`
and `Gradle:`.

Run, Debug and Stop are also in the status bar, and Stop knows which of the two is
running.

## Requirements

- A Grails project with a Gradle wrapper (`gradlew`) in the folder you open.
- **Java 17 or newer** on `JAVA_HOME` or `PATH`. The debug adapter runs on it; the
  JDK your application runs on can be a different one.
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
| `grails.debug.javaPath` | `""` | The `java` used to run the debug adapter. Defaults to `JAVA_HOME`, then `PATH`. |

Attach configurations also take `sourcePaths` (roots for resolving stack frames,
defaulted from the project layout), `stepFilters` (packages to step over),
`stepIntoProjectCodeOnly` and `trace`.

## Known issues

- **Step over from the middle of a line overshoots.** After a call returns you are
  partway through the line that made it; a step over issued there runs to the end
  of the method instead of moving to the next line. Located, not yet fixed.
- **Step into does not enter a `@Transactional` method.** Its entry runs through
  the transaction template, and stepping climbs back out to the calling line. Put a
  breakpoint in the method instead.
- **Conditional breakpoints, watch expressions and hover evaluation are not
  implemented.** They need Groovy expressions compiled and evaluated inside the
  target VM.
- **Attach only.** There is no launch configuration; the app is started by the
  Gradle wrapper and the adapter attaches to it.
- GSP files are not mapped.

## License

MIT

# Change Log

## [Unreleased]

### Added

- **A debug adapter for Groovy.** Breakpoints now bind in `.groovy` files, which
  the Java debugger cannot do because it resolves class names through JDT and JDT
  does not index Groovy. In attach mode the adapter asks the running JVM which
  class owns a line rather than parsing anything, so closures — nested ones
  included — and methods rewritten by Grails' AST transforms work without special
  handling.
- A breakpoint on a line that carries no bytecode, such as a method signature,
  moves down to the first line that does and reports where it went.
- Variables captured by a closure are shown as their values instead of the
  `groovy.lang.Reference` they are boxed in.
- Stepping skips the Groovy runtime, reflection, Grails' transaction template and
  the servlet container, and stops only in files under the project's source roots.
- A **Grails** view in the activity bar with the commands people reach for,
  grouped, and Run / Debug / Stop in the status bar.
- `grails.run.command`, `grails.run.debugCommand`, `grails.run.env` and
  `grails.run.args`, for projects that boot through a wrapper script rather than
  gradlew directly.

### Fixed

- **All 199 commands ran the wrong thing.** They built `gradlew -Pargs="<name>"`,
  which sets a project property and names no task, so Gradle ran its default task
  and printed help. Gradle tasks are passed as tasks now, and Grails' own commands
  go to `grailsw`, the Grails CLI wrapper that projects carry next to `gradlew` --
  code generation is not a Gradle task at all, and asking Gradle for one answers
  "Command not found for name: create-controller".
- **Debug attached on a three second timer**, which loses to Gradle's configure and
  compile phases on a cold start. It now waits for the JVM to report that it is
  listening, and takes the port from that line.
- **Stop left the application running.** Gradle forks the app into a JVM of its
  own, which kept the debug port and 8080; the whole process tree is stopped now.
- Builds streamed through `exec` were killed once their output passed 1 MB, which a
  Grails log reaches in minutes.
- Dismissing the argument prompt no longer runs the command anyway.

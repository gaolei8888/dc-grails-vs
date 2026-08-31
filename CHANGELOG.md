# Change Log

## 0.1.4 — pre-release

### Changed

- The status bar controls drop their colour while a debug session is running.
  VSCode paints the whole bar with its own debugging colour for the length of a
  session, and coloured text on that background reads worse than plain text, not
  better. Green and red are back as soon as the session ends.
- The README lists the Marketplace, source, issue and changelog links.

## 0.1.3 — pre-release

### Changed

- Named DC-Grails-VS rather than Grails, in the Marketplace listing, the
  extensions list and the activity bar. "Grails" is the framework's name, not
  this extension's.

## 0.1.2 — pre-release

### Added

- **A domain object in the variables pane shows its own properties.** GORM works
  through Groovy traits, and a trait's fields are compiled into every implementing
  class under a mangled name that carries no dollar sign, so the existing filter
  did not catch them: a domain class with one property arrived carrying
  `..._GormValidateable__errors`, `..._GormValidateable__skipValidate` and
  `..._DirtyCheckable__$changedProperties`. `id` and `version` stay, being the
  ones that mean something.
- A null value shows its declared type rather than nothing, so an unsaved `id`
  reads as `java.lang.Long` instead of as `null : null`.
- **Grails: Debug Tests**, which runs `gradlew test --debug-jvm` and attaches to
  the test JVM. Spock specs could not be debugged at all before. The spec in the
  active editor is offered as the `--tests` filter, since a whole run stopping on
  the first breakpoint in a shared helper is not what you want when you are
  looking at one failure.

- **Exception breakpoints**, caught and uncaught, from the Breakpoints section of
  the Run and Debug view. Filtered by the same rule as stepping -- the throw site
  has to be in a file under the project's source roots -- because a Grails request
  throws and catches its way through startup and dispatch, and stopping for every
  one of them is not a debugger anyone can use. The exception's message is read as
  a field rather than by calling getMessage(), since invoking a method in the
  target to describe a stop means resuming it to do so.

## 0.1.1 — pre-release

### Fixed

- An empty console window opened in front of the editor on every run. Sending the
  build's output to a file rather than a pipe means Windows allocates a console
  for it, and spawn does not hide one by default.

## 0.1.0 — pre-release

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
- **Stop left the application running.** Gradle forks the app from its daemon, and
  the daemon is reused between builds -- so the app descends from the process this
  extension started only when that build happened to start a fresh daemon, and
  killing the process tree left it running the rest of the time, holding the debug
  port and 8080. Measured: the app JVM's parent was the daemon, whose own parent
  was a launcher from an earlier build. Stop now kills the tree and, separately,
  whatever JVM holds the ports the app announced.
- **Debug App checks the debug port first.** A JVM that cannot open it produces a
  build that stops after `:findMainClass` and prints nothing, which reads as a
  hang. It now says what holds the port and offers to stop it.
- Builds streamed through `exec` were killed once their output passed 1 MB, which a
  Grails log reaches in minutes.
- Dismissing the argument prompt no longer runs the command anyway.

# Testing the extension

Latest local verification (2026-09-06, 0.1.18): **15 unit tests and 4 real-editor
tests passed** on Windows / VS Code 1.136.1 / Grails 7.2.3. The editor suite passed
with both Java 25.0.4.1 and Java 17.0.11; the final run used Java 17.0.11 and took
16 seconds for the four editor tests, excluding application/editor startup.
The server build and local VSIX packaging succeeded. Lint reported no errors and
the six existing unused catch-variable warnings described below.

Install development dependencies with `pnpm install`. Build the adapter with
`npm run build:server` using a valid `JAVA_HOME` pointing at JDK 17 or newer.
The Java compiler emits Java 17 bytecode.

## Debug adapter checks

`cd server && ./gradlew verifyProtocol` runs the adapter's own dependency-free
checks (also part of `./gradlew build`): JSON and DAP framing, step excludes,
source mapping, stop de-duplication, variable rendering (including a local the VM
cannot read yet), path evaluation (including operator characters inside quoted
keys), variable writes (and a check that the only code ever run in the target is
`valueOf` on the eight JDK box classes), and an end-to-end half that drives a real
`DebugSession` against a plain Java target over a loopback channel
(breakpoint / step / continue event order, conditions, hit counts and logpoints,
`setVariable`, disconnect and hang-up). 2026-10-12, merged tree: 44 passed, 0 failed.
It does not cover GSP, data breakpoints, or a real VS Code window.

## Fast tests

`pnpm test` runs the Node tests in `test/unit`. Exception tests cover read-only
context capture, workspace boundaries, current-stop validation, model selection
cancellation, missing providers, streaming, provider failures and token limits.
The provider is simulated; these tests do not contact a model service.

Grammar tests use the actual VS Code HTML/Groovy/JavaScript/CSS grammars through
`vscode-textmate` and Oniguruma. Set `VSCODE_BUILTIN_EXTENSIONS` to VS Code's
`resources/app/extensions` directory if it is not found automatically. On Windows
the runner also recognizes the versioned subdirectory used by recent VS Code
installations. These tests cover expressions in HTML/tag attributes, nested
closures, comment boundaries, directives, multiline scriptlets and script/style.

`pnpm lint` checks the extension, helpers and test scripts. The existing six
unused catch-variable warnings in `extension.js` predate this change.

## Real editor regression

`pnpm test:editor` launches VS Code with a separate profile and extensions folder
under `.vscode-test`. It drives real editor commands and menus via Playwright/CDP
(`playwright-core`, a dev-only dependency used only by this editor test and not shipped in the vsix) while an Extension Host test records DAP messages and checks the target's HTTP
responses. A graphical desktop is required (use Xvfb on headless Linux).

The target must be the **standalone Grails 7.2.3 dapspike application**, not a
business repository. By default it is the sibling directory
`../grails-dap-testbed/dapspike`; set `GRAILS_TESTBED` to override this. The testbed
contains `SpikeService`, `SpikeController`, `Widget` and `views/spike/page.gsp`
from the design document's spikes. Its Gradle `bootRun` accepts `-PdbgPort`
(JDWP, suspend=y) and `-PappPort` (HTTP). It must have these routes:

| Route | Behavior |
| --- | --- |
| `/` | Readiness response |
| `/gsppage` | Render `spike/page.gsp`; line 4 contains `${title}` |
| `/bump` | Increment singleton `SpikeService.touches` at line 57 |
| `/spike/editor-test` | Throw `IllegalStateException("deliberate failure: editor-test")` |

Set `VSCODE_EXECUTABLE` to choose a VS Code executable. The runner uses the local
Windows installation when present; otherwise `@vscode/test-electron` downloads
VS Code. Ports are allocated for each run. Run only one editor suite at a time;
the profile and testbed build directory are shared. The runner stops only the
process tree/group it started, not unrelated Java or Grails applications.

Coverage:

- Attach to Running App through the actual host/port input box.
- Recognize `.gsp`, toggle its gutter breakpoint, stop at page line 4 and step
  over through the editor command.
- Expand `this`, use the Variables pane's **Break on Value Change** menu and
  check successive instance field writes.
- Stop on a real caught exception, open its context document, and exercise the
  missing-model flow. Continuing clears the exception action.

Artifacts are in `.vscode-test/artifacts`: screenshots, `dap-transcript.json`,
and the target's `grails.log`. Failure screenshots and DOM dumps help diagnose
UI automation failures. These files are ignored by Git and excluded from VSIX.

Live model generation requires an available provider and its account/consent.
That verification has not been substituted with the simulated-provider tests.
macOS/Linux process management has not been exercised in this Windows session.

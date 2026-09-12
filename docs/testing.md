# Testing the extension

0.1.28 changes the tenant fixture to enumerate with bare DetachedCriteria,
matching resolvers that do not provide their own transaction. The old engine
fails this case with HibernateException; the fixed engine supplies a read-only
transaction. Fixture bootstrap also asserts the security chain registration is
disabled for Skip mode and enabled for credential login.

0.1.27: `scripts/test-qa-catalog.js` creates separate single/multi-tenant fixtures
and tests runtime discovery, tenant-scoped users (including identical usernames),
empty tenants, search/pagination, disabled/locked accounts, shared process reuse,
request/session tenant context and 14 UI checks in each fixture. It accepts
`GRAILS_QA_ENGINE` for installed-package verification. The real editor suite now
loads and selects an identity before its Skip authentication run.

0.1.26 adds a real `QaAccount` fixture and a controller guard that redirects
without `springSecurityService.currentUser`, then checks its Domain type and
ROLE_QA. `scripts/test-qa-project-database.js` exercises credentials and bypass;
bypass supplies only a username. Fresh plans now have 22 Domain/H2 cases.
`scripts/test-qa-identity.js` checks currentUser without a password, missing/wrong
capabilities, request isolation, and no capability forwarded across origins.
Both scripts accept `GRAILS_QA_ENGINE` pointing at the installed engine and inherit
the user's JAVA_HOME.

Latest local verification (2026-09-06, 0.1.18): **15 unit tests and 4 real-editor
tests passed** on Windows / VS Code 1.136.1 / Grails 7.2.3. The editor suite passed
with both Java 25.0.4.1 and Java 17.0.11; the final run used Java 17.0.11 and took
16 seconds for the four editor tests, excluding application/editor startup.
The server build and local VSIX packaging succeeded. Lint reported no errors and
the six existing unused catch-variable warnings described below.

Install development dependencies with `pnpm install`. Build the adapter with
`npm run build:server` using a valid `JAVA_HOME` pointing at JDK 17 or newer.
The Java compiler emits Java 17 bytecode.

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
while an Extension Host test records DAP messages and checks the target's HTTP
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

## Grails QA regression

`pnpm test` also checks QA boundary precision, unsupported-rule gaps, route
confirmation, form assertion contracts, same-origin login, credential scoping,
redaction, config rejection, existing-server bypass guards, concurrent operation
locks and JUnit result aggregation.

`pnpm test:qa:editor` creates `.vscode-test/qa-fixture` from the standalone
testbed's toolchain and launches another isolated VS Code profile. Its five
tests exercise real panel controls, credential login, SecretStorage reuse,
local bypass and incorrect-password failure. Successful runs execute 20
Domain/H2 cases and 14 real browser cases. Three unconfirmed auxiliary views
remain explicit gaps, so the fixture's overall status is `partial`.
Artifacts: `.vscode-test/qa-editor-artifacts` and the fixture's `.grails-qa/runs`.

`pnpm test:qa:regression` freezes the fixture's expectations, changes a numeric
constraint, asserts the old plan detects it, restores the source in `finally`,
and checks all 20 Domain/H2 cases pass. It also launches bypass UI tests with a
fixture bootstrap assertion checking the actual JDBC connection is temporary
H2. Run these suites serially because they share the fixture build directory.

Use Java 17 and installed Edge on Windows for the verified setup. Set
`QA_EDITOR_GREP=Buttons` to check only the panel controls/layout. Package builds
stage the browser driver through `build:qa`; browser binaries are not bundled.

For package verification, extract the VSIX outside the repository (without
access to its node_modules) and run `node scripts/check-qa-package.js
<extracted-extension-directory>`. This launches the bundled driver against a
temporary HTTP page, asserts its content and writes a browser screenshot.

`node scripts/test-qa-project-database.js` verifies the default UI launch
preserves the fixture's own test-environment JDBC URL with both credentials
and bypass, executes its 14 browser cases, and checks logs contain no ESC.
Set `GRAILS_QA_ENGINE` to an installed extension's `qa/engine.js` to test that
installed engine. Inherit the user's JAVA_HOME; do not choose another JDK.
# AI QA validation (0.1.30)

`npm test` passes 34 cases, including streamed model output, cancellation,
missing providers, source exclusions, schema rejection and baseline preservation.
The provider in these tests is simulated; live model generation quality has not
been verified with an authenticated account.

`QA_EDITOR_GREP='AI draft controls' node scripts/test-qa-editor.js` passes in real
VS Code: missing-provider message, applying a reviewed draft, then 22 Domain and
15 browser checks against the independent Grails fixture.

`node scripts/test-qa-ai-browser.js` exercises a visible local Edge window. It
checks fill/select/check/click/text/URL execution, screenshot evidence, a wrong
expectation failing, and blocked cross-origin navigation (zero external requests).
It also passes using `GRAILS_QA_ENGINE` pointing at the installed 0.1.30 engine.

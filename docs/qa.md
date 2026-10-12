# Grails QA (0.1.30 local preview)

Open a Grails application, then choose **DC-Grails-VS → Run → Run QA** or
**Grails: Run QA** from the command palette. The panel runs Domain constraint
checks, real Hibernate/H2 persistence checks, and browser page/form tests.

The engine replays frozen expectations. Optional AI generation proposes browser
cases from source; its inferred expectations need review. Verified target: Grails 7.2.3,
Hibernate, Gradle 8.14.5, Java 25, Windows, Edge. Other versions and operating
systems have not yet been verified.

## AI generation and local browser

1. Click **Generate QA cases with AI**, then select an available VS Code model.
   This sends bounded controller, GSP and Domain source excerpts to that provider.
   Configuration, database rows, saved credentials and logs are excluded; source
   files themselves may contain sensitive text. No provider means an explicit
   setup message, with no change to your saved plan.
2. Review/edit `.grails-qa/ai-draft.json` and save it. Each case has a name,
   use-case description, application-relative path, steps and a final assertion.
   Gaps identify missing evidence or dependencies. Source context is bounded to
   90,000 characters, at most 6,000 per file, and reduced further to fit the model.
3. Click **Use AI draft** to replace the plan's AI browser cases. The Domain
   baseline and configured pages are preserved. A previous-plan backup is saved
   as `plan.json.before-ai.json`. If no base plan exists, Domain inventory runs first.
4. Choose the test target and identity, then **Run QA**. **Show local browser
   during QA** is on by default; clear it to run headlessly. A separate local
   Edge/Chromium session executes the saved steps and records screenshots and
   failures. CLI runs retain their existing headless default.

Supported actions: `click`, `fill`, `select`, `check`, `visible`, `text`, `url`.
Selectors use CSS; `text` checks contained text, `url` checks an exact relative
path including query/hash. Each case ends with an assertion. There is no model
generated JavaScript or shell execution. Browser flows can write test data;
authentication remains configured separately. AI cases block cross-origin
navigation and cross-origin non-GET/HEAD requests. They do not control your
existing browser tabs or desktop applications.

The model generates a draft once; Run QA replays it rather than asking the model
to change expectations after a failure. It does not yet inspect live screenshots
to plan new actions. Verify inferred routes, selectors and business expectations;
unavailable Docker/API services can still cause real page failures.

## Authentication and target

- **Log in with test credentials** uses the real login form. Credentials can
  be remembered in VS Code SecretStorage, separately per project and target
  origin. Blank fields reuse the stored pair. **Forget saved credentials**
  removes it. Local H2 starts empty: create the test account in your test
  bootstrap, or use an existing QA server with a prepared account.
- **Skip authentication** is available only for a local instance started by
  the tool when it detects the Grails Spring Security plugin. Click **Load test
  users / tenants** to start the test application and read its actual database.
  Multi-tenancy is detected from the running Hibernate datastore. If present,
  choose a tenant first, then one of its users; otherwise only the user selector
  is shown. No identity is selected automatically and no password is required.
  Search and **Load more users** browse usernames in pages of 100.
  **Filter usernames** is optional: leave it empty to list available accounts;
  usernames do not need to contain `test`. **Clear filter** restores the list.
  Reloading or switching tenants clears the old search. Results and empty-search
  guidance are displayed next to the user selector.
  Disabled,
  locked and expired accounts are excluded when those mapped properties exist.
  Empty lists are reported explicitly; QA does not create test accounts/tenants.
  The project's `userDetailsService`
  supplies the principal and existing roles for each QA request, so Grails
  `currentUser` can resolve the real test user. No user is created or granted
  additional roles. Missing, disabled, locked or expired users are rejected.
  Discovery and the QA run reuse the same application process, including when
  using temporary H2. Cancel, changing target/authentication, closing the panel,
  or finishing the run stops it and releases the per-project operation lock.
  Reload the list before the next run.

  User discovery uses Spring Security's configured GORM user domain and username
  property; only username projections are returned, never password fields.
  Tenants come from `AllTenantsResolver.resolveTenantIds()` or named connections
  in DATABASE mode. Resolver enumeration runs in a read-only transaction so
  implementations using DetachedCriteria have a Hibernate session.
  Unsupported external identity providers/resolvers report an
  explicit setup error instead of guessing identities. No discovery endpoint is
  added to an existing server or normal application build.

  For multi-tenant QA, the selected tenant wraps both user lookup and page handling
  using GORM `Tenants.withId`. Session resolvers exposing `attributeName` also
  receive that attribute for the request (including custom names). Request,
  security and tenant contexts are restored afterward; the report records the
  selected tenant. Custom cookie/subdomain/session conventions beyond this
  contract require project-specific support.
  A generated
  external Groovy configuration keeps the plugin active and sets its HTTP
  `filterChain.chainMap` to `[[pattern: '/**', filters: 'none']]` for that process.
  Security service beans (including REST token storage) remain available.
  The security chain's servlet registration is disabled in this QA process so
  environment-specific chain configuration cannot undo Skip mode. The QA
  capability filter continues to protect every request.
  A temporary initializer is added only to this bootRun classpath. Its filter
  accepts the QA browser's per-run capability on loopback requests and restores
  the security context after each request. The capability is kept in memory and
  the child environment; it is not written to configuration or reports and is
  not forwarded to other origins. Normal application builds do not include it.
  Existing servers cannot bypass security.
  The report excludes authentication/authorization coverage. Custom filters,
  SSO, MFA, custom session state and WebSockets need application-specific setup.

  This uses the plugin's documented [filter-chain configuration](https://apache.github.io/grails-spring-security/6.0.x/index.html#filters).
- **No login** supports public pages. Redirecting to login fails a protected
  page test instead of counting the login page as a successful application page.

Local runs bind `127.0.0.1` on a temporary port and default to the project's
Grails **test environment and database**. Datasource URLs, credentials,
migrations and external-service configuration remain the application's own.
The process stops after the run; application/build sources are not changed.
Temporary H2 is an explicit alternative (`ui.database: "h2"`); only that
alternative rejects multiple datasources. Isolated Domain constraint and
persistence checks continue to use their own H2 datastore.
Browser submissions really write to the selected QA server.

Credentials are not stored in configuration, generated tests, reports or
browser session files. Login pages are not screenshotted; session state stays
in memory. Application-page screenshots can contain displayed test data.
The browser driver is bundled without browser binaries: use installed
Edge/Chrome or configure a Chromium executable.

## Frozen plan and configuration

The first **Run QA** generates `.grails-qa/plan.json`. Subsequent runs reuse it
so a code change cannot silently rewrite the expected results. **Generate /
replace test plan** intentionally replaces the baseline; review its diff.

Inventory evaluates Domain constraints in an isolated Hibernate datastore.
Supported cases: scalar nullable/blank, string length, email/URL, numeric
boundaries, inList, real H2 flush/clear/reload and simple unique constraints
with transaction rollback. Custom validators, associations, composite unique
constraints and unsupported types are recorded as gaps. Application-wide
default constraints, database-specific behavior, service/controller business
rules and authorization matrices are not inferred.

Only literal GET-compatible routes are selected automatically. Other GSP
paths require configuration. Open **QA configuration**, edit
`.grails-qa/config.json`, then generate a new plan. Example:

```json
{
  "ui": {
    "launch": true,
    "pages": [{
      "path": "/book/create",
      "source": "grails-app/views/book/create.gsp",
      "domain": "demo.Book",
      "form": "form#create-book",
      "fields": ["title"],
      "success": { "selector": ".message", "text": "created" },
      "error": {
        "selector": ".errors",
        "fieldSelector": "[data-qa-field=\"{field}\"]"
      }
    }]
  },
  "auth": { "mode": "credentials", "loginPath": "/login/auth" },
  "fixtures": { "demo.Book": { "title": "QA book" } }
}
```

Forms need explicit success/error markers. Negative cases also need a field
error selector containing `{field}`. Missing submission contracts are reported
as gaps. Native browser rejection (maxlength/required) is distinguished from
application rejection. An already-visible error marker cannot satisfy a new
submission assertion.

Optional settings: `ui.database` (`project`, the default, or `h2`),
`ui.baseURL` with `ui.launch: false`, `ui.channel`,
`ui.executablePath`, `ui.headless`, `ui.timeout`; page `selector`,
`fieldSelectors`, `submitSelector`, `values`; authentication `usernameSelector`,
`passwordSelector`, `submitSelector`, `successSelector`. Default username and
password selectors support both `username`/`password` and
`j_username`/`j_password`. Login URLs and form actions stay on the target origin.

## Reports and CLI

Each run writes `.grails-qa/runs/<run>/report.json`, JUnit XML/HTML, logs,
generated Groovy specs and screenshots. `.grails-qa/report.json` is the latest
result; **Open full report** opens it. Each Domain and browser case includes a short `useCase` description
of the scenario and expected check, also shown in the panel's **Test cases**
table. Existing frozen plans work unchanged; rerun QA to add descriptions to
the new report. Descriptions explain configured checks, not inferred business rules.
Statuses are `passed`, `failed` and
`partial`: gaps/skipped tests never count as an all-green result. Review config
and plan in version control; ignore `runs/` and `report.json`.

```sh
node qa/cli.js scan /path/to/app
node qa/cli.js plan /path/to/app
node qa/cli.js run /path/to/app --domain-only
node qa/cli.js run /path/to/app --launch --auth=bypass
node qa/cli.js run /path/to/app --launch --database=h2 --auth=bypass
node qa/cli.js run /path/to/app --base-url=http://localhost:8080 --auth=credentials
```

CLI credentials use `GRAILS_QA_USERNAME` and `GRAILS_QA_PASSWORD`. Bypass requires
only `GRAILS_QA_USERNAME`, naming an existing test user. Exit codes:
0 passed, 1 failed, 2 partial. Cancel stops only processes started by this run.
Run one QA operation per application at a time.
An `active.lock` prevents overlapping operations. After a crash, remove that
lock only after confirming the recorded process has stopped.

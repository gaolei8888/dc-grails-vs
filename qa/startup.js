function startupFailure(output) {
  if (!/Application run failed|APPLICATION FAILED TO START/.test(output)) return null;
  const tail = output.slice(-100000);
  const missing = /required a bean named '([^']+)' that could not be found/.exec(tail)?.[1];
  if (missing) return 'QA application startup failed: missing Spring bean ' + missing + '. See application.log.';
  if (/liquibase/i.test(tail) && /JdbcSQLSyntaxErrorException/.test(tail)) {
    return 'Local QA application startup failed: database migration SQL could not run on temporary H2. Use a prepared QA server with the application\'s database and required services. See application.log.';
  }
  const cause = [...tail.matchAll(/^Caused by:\s*(.+)$/gm)].at(-1)?.[1]?.trim();
  return 'QA application startup failed' + (cause ? ': ' + cause.slice(0, 600) : '.') + ' See application.log.';
}
module.exports = { startupFailure };

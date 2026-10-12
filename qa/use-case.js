// Describe the frozen checks without inferring application business requirements.
function constraintUseCase(domain, row) {
  return `Set ${domain}.${row.property} to ${row.label || 'the test value'} and verify that the ${row.rule || 'field'} constraint ${row.reject ? 'rejects' : 'does not reject'} it.`;
}
function describeDomainCases(plan, cases) {
  const descriptions = new Map();
  for (const domain of plan.domains) {
    for (const row of domain.rows) descriptions.set(
      `${domain.name}: ${row.property} ${row.rule} ${row.label} (reject=${row.reject})`, constraintUseCase(domain.name, row));
    descriptions.set(`${domain.name}: persist flush clear and reload in H2`,
      `Save a valid ${domain.name} record in H2, clear the session, and reload it to verify the saved field values.`);
    for (const field of domain.unique || []) descriptions.set(`${domain.name}: reject duplicate ${field} in H2`,
      `Save a ${domain.name} record in H2, then verify that another record with the same ${field} fails uniqueness validation.`);
  }
  for (const result of cases) result.useCase = descriptions.get(result.name) || `Execute ${result.name} and check the declared assertions.`;
}
module.exports = { constraintUseCase, describeDomainCases };

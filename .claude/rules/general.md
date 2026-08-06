# General Rules (always loaded)

- No secrets in code. Use environment variables.
- No dead code. Delete it, don't comment it out.
- No premature optimization. Make it correct, then make it fast.
- Every feature ships with tests.
- Import order: standard lib → framework → third-party → local.
- Read ARCHITECTURE.md before making structural changes. Update it after.
- For significant decisions, create an ADR in `docs/architecture/decisions/`.
- Multi-tenancy: every query filters by org_id. No exceptions.
- Follow the subagent workflow: planner → implementer → reviewer.
- Check the security skill's OWASP Top 10 checklist on every review.

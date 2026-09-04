package com.qualityops.api.execution.application.service;

import com.qualityops.api.audit.annotation.Audited;
import com.qualityops.api.audit.domain.AuditAction;
import com.qualityops.api.config.SchedulingProperties;
import com.qualityops.api.execution.application.port.in.GetRunConcurrencyUseCase;
import com.qualityops.api.execution.application.port.in.SetRunConcurrencyUseCase;
import com.qualityops.api.execution.application.port.out.OrgConcurrencyRepository;
import com.qualityops.api.execution.dto.RunConcurrencyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** ADR-007 §4. The dispatcher already reads {@code org_run_concurrency}; an
 *  override takes effect on the next {@code queue-dispatch} tick with no restart. */
@Service
@Transactional
public class OrgConcurrencyService implements SetRunConcurrencyUseCase, GetRunConcurrencyUseCase {

    /** Structured audit line — 2E's {@code @Audited} can promote this to a table. */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.qualityops.api.audit");

    private final OrgConcurrencyRepository orgConcurrencyRepository;
    private final SchedulingProperties props;

    public OrgConcurrencyService(OrgConcurrencyRepository orgConcurrencyRepository,
                                 SchedulingProperties props) {
        this.orgConcurrencyRepository = orgConcurrencyRepository;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public RunConcurrencyResponse get(UUID orgId) {
        return orgConcurrencyRepository.findByOrgId(orgId)
            .map(v -> new RunConcurrencyResponse(v, "OVERRIDE"))
            .orElseGet(() -> new RunConcurrencyResponse(props.queue().maxActiveRunsPerOrg(), "DEFAULT"));
    }

    @Override
    // ADR-008 §7: @Audited promotes the structured AUDIT.info line below to a
    // durable audit_log row; both coexist in 2E.
    @Audited(action = AuditAction.ORG_RUN_CONCURRENCY_UPDATE, targetType = "org")
    public RunConcurrencyResponse set(UUID orgId, int maxActiveRuns, UUID actorUserId) {
        String oldStr = orgConcurrencyRepository.findByOrgId(orgId).map(String::valueOf)
            .orElse("default:" + props.queue().maxActiveRunsPerOrg());
        orgConcurrencyRepository.upsert(orgId, maxActiveRuns);
        AUDIT.info("audit action=org.run_concurrency.update actor={} org={} old={} new={}",
            actorUserId, orgId, oldStr, maxActiveRuns);
        return new RunConcurrencyResponse(maxActiveRuns, "OVERRIDE");
    }
}

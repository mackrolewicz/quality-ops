package com.qualityops.api.execution.application.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OrgConcurrencyRepository {

    /** orgId -> max concurrent active runs override. Orgs absent from the map
     *  use the global default from configuration. */
    Map<UUID, Integer> findAllOverrides();

    /** ADR-007 §4 — the override for one org, if any. */
    Optional<Integer> findByOrgId(UUID orgId);

    /** ADR-007 §4 — INSERT ... ON CONFLICT (org_id) DO UPDATE. */
    void upsert(UUID orgId, int maxActiveRuns);
}

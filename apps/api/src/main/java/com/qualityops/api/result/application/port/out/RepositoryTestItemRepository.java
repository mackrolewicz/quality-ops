package com.qualityops.api.result.application.port.out;

import com.qualityops.api.result.domain.RepositoryTestItem;

import java.util.List;
import java.util.UUID;

/** ADR-009 §7 — org-scoped persistence for {@code repository_test_item}. */
public interface RepositoryTestItemRepository {

    /** Epoch-guarded upsert: {@code ON CONFLICT (run_id, item_key) DO UPDATE …
     *  WHERE repository_test_item.attempt_epoch <= EXCLUDED.attempt_epoch}
     *  (ADR-005 §2.4). A stale lower-epoch chunk is a no-op. */
    void upsertForRun(UUID orgId, UUID runId, int attemptEpoch, List<RepositoryTestItem> items);

    List<RepositoryTestItem> findByRunIdAndOrgId(UUID runId, UUID orgId);
}

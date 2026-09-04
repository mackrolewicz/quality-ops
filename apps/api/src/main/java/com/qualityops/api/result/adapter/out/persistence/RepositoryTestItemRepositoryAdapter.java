package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.application.port.out.RepositoryTestItemRepository;
import com.qualityops.api.result.domain.RepositoryTestItem;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
class RepositoryTestItemRepositoryAdapter implements RepositoryTestItemRepository {

    private final RepositoryTestItemJpaRepository jpa;

    RepositoryTestItemRepositoryAdapter(RepositoryTestItemJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void upsertForRun(UUID orgId, UUID runId, int attemptEpoch, List<RepositoryTestItem> items) {
        for (RepositoryTestItem item : items) {
            jpa.upsertItem(orgId, runId, item.itemKey(), item.suite(), item.name(), item.status(),
                item.durationMs(), item.failureType(), item.failureMessage(), item.attemptEpoch());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepositoryTestItem> findByRunIdAndOrgId(UUID runId, UUID orgId) {
        return jpa.findByRunIdAndOrgIdOrderBySuiteAscNameAsc(runId, orgId).stream()
            .map(RepositoryTestItemRepositoryAdapter::toDomain)
            .toList();
    }

    private static RepositoryTestItem toDomain(RepositoryTestItemEntity e) {
        return new RepositoryTestItem(e.getItemKey(), e.getSuite(), e.getName(), e.getStatus(),
            e.getDurationMs(), e.getFailureType(), e.getFailureMessage(), e.getAttemptEpoch());
    }
}

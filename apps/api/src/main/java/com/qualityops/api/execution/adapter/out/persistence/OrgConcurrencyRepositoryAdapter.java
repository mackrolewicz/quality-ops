package com.qualityops.api.execution.adapter.out.persistence;

import com.qualityops.api.execution.application.port.out.OrgConcurrencyRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class OrgConcurrencyRepositoryAdapter implements OrgConcurrencyRepository {

    private final OrgRunConcurrencyJpaRepository jpa;

    OrgConcurrencyRepositoryAdapter(OrgRunConcurrencyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> findAllOverrides() {
        Map<UUID, Integer> out = new HashMap<>();
        for (Object[] r : jpa.findAllLimits()) {
            out.put((UUID) r[0], ((Number) r[1]).intValue());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Integer> findByOrgId(UUID orgId) {
        return jpa.findMaxActiveRunsByOrgId(orgId);
    }

    @Override
    @Transactional
    public void upsert(UUID orgId, int maxActiveRuns) {
        jpa.upsert(orgId, maxActiveRuns);
    }
}

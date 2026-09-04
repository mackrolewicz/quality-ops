package com.qualityops.worker.execution.adapter.out.container;

import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-009 §9 — restart-safe orphan reconciliation. On boot and every
 * {@code qualityops.repo-exec.container-sweep-interval} (PT10M): enumerate the
 * {@code label=com.qualityops.managed=true} containers, subtract those whose
 * {@code worker.execution_attempt} is COMPLETED, and hand the remaining
 * "possibly-live" execution ids to {@link com.qualityops.worker.execution.application.port.out.ContainerRunnerPort#sweepOrphans}
 * (which additionally drops any container older than the run wall-clock budget).
 * The Worker's Postgres reach is unchanged — this reads only {@code execution_attempt}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "qualityops.repo-exec.enabled", havingValue = "true", matchIfMissing = true)
public class RepoContainerSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RepoContainerSweeper.class);

    private final DockerContainerRunner runner;
    private final ExecutionAttemptStore attemptStore;

    public RepoContainerSweeper(DockerContainerRunner runner, ExecutionAttemptStore attemptStore) {
        this.runner = runner;
        this.attemptStore = attemptStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    @Scheduled(fixedDelayString = "${qualityops.repo-exec.container-sweep-interval:PT10M}")
    void sweep() {
        Set<UUID> managed = runner.managedExecutionIds();
        if (managed.isEmpty()) {
            return;
        }
        Set<UUID> completed = attemptStore.completedExecutionIds(managed);
        Set<UUID> maybeLive = new HashSet<>(managed);
        maybeLive.removeAll(completed);
        int removed = runner.sweepOrphans(maybeLive);
        if (removed > 0) {
            log.info("repo container sweep removed {} orphan(s) ({} managed, {} completed)",
                removed, managed.size(), completed.size());
        }
    }
}

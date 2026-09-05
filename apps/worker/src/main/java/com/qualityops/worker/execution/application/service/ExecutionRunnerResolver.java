package com.qualityops.worker.execution.application.service;

import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.adapter.out.runner.BlockedRepositoryRunner;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ExecutionRunnerResolver {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRunnerResolver.class);

    private final Mode mode;
    private final Map<RunnerKind, ExecutionRunner> byKind = new EnumMap<>(RunnerKind.class);
    private final ExecutionRunner blockedRepositoryRunner;

    public ExecutionRunnerResolver(WorkerExecutionProperties props, List<ExecutionRunner> runners,
                                   MeterRegistry meterRegistry) {
        this.mode = props.mode();
        runners.forEach(r -> byKind.put(r.kind(), r));
        this.blockedRepositoryRunner = new BlockedRepositoryRunner(meterRegistry);
    }

    public ExecutionRunner resolve(TestCaseSnapshotItem c) {
        // ADR-009 §1 gap #8 — a repository case is unambiguous: REPOSITORY
        // regardless of WORKER_EXECUTION_MODE. Mode only governs the
        // api/browser/simulated fall-through below.
        if (c.repoTest() != null) {
            return byKind.getOrDefault(RunnerKind.REPOSITORY, blockedRepositoryRunner);
        }
        return switch (mode) {
            case SIMULATED -> byKind.get(RunnerKind.SIMULATED);
            case REAL -> byKind.get(c.browserTest() != null ? RunnerKind.BROWSER : RunnerKind.API);
            case AUTO -> byKind.get(kindForCase(c));
        };
    }

    private RunnerKind kindForCase(TestCaseSnapshotItem c) {
        if (c.browserTest() != null && c.apiRequest() != null) {
            log.warn("Case {} carries both a browserTest and an apiRequest — running as BROWSER", c.testCaseId());
            return RunnerKind.BROWSER;
        }
        if (c.browserTest() != null) {
            return RunnerKind.BROWSER;
        }
        return c.apiRequest() != null ? RunnerKind.API : RunnerKind.SIMULATED;
    }

    /** Ledger {@code runner_kind} hint: REPOSITORY if any repo case (unconditional,
     *  gap #8), else BROWSER if any browser case, else API if any real case, else
     *  SIMULATED. */
    public RunnerKind resolvedKindFor(List<TestCaseSnapshotItem> cases) {
        if (cases.stream().anyMatch(c -> c.repoTest() != null)) {
            return RunnerKind.REPOSITORY;
        }
        return switch (mode) {
            case SIMULATED -> RunnerKind.SIMULATED;
            case REAL -> cases.stream().anyMatch(c -> c.browserTest() != null)
                ? RunnerKind.BROWSER : RunnerKind.API;
            case AUTO -> {
                if (cases.stream().anyMatch(c -> c.browserTest() != null)) {
                    yield RunnerKind.BROWSER;
                }
                yield cases.stream().anyMatch(c -> c.apiRequest() != null)
                    ? RunnerKind.API : RunnerKind.SIMULATED;
            }
        };
    }
}

package com.qualityops.worker.execution.application.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.qualityops.worker.config.RepoExecMetrics;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ADR-009 §5 — pre-pulls all six digest-pinned runner images on startup so the
 * per-run {@code createContainer} path can be inspect-not-pull ({@code --pull=never}
 * equivalent) and a run never triggers an unexpected pull. Gated on
 * {@code image-pull-on-startup} (default true). Failures are logged, not fatal —
 * a missing image later surfaces as a {@code BLOCKED} case, not a Worker crash.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "qualityops.repo-exec.enabled", havingValue = "true", matchIfMissing = true)
public class RepoImagePrePuller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RepoImagePrePuller.class);

    private final DockerClient docker;
    private final RepoExecWorkerProperties props;
    private final RepoExecMetrics metrics;

    public RepoImagePrePuller(DockerClient docker, RepoExecWorkerProperties props,
                              RepoExecMetrics metrics) {
        this.docker = docker;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.imagePullOnStartup() || props.images() == null) {
            log.info("repo-exec image pre-pull disabled");
            return;
        }
        for (String ref : props.images().all()) {
            pull(ref);
        }
    }

    private void pull(String ref) {
        String tag = presetTag(ref);
        Timer.Sample sample = Timer.start();
        try {
            docker.pullImageCmd(ref).exec(new PullImageResultCallback())
                .awaitCompletion(5, TimeUnit.MINUTES);
            sample.stop(metrics.imagePull(tag, "ok"));
            log.info("pre-pulled runner image {}", ref);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sample.stop(metrics.imagePull(tag, "error"));
        } catch (RuntimeException e) {
            sample.stop(metrics.imagePull(tag, "error"));
            log.warn("could not pre-pull runner image {} ({}) — repo runs using it will be BLOCKED "
                + "until it is available", ref, e.toString());
        }
    }

    private String presetTag(String ref) {
        var img = props.images();
        if (ref.equals(img.playwright())) {
            return "PLAYWRIGHT";
        }
        if (ref.equals(img.junit())) {
            return "JUNIT";
        }
        if (ref.equals(img.pytest())) {
            return "PYTEST";
        }
        if (ref.equals(img.cypress())) {
            return "CYPRESS";
        }
        if (ref.equals(img.k6())) {
            return "K6";
        }
        return "CHECKOUT";
    }
}

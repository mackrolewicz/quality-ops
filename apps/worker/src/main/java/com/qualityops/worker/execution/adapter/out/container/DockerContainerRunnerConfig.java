package com.qualityops.worker.execution.adapter.out.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.qualityops.worker.config.RepoExecWorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;

/**
 * ADR-009 §6 — builds the {@link DockerClient} from
 * {@code qualityops.repo-exec.docker.host} over the {@code httpclient5} transport.
 *
 * <p><strong>Proxy enforcement (gap #7).</strong> When
 * {@code docker.require-proxy=true} the host must be a {@code tcp://} endpoint (the
 * {@code tecnativa/docker-socket-proxy}); a raw {@code unix://} or Windows
 * {@code npipe://} socket fails startup. When {@code require-proxy=false} a raw
 * socket is allowed with a loud WARN — the local {@code mvn spring-boot:run} path.
 */
@Configuration
@ConditionalOnProperty(name = "qualityops.repo-exec.enabled", havingValue = "true", matchIfMissing = true)
public class DockerContainerRunnerConfig {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerRunnerConfig.class);

    @Bean(destroyMethod = "close")
    DockerClient repoExecDockerClient(RepoExecWorkerProperties props) {
        String host = props.docker() == null || props.docker().host() == null
            ? "unix:///var/run/docker.sock" : props.docker().host().trim();
        boolean requireProxy = props.docker() != null && props.docker().requireProxy();
        boolean rawSocket = isRawSocket(host);

        if (rawSocket && requireProxy) {
            throw new IllegalStateException("qualityops.repo-exec.docker.require-proxy=true but docker.host "
                + "resolves to a raw socket (" + host + "). Point it at tcp://docker-proxy:2375.");
        }
        if (rawSocket) {
            log.warn("repo-exec Docker endpoint is a RAW socket ({}) — the Worker process holds daemon "
                + "access equivalent to host root. Acceptable only for local dev; set require-proxy=true "
                + "and DOCKER_HOST=tcp://docker-proxy:2375 in staging.", host);
        } else {
            log.info("repo-exec Docker endpoint: {}", host);
        }

        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(host)
            .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .connectionTimeout(Duration.ofSeconds(10))
            .responseTimeout(Duration.ofMinutes(40))
            .build();
        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        try {
            client.pingCmd().exec();
            log.info("repo-exec Docker daemon reachable");
        } catch (RuntimeException e) {
            log.warn("repo-exec Docker daemon ping failed ({}). Repository runs will be BLOCKED until it "
                + "recovers.", e.toString());
        }
        return client;
    }

    private static boolean isRawSocket(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("unix://") || h.startsWith("npipe://") || h.startsWith("fd://");
    }
}

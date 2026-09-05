package com.qualityops.worker.execution.adapter.out.secret;

import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.port.out.SecretResolver;
import com.qualityops.worker.execution.exception.SecretNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** Local/dev {@link SecretResolver}: environment variable
 *  ({@code <env-prefix><KEY>}) first, then an optional mounted properties file
 *  (keys = {@code <KEY>}). Logs only the key name and a hit/miss — never the
 *  value. Production (Key Vault) is a separate Phase-5 adapter. */
@Component
public class EnvFileSecretResolver implements SecretResolver {

    private static final Logger log = LoggerFactory.getLogger(EnvFileSecretResolver.class);

    private final String envPrefix;
    private final Path file;
    private final Function<String, String> getenv;

    @Autowired
    public EnvFileSecretResolver(WorkerExecutionProperties props) {
        this(props, System::getenv);
    }

    EnvFileSecretResolver(WorkerExecutionProperties props, Function<String, String> getenv) {
        var secrets = props.secrets();
        this.envPrefix = secrets == null || secrets.envPrefix() == null
            ? "QUALITYOPS_SECRET_" : secrets.envPrefix();
        this.file = secrets == null || secrets.file() == null || secrets.file().isBlank()
            ? null : Path.of(secrets.file());
        this.getenv = getenv;
    }

    @Override
    public String resolve(String key) throws SecretNotFoundException {
        String fromEnv = getenv.apply(envPrefix + key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            log.debug("Resolved secretRef {} from environment", key);
            return fromEnv;
        }
        String fromFile = fromFile(key);
        if (fromFile != null && !fromFile.isBlank()) {
            log.debug("Resolved secretRef {} from file", key);
            return fromFile;
        }
        log.warn("Unresolved secretRef {} (checked env prefix + file)", key);
        throw new SecretNotFoundException(key);
    }

    /** A broken/unreadable secrets file is treated as "not found" (⇒ case BLOCKED),
     *  never as a transient error — it is a deterministic config problem. */
    private String fromFile(String key) throws SecretNotFoundException {
        if (file == null || !Files.isReadable(file)) {
            return null;
        }
        var props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Cannot read secrets file for secretRef {} ({})", key, e.getClass().getSimpleName());
            throw new SecretNotFoundException(key);
        }
        return props.getProperty(key);
    }

    /** Test seam. */
    static EnvFileSecretResolver withEnv(WorkerExecutionProperties props, Map<String, String> env) {
        return new EnvFileSecretResolver(props, env::get);
    }
}

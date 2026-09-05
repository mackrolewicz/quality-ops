package com.qualityops.api.scm.adapter.out.credential;

import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.application.port.out.ScmCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Function;

/** ADR-009 §4 — local/dev {@link ScmCredentialResolver}: environment variable
 *  ({@code <env-prefix><KEY>}) first, then an optional mounted properties file
 *  (keys = {@code <KEY>}). Logs only the key name and a hit/miss — never the
 *  value. Azure Key Vault is a Phase-4/5 adapter. Mirrors the Worker's
 *  {@code EnvFileSecretResolver}. */
@Component
public class EnvScmCredentialResolver implements ScmCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(EnvScmCredentialResolver.class);

    private final String envPrefix;
    private final Path file;
    private final Function<String, String> getenv;

    @Autowired
    public EnvScmCredentialResolver(RepoExecApiProperties props) {
        this(props, System::getenv);
    }

    EnvScmCredentialResolver(RepoExecApiProperties props, Function<String, String> getenv) {
        var scm = props.scm();
        this.envPrefix = scm == null || scm.credentialEnvPrefix() == null || scm.credentialEnvPrefix().isBlank()
            ? "QUALITYOPS_SCM_CREDENTIAL_" : scm.credentialEnvPrefix();
        this.file = scm == null || scm.credentialFile() == null || scm.credentialFile().isBlank()
            ? null : Path.of(scm.credentialFile());
        this.getenv = getenv;
    }

    @Override
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return null;
        }
        String fromEnv = getenv.apply(envPrefix + credentialRef);
        if (fromEnv != null && !fromEnv.isBlank()) {
            log.debug("Resolved SCM credentialRef {} from environment", credentialRef);
            return fromEnv;
        }
        String fromFile = fromFile(credentialRef);
        if (fromFile != null && !fromFile.isBlank()) {
            log.debug("Resolved SCM credentialRef {} from file", credentialRef);
            return fromFile;
        }
        log.warn("Unresolved SCM credentialRef {} (checked env prefix + file)", credentialRef);
        return null;
    }

    private String fromFile(String key) {
        if (file == null || !Files.isReadable(file)) {
            return null;
        }
        var props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Cannot read SCM credential file for {} ({})", key, e.getClass().getSimpleName());
            return null;
        }
        return props.getProperty(key);
    }

    /** Test seam. */
    static EnvScmCredentialResolver withEnv(RepoExecApiProperties props, Function<String, String> getenv) {
        return new EnvScmCredentialResolver(props, getenv);
    }
}

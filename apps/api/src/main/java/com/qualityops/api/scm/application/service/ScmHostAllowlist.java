package com.qualityops.api.scm.application.service;

import com.qualityops.api.config.RepoExecApiProperties;
import com.qualityops.api.scm.exception.RepositoryHostNotAllowedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** ADR-009 §4 — the {@code qualityops.repo-exec.scm.allowed-hosts} gate. A host
 *  not on the list is rejected <em>before any socket is opened</em>. */
@Component
public class ScmHostAllowlist {

    private final Set<String> allowed;

    public ScmHostAllowlist(RepoExecApiProperties props) {
        List<String> configured = props.scm() == null ? null : props.scm().allowedHosts();
        this.allowed = configured == null ? Set.of()
            : configured.stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void check(String host) {
        if (host == null || !allowed.contains(host.toLowerCase(Locale.ROOT))) {
            throw new RepositoryHostNotAllowedException(host);
        }
    }
}

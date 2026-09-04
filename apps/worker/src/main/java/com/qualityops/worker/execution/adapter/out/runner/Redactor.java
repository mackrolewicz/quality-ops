package com.qualityops.worker.execution.adapter.out.runner;

import com.qualityops.worker.config.WorkerExecutionProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class Redactor {

    private static final String MASK = "***REDACTED***";
    private static final List<Pattern> NAME_PATTERNS = List.of(
        Pattern.compile(".*token.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*secret.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*api[-_]?key.*", Pattern.CASE_INSENSITIVE));

    private final List<String> headerDenylist;
    private final List<Pattern> bodyPatterns;

    public Redactor(WorkerExecutionProperties props) {
        var r = props.redaction();
        this.headerDenylist = r.headerDenylist() == null ? List.of()
            : r.headerDenylist().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.bodyPatterns = r.bodyPatterns() == null ? List.of()
            : r.bodyPatterns().stream().map(Pattern::compile).toList();
    }

    public Map<String, String> headers(Map<String, String> in) {
        var out = new LinkedHashMap<String, String>();
        in.forEach((k, v) -> out.put(k, isSensitiveHeader(k) ? MASK : v));
        return out;
    }

    public boolean isSensitiveHeader(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return headerDenylist.contains(n) || NAME_PATTERNS.stream().anyMatch(p -> p.matcher(n).matches());
    }

    /** scheme://host[:port]/path — userinfo and the whole query string dropped. */
    public String url(String raw) {
        try {
            var u = URI.create(raw);
            var sb = new StringBuilder(u.getScheme()).append("://").append(u.getHost());
            if (u.getPort() != -1) {
                sb.append(':').append(u.getPort());
            }
            if (u.getRawPath() != null) {
                sb.append(u.getRawPath());
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "***";
        }
    }

    public String body(String sample) {
        if (sample == null) {
            return null;
        }
        String out = sample;
        for (Pattern p : bodyPatterns) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return out;
    }

    public String value(String v) {
        return body(v);   // assertion expected/actual
    }

    /** ADR-009 §8 — a per-execution mask set (resolved {@code secretVars}
     *  plaintexts + the checkout token) layered on top of this singleton's
     *  regex-based redaction. Exact-string masking runs FIRST so a literal
     *  substring match always wins, then every existing {@code body}/{@code url}/
     *  {@code value}/{@code headers} rule still applies. */
    public RedactionView forExecution(Set<String> literals) {
        Set<String> safe = literals == null ? Set.of() : Set.copyOf(literals);
        return new RedactionView(safe);
    }

    public final class RedactionView {

        private final Set<String> literals;

        private RedactionView(Set<String> literals) {
            this.literals = literals;
        }

        public String line(String raw) {
            return body(maskLiterals(raw));
        }

        public String value(String raw) {
            return Redactor.this.value(maskLiterals(raw));
        }

        public String url(String raw) {
            return Redactor.this.url(maskLiterals(raw));
        }

        public Map<String, String> headers(Map<String, String> in) {
            var literalMasked = new LinkedHashMap<String, String>();
            in.forEach((k, v) -> literalMasked.put(k, maskLiterals(v)));
            return Redactor.this.headers(literalMasked);
        }

        private String maskLiterals(String s) {
            if (s == null || literals.isEmpty()) {
                return s;
            }
            String out = s;
            for (String literal : literals) {
                if (literal != null && !literal.isEmpty()) {
                    out = out.replace(literal, MASK);
                }
            }
            return out;
        }
    }
}

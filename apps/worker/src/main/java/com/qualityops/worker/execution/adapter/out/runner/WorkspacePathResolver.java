package com.qualityops.worker.execution.adapter.out.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ADR-009 §7/§9 — resolves {@code reportPaths} / {@code artifactGlobs} globs
 * against the host-side bind-mounted workspace directory (the Worker reads
 * files directly off disk, never {@code docker cp}). Every match is
 * {@code toRealPath()}'d (follows symlinks) and rejected if it resolves outside
 * the workspace root — the zip-slip / symlink-escape guard. An absolute glob or
 * one containing a {@code ..} segment is rejected before any filesystem walk.
 * Never throws for an unsafe or unmatched glob — logs a skip and continues, so
 * a single bad spec entry does not abort the run.
 */
@Component
public class WorkspacePathResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePathResolver.class);

    /** @param workspaceRoot the per-attempt host workspace directory (must already exist).
     *  @param globs         workspace-relative globs (e.g. {@code "report.xml"}, {@code "**}/{@code *.xml"}).
     *  @return the safe, real, in-root matches — deduplicated, order-preserving. */
    public List<Path> resolve(Path workspaceRoot, List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        Path root = realOrNull(workspaceRoot);
        if (root == null) {
            log.warn("workspace root {} does not exist — no report/artifact files resolved", workspaceRoot);
            return List.of();
        }

        var results = new ArrayList<Path>();
        for (String glob : globs) {
            if (!isSafeGlob(glob)) {
                log.warn("rejecting unsafe glob '{}' (absolute path or '..' segment)", glob);
                continue;
            }
            for (Path match : matches(root, glob)) {
                Path real = realOrNull(match);
                if (real == null) {
                    continue;
                }
                if (!real.startsWith(root)) {
                    log.warn("rejecting glob match '{}' — resolves outside the workspace root", match);
                    continue;
                }
                if (!results.contains(real)) {
                    results.add(real);
                }
            }
        }
        return List.copyOf(results);
    }

    private static boolean isSafeGlob(String glob) {
        if (glob == null || glob.isBlank()) {
            return false;
        }
        String normalized = glob.replace('\\', '/');
        if (normalized.startsWith("/")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private List<Path> matches(Path root, String glob) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> matcher.matches(root.relativize(p)))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk workspace root " + root, e);
        }
    }

    private static Path realOrNull(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}

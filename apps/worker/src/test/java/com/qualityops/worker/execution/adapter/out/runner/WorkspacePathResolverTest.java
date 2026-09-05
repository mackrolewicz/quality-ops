package com.qualityops.worker.execution.adapter.out.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §7/§9 — glob resolution against the workspace root: {@code ../}
 *  escapes, absolute paths, and symlinks pointing outside the root are all
 *  rejected; {@code **} stays in-root. */
class WorkspacePathResolverTest {

    private final WorkspacePathResolver resolver = new WorkspacePathResolver();

    @Test
    void resolve_simpleGlob_findsMatchingFile(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("report.xml"), "<r/>");

        var matches = resolver.resolve(root, List.of("report.xml"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getFileName().toString()).isEqualTo("report.xml");
    }

    @Test
    void resolve_doubleStarGlob_findsNestedFilesStayingInRoot(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("sub/dir"));
        Files.writeString(root.resolve("sub/dir/report.xml"), "<r/>");
        Files.writeString(root.resolve("readme.txt"), "not a report");

        var matches = resolver.resolve(root, List.of("**/*.xml"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0)).isEqualTo(root.resolve("sub/dir/report.xml").toRealPath());
    }

    @Test
    void resolve_parentEscapeGlob_isRejected(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("report.xml"), "<r/>");

        var matches = resolver.resolve(root, List.of("../../../../etc/hostname"));

        assertThat(matches).isEmpty();
    }

    @Test
    void resolve_absolutePathGlob_isRejected(@TempDir Path root) {
        var matches = resolver.resolve(root, List.of("/etc/passwd"));

        assertThat(matches).isEmpty();
    }

    @Test
    void resolve_symlinkPointingOutsideRoot_isRejected(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        Path secret = outside.resolve("secret.xml");
        Files.writeString(secret, "<r/>");
        try {
            Files.createSymbolicLink(root.resolve("link.xml"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            // symlink creation requires a privilege this CI/dev box may not grant — skip gracefully.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not supported on this box");
            return;
        }

        var matches = resolver.resolve(root, List.of("link.xml"));

        assertThat(matches).isEmpty();
    }

    @Test
    void resolve_nonExistentRoot_returnsEmptyWithoutThrowing() {
        var matches = resolver.resolve(Path.of("/does/not/exist/qo"), List.of("report.xml"));

        assertThat(matches).isEmpty();
    }

    @Test
    void resolve_noGlobs_returnsEmpty(@TempDir Path root) {
        assertThat(resolver.resolve(root, List.of())).isEmpty();
        assertThat(resolver.resolve(root, null)).isEmpty();
    }
}

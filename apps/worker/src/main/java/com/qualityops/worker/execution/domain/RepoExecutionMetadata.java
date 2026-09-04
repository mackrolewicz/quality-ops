package com.qualityops.worker.execution.domain;

import com.qualityops.events.RepositoryRunProvenance;
import com.qualityops.events.RepositoryTestItem;

import java.util.List;

/** What {@link com.qualityops.worker.execution.adapter.out.runner.RepositoryExecutionRunner}
 *  records about one repository-run case, carried on {@link CaseExecutionResult#repository()}
 *  (mirrors {@link BrowserRunMetadata}). Parsed items + provenance are already
 *  redacted and truncated by the time they land here. */
public record RepoExecutionMetadata(
        List<RepositoryTestItem> items,          // never null; may be empty
        RepositoryRunProvenance provenance,       // nullable ⇒ the framework container never ran
        String consoleLogTempPath,                // nullable — temp-only, staged for upload
        long consoleLogBytes,
        List<ReportCapture> reportCaptures        // never null; may be empty — report + artifactGlobs files
) {
    /** One staged file (a report or a globbed artifact) awaiting upload. */
    public record ReportCapture(String tempPath, long bytes, String objectName) {}
}

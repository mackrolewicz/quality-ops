import type { FieldErrors, UseFormRegister } from "react-hook-form";

import { Field } from "../../components/Field";
import { Select } from "../../components/Select";
import { TextInput } from "../../components/TextInput";
import type {
  RepoFramework,
  RepoNetworkPolicy,
  RepoResourceProfile,
  RepositoryConnectionResponse,
} from "../../api/types";
import type { TestCaseFormValues } from "./TestCaseForm";

interface RepoTestFormProps {
  register: UseFormRegister<TestCaseFormValues>;
  errors: FieldErrors<TestCaseFormValues>;
  connections: RepositoryConnectionResponse[];
}

const FRAMEWORKS: RepoFramework[] = [
  "PLAYWRIGHT",
  "JUNIT",
  "PYTEST",
  "CYPRESS",
  "K6",
];
const RESOURCE_PROFILES: RepoResourceProfile[] = ["SMALL", "MEDIUM", "LARGE"];
const NETWORK_POLICIES: RepoNetworkPolicy[] = ["ISOLATED", "EGRESS"];

/** ADR-009 §11 — the case editor's "Repository" tab. Authors a `RepoTestPayload`
 *  on the case; the run itself goes through the existing suite Run-now / CI /
 *  schedule flows (no "run now from a connection" — gap #1). Selecting a
 *  connection is what marks this case as repository-run on submit
 *  (`TestCaseForm.buildRepoTest`); leaving it unset keeps this a plain case. */
export function RepoTestForm({ register, errors, connections }: RepoTestFormProps) {
  return (
    <div className="space-y-4">
      <Field
        label="Repository connection"
        htmlFor="repo-test-connection"
        error={errors.repoConnectionId?.message}
        help="Leave unset to keep this a plain (non-repository) case."
      >
        <Select
          id="repo-test-connection"
          data-testid="repo-test-connection"
          {...register("repoConnectionId")}
        >
          <option value="">— None —</option>
          {connections.map((c) => (
            <option key={c.id} value={c.id}>
              {c.ownerPath}/{c.repoName}
            </option>
          ))}
        </Select>
      </Field>

      <Field label="Ref" htmlFor="repo-test-ref" error={errors.repoRequestedRef?.message}>
        <TextInput
          id="repo-test-ref"
          data-testid="repo-test-ref"
          placeholder="main"
          invalid={Boolean(errors.repoRequestedRef)}
          {...register("repoRequestedRef", {
            maxLength: { value: 255, message: "Ref is too long" },
          })}
        />
      </Field>

      <Field label="Framework" htmlFor="repo-test-framework">
        <Select
          id="repo-test-framework"
          data-testid="repo-test-framework"
          {...register("repoFramework")}
        >
          {FRAMEWORKS.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </Select>
      </Field>

      <Field
        label="Working directory"
        htmlFor="repo-test-working-dir"
        help="Optional — relative to the repository root."
      >
        <TextInput
          id="repo-test-working-dir"
          data-testid="repo-test-working-dir"
          placeholder="tests/"
          {...register("repoWorkingDir")}
        />
      </Field>

      <Field
        label="Command"
        htmlFor="repo-test-command"
        error={errors.repoCommand?.message}
        help="Space-separated argv, e.g. pytest --junitxml=report.xml"
      >
        <TextInput
          id="repo-test-command"
          data-testid="repo-test-command"
          placeholder="pytest --junitxml=report.xml"
          invalid={Boolean(errors.repoCommand)}
          {...register("repoCommand")}
        />
      </Field>

      <Field
        label="Report paths"
        htmlFor="repo-test-report-paths"
        help="Optional, comma-separated globs, e.g. report.xml"
      >
        <TextInput
          id="repo-test-report-paths"
          data-testid="repo-test-report-paths"
          placeholder="report.xml"
          {...register("repoReportPaths")}
        />
      </Field>

      <Field label="Resource profile" htmlFor="repo-test-resource-profile">
        <Select
          id="repo-test-resource-profile"
          data-testid="repo-test-resource-profile"
          {...register("repoResourceProfile")}
        >
          <option value="">Default (SMALL)</option>
          {RESOURCE_PROFILES.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </Select>
      </Field>

      <Field label="Network policy" htmlFor="repo-test-network-policy">
        <Select
          id="repo-test-network-policy"
          data-testid="repo-test-network-policy"
          {...register("repoNetworkPolicy")}
        >
          <option value="">Default (ISOLATED)</option>
          {NETWORK_POLICIES.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </Select>
      </Field>

      <Field
        label="Timeout (seconds)"
        htmlFor="repo-test-timeout"
        error={errors.repoTimeoutSeconds?.message}
        help="30–1800. Blank uses the platform default."
      >
        <TextInput
          id="repo-test-timeout"
          type="number"
          data-testid="repo-test-timeout"
          invalid={Boolean(errors.repoTimeoutSeconds)}
          {...register("repoTimeoutSeconds", {
            validate: (v) =>
              v === "" ||
              (Number.parseInt(v, 10) >= 30 && Number.parseInt(v, 10) <= 1800) ||
              "Must be between 30 and 1800",
          })}
        />
      </Field>
    </div>
  );
}

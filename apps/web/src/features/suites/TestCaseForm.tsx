import { useState } from "react";

import { useForm } from "react-hook-form";

import { useRepositoryConnections } from "../../api/repositories";
import { useCreateCase, useUpdateCase } from "../../api/cases";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { Tabs, type TabItem } from "../../components/Tabs";
import { TextInput } from "../../components/TextInput";
import type {
  RepoNetworkPolicy,
  RepoResourceProfile,
  RepoTestPayload,
  RepoFramework,
  TestCaseResponse,
} from "../../api/types";
import { RepoTestForm } from "./RepoTestForm";

export interface TestCaseFormValues {
  name: string;
  description: string;
  orderIndex: string;
  repoConnectionId: string;
  repoRequestedRef: string;
  repoFramework: RepoFramework;
  repoWorkingDir: string;
  repoCommand: string;
  repoReportPaths: string;
  repoResourceProfile: "" | RepoResourceProfile;
  repoNetworkPolicy: "" | RepoNetworkPolicy;
  repoTimeoutSeconds: string;
}

interface TestCaseFormProps {
  projectId: string;
  suiteId: string;
  testCase?: TestCaseResponse;
  nextOrderIndex: number;
  onDone: () => void;
  onCancel: () => void;
}

const CASE_TABS: TabItem[] = [
  { id: "details", label: "Details", testId: "case-tab-details" },
  { id: "repository", label: "Repository", testId: "case-tab-repository" },
];

/** ADR-009 §11 — the case editor's "Repository" tab is authored here alongside
 *  the plain details. Selecting a connection in the Repository tab is what
 *  turns this case into a repository-run case on submit; the run itself still
 *  goes through the existing suite Run-now / CI / schedule flows. */
function buildRepoTest(values: TestCaseFormValues): RepoTestPayload | undefined {
  if (!values.repoConnectionId) return undefined;
  const command = values.repoCommand.trim().split(/\s+/).filter(Boolean);
  const reportPaths = values.repoReportPaths
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  return {
    repositoryConnectionId: values.repoConnectionId,
    requestedRef: values.repoRequestedRef.trim() || "main",
    framework: values.repoFramework,
    workingDir: values.repoWorkingDir.trim() || undefined,
    command: command.length > 0 ? command : ["true"],
    reportFormat: values.repoFramework === "K6" ? "K6_SUMMARY_JSON" : "JUNIT_XML",
    reportPaths: reportPaths.length > 0 ? reportPaths : undefined,
    resourceProfile: values.repoResourceProfile || undefined,
    networkPolicy: values.repoNetworkPolicy || undefined,
    timeoutSeconds: values.repoTimeoutSeconds
      ? Number.parseInt(values.repoTimeoutSeconds, 10)
      : undefined,
  };
}

export function TestCaseForm({
  projectId,
  suiteId,
  testCase,
  nextOrderIndex,
  onDone,
  onCancel,
}: TestCaseFormProps) {
  const create = useCreateCase(suiteId);
  const update = useUpdateCase(suiteId, testCase?.id ?? "");
  const connections = useRepositoryConnections(projectId);
  const mutation = testCase ? update : create;
  const isEdit = Boolean(testCase);
  const [tab, setTab] = useState<string>("details");

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<TestCaseFormValues>({
    defaultValues: {
      name: testCase?.name ?? "",
      description: testCase?.description ?? "",
      orderIndex: String(testCase?.orderIndex ?? nextOrderIndex),
      repoConnectionId: testCase?.repoTest?.repositoryConnectionId ?? "",
      repoRequestedRef: testCase?.repoTest?.requestedRef ?? "",
      repoFramework: testCase?.repoTest?.framework ?? "PYTEST",
      repoWorkingDir: testCase?.repoTest?.workingDir ?? "",
      repoCommand: testCase?.repoTest?.command?.join(" ") ?? "",
      repoReportPaths: testCase?.repoTest?.reportPaths?.join(", ") ?? "",
      repoResourceProfile: testCase?.repoTest?.resourceProfile ?? "",
      repoNetworkPolicy: testCase?.repoTest?.networkPolicy ?? "",
      repoTimeoutSeconds: testCase?.repoTest?.timeoutSeconds
        ? String(testCase.repoTest.timeoutSeconds)
        : "",
    },
  });

  const onSubmit = (values: TestCaseFormValues) => {
    const name = values.name.trim();
    const description = values.description.trim() || undefined;
    const parsedOrder = Number.parseInt(values.orderIndex, 10);
    const repoTest = buildRepoTest(values);

    if (isEdit) {
      update.mutate(
        {
          name,
          description,
          orderIndex: Number.isNaN(parsedOrder) ? nextOrderIndex : parsedOrder,
          repoTest,
        },
        { onSuccess: onDone },
      );
    } else {
      create.mutate(
        {
          name,
          description,
          orderIndex: Number.isNaN(parsedOrder) ? undefined : parsedOrder,
          repoTest,
        },
        { onSuccess: onDone },
      );
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {mutation.isError && <ErrorState error={mutation.error} />}

      <Tabs tabs={CASE_TABS} active={tab} onChange={setTab} />

      <div className={tab === "details" ? "space-y-4" : "hidden"}>
        <Field label="Name" htmlFor="case-name" error={errors.name?.message}>
          <TextInput
            id="case-name"
            data-testid="case-name"
            invalid={Boolean(errors.name)}
            {...register("name", {
              required: "Name is required",
              maxLength: { value: 255, message: "Name is too long" },
            })}
          />
        </Field>

        <Field
          label="Description"
          htmlFor="case-description"
          error={errors.description?.message}
        >
          <TextInput
            id="case-description"
            data-testid="case-description"
            invalid={Boolean(errors.description)}
            {...register("description", {
              maxLength: { value: 2000, message: "Description is too long" },
            })}
          />
        </Field>

        <Field
          label="Order"
          htmlFor="case-order"
          error={errors.orderIndex?.message}
          help="Position within the suite."
        >
          <TextInput
            id="case-order"
            type="number"
            data-testid="case-order"
            invalid={Boolean(errors.orderIndex)}
            {...register("orderIndex", {
              pattern: {
                value: /^-?\d+$/,
                message: "Order must be a whole number",
              },
            })}
          />
        </Field>
      </div>

      <div className={tab === "repository" ? "space-y-4" : "hidden"}>
        <RepoTestForm
          register={register}
          errors={errors}
          connections={connections.data?.items ?? []}
        />
      </div>

      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          isLoading={mutation.isPending}
          data-testid="case-submit"
        >
          {isEdit ? "Save case" : "Add case"}
        </Button>
      </div>
    </form>
  );
}

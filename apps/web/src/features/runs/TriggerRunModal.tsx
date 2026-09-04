import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";

import { useCases } from "../../api/cases";
import { useEnvironments } from "../../api/environments";
import { useTriggerRun } from "../../api/runs";
import { useSuites } from "../../api/suites";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { Modal } from "../../components/Modal";
import { Select } from "../../components/Select";

interface TriggerRunModalProps {
  projectId: string;
  open: boolean;
  onClose: () => void;
  defaultSuiteId?: string;
}

interface TriggerRunFormValues {
  suiteId: string;
  environmentId: string;
}

export function TriggerRunModal({
  projectId,
  open,
  onClose,
  defaultSuiteId,
}: TriggerRunModalProps) {
  const navigate = useNavigate();
  const suites = useSuites(projectId);
  const environments = useEnvironments(projectId);
  const trigger = useTriggerRun();

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors },
  } = useForm<TriggerRunFormValues>({
    defaultValues: { suiteId: defaultSuiteId ?? "", environmentId: "" },
  });

  const selectedSuiteId = watch("suiteId");
  const cases = useCases(selectedSuiteId || undefined);
  const zeroCases =
    Boolean(selectedSuiteId) &&
    !cases.isLoading &&
    (cases.data?.meta.total ?? cases.data?.items.length ?? 0) === 0;

  const onSubmit = (values: TriggerRunFormValues) => {
    trigger.mutate(
      { projectId, suiteId: values.suiteId, environmentId: values.environmentId },
      {
        onSuccess: (run) => {
          reset();
          onClose();
          navigate(`/runs/${run.id}`);
        },
      },
    );
  };

  return (
    <Modal open={open} onClose={onClose} title="Trigger new run">
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        {trigger.isError && <ErrorState error={trigger.error} />}

        <Field
          label="Suite"
          htmlFor="run-suite-select"
          error={errors.suiteId?.message}
        >
          <Select
            id="run-suite-select"
            data-testid="run-suite-select"
            invalid={Boolean(errors.suiteId)}
            {...register("suiteId", { required: "Select a suite" })}
          >
            <option value="">Select a suite</option>
            {(suites.data?.items ?? []).map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label="Environment"
          htmlFor="run-env-select"
          error={errors.environmentId?.message}
        >
          <Select
            id="run-env-select"
            data-testid="run-env-select"
            invalid={Boolean(errors.environmentId)}
            {...register("environmentId", {
              required: "Select an environment",
            })}
          >
            <option value="">Select an environment</option>
            {(environments.data?.items ?? []).map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
              </option>
            ))}
          </Select>
        </Field>

        {zeroCases && (
          <p
            data-testid="run-zero-cases-warning"
            className="rounded-md border border-status-flaky/30 bg-status-flaky/10 p-3 text-xs text-status-flaky"
          >
            This suite has no test cases; the run will complete with no results.
          </p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            isLoading={trigger.isPending}
            data-testid="run-submit"
          >
            Trigger run
          </Button>
        </div>
      </form>
    </Modal>
  );
}

import { useForm } from "react-hook-form";

import { useCreateSuite, useUpdateSuite } from "../../api/suites";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { Select } from "../../components/Select";
import { TextInput } from "../../components/TextInput";
import type { SuiteType, TestSuiteResponse } from "../../api/types";

interface SuiteFormValues {
  name: string;
  description: string;
  type: SuiteType;
}

interface SuiteFormProps {
  projectId: string;
  suite?: TestSuiteResponse;
  onDone: () => void;
  onCancel: () => void;
}

const TYPES: SuiteType[] = ["API", "UI", "PERFORMANCE"];

export function SuiteForm({
  projectId,
  suite,
  onDone,
  onCancel,
}: SuiteFormProps) {
  const create = useCreateSuite(projectId);
  const update = useUpdateSuite(projectId, suite?.id ?? "");
  const mutation = suite ? update : create;

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SuiteFormValues>({
    defaultValues: {
      name: suite?.name ?? "",
      description: suite?.description ?? "",
      type: suite?.type ?? "API",
    },
  });

  const onSubmit = (values: SuiteFormValues) => {
    mutation.mutate(
      {
        name: values.name.trim(),
        description: values.description.trim() || undefined,
        type: values.type,
      },
      { onSuccess: onDone },
    );
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {mutation.isError && <ErrorState error={mutation.error} />}

      <Field label="Name" htmlFor="suite-name" error={errors.name?.message}>
        <TextInput
          id="suite-name"
          data-testid="suite-name"
          invalid={Boolean(errors.name)}
          {...register("name", {
            required: "Name is required",
            maxLength: { value: 255, message: "Name is too long" },
          })}
        />
      </Field>

      <Field
        label="Description"
        htmlFor="suite-description"
        error={errors.description?.message}
      >
        <TextInput
          id="suite-description"
          data-testid="suite-description"
          invalid={Boolean(errors.description)}
          {...register("description", {
            maxLength: { value: 2000, message: "Description is too long" },
          })}
        />
      </Field>

      <Field label="Type" htmlFor="suite-type" error={errors.type?.message}>
        <Select
          id="suite-type"
          data-testid="suite-type"
          {...register("type", { required: true })}
        >
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </Select>
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          isLoading={mutation.isPending}
          data-testid="suite-submit"
        >
          {suite ? "Save suite" : "Add suite"}
        </Button>
      </div>
    </form>
  );
}

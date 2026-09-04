import { useForm } from "react-hook-form";

import {
  useCreateEnvironment,
  useUpdateEnvironment,
} from "../../api/environments";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { Select } from "../../components/Select";
import { TextInput } from "../../components/TextInput";
import type {
  EnvironmentResponse,
  EnvironmentStatus,
  EnvironmentType,
} from "../../api/types";

interface EnvironmentFormValues {
  name: string;
  baseUrl: string;
  type: EnvironmentType;
  status: EnvironmentStatus;
}

interface EnvironmentFormProps {
  projectId: string;
  environment?: EnvironmentResponse;
  onDone: () => void;
  onCancel: () => void;
}

const TYPES: EnvironmentType[] = ["DEV", "STAGING", "PRODUCTION"];
const STATUSES: EnvironmentStatus[] = ["ACTIVE", "INACTIVE"];

function isValidUrl(value: string): boolean {
  try {
    new URL(value);
    return true;
  } catch {
    return false;
  }
}

export function EnvironmentForm({
  projectId,
  environment,
  onDone,
  onCancel,
}: EnvironmentFormProps) {
  const create = useCreateEnvironment(projectId);
  const update = useUpdateEnvironment(projectId, environment?.id ?? "");
  const mutation = environment ? update : create;
  const isEdit = Boolean(environment);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EnvironmentFormValues>({
    defaultValues: {
      name: environment?.name ?? "",
      baseUrl: environment?.baseUrl ?? "",
      type: environment?.type ?? "DEV",
      status: environment?.status ?? "ACTIVE",
    },
  });

  const onSubmit = (values: EnvironmentFormValues) => {
    if (isEdit) {
      update.mutate(
        {
          name: values.name.trim(),
          baseUrl: values.baseUrl.trim(),
          type: values.type,
          status: values.status,
        },
        { onSuccess: onDone },
      );
    } else {
      create.mutate(
        {
          name: values.name.trim(),
          baseUrl: values.baseUrl.trim(),
          type: values.type,
        },
        { onSuccess: onDone },
      );
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {mutation.isError && <ErrorState error={mutation.error} />}

      <Field label="Name" htmlFor="env-name" error={errors.name?.message}>
        <TextInput
          id="env-name"
          data-testid="env-name"
          invalid={Boolean(errors.name)}
          {...register("name", {
            required: "Name is required",
            maxLength: { value: 255, message: "Name is too long" },
          })}
        />
      </Field>

      <Field
        label="Base URL"
        htmlFor="env-baseurl"
        error={errors.baseUrl?.message}
      >
        <TextInput
          id="env-baseurl"
          data-testid="env-baseurl"
          placeholder="https://example.com"
          invalid={Boolean(errors.baseUrl)}
          {...register("baseUrl", {
            required: "Base URL is required",
            maxLength: { value: 2048, message: "URL is too long" },
            validate: (v) => isValidUrl(v) || "Enter a valid URL",
          })}
        />
      </Field>

      <Field label="Type" htmlFor="env-type" error={errors.type?.message}>
        <Select
          id="env-type"
          data-testid="env-type"
          {...register("type", { required: true })}
        >
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </Select>
      </Field>

      {isEdit && (
        <Field label="Status" htmlFor="env-status" error={errors.status?.message}>
          <Select
            id="env-status"
            data-testid="env-status"
            {...register("status", { required: true })}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
        </Field>
      )}

      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          isLoading={mutation.isPending}
          data-testid="env-submit"
        >
          {isEdit ? "Save environment" : "Add environment"}
        </Button>
      </div>
    </form>
  );
}

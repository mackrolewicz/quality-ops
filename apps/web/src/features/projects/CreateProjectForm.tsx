import { useEffect, useRef } from "react";

import { useForm } from "react-hook-form";

import { useCreateProject } from "../../api/projects";
import { SLUG_PATTERN, toKebabCase } from "../../lib/slug";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { TextInput } from "../../components/TextInput";
import type { ProjectResponse } from "../../api/types";

interface CreateProjectFormValues {
  name: string;
  description: string;
  slug: string;
}

interface CreateProjectFormProps {
  onCreated: (project: ProjectResponse) => void;
  onCancel: () => void;
}

export function CreateProjectForm({
  onCreated,
  onCancel,
}: CreateProjectFormProps) {
  const create = useCreateProject();
  const slugEdited = useRef(false);

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<CreateProjectFormValues>({
    defaultValues: { name: "", description: "", slug: "" },
  });

  const nameValue = watch("name");

  useEffect(() => {
    if (!slugEdited.current) {
      setValue("slug", toKebabCase(nameValue));
    }
  }, [nameValue, setValue]);

  const onSubmit = (values: CreateProjectFormValues) => {
    create.mutate(
      {
        name: values.name.trim(),
        description: values.description.trim() || undefined,
        slug: values.slug.trim(),
      },
      { onSuccess: onCreated },
    );
  };

  const slugField = register("slug", {
    required: "Slug is required",
    maxLength: { value: 100, message: "Slug is too long" },
    pattern: {
      value: SLUG_PATTERN,
      message: "Use lowercase letters, numbers and single hyphens",
    },
  });

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {create.isError && <ErrorState error={create.error} />}

      <Field label="Name" htmlFor="project-name" error={errors.name?.message}>
        <TextInput
          id="project-name"
          data-testid="project-name"
          invalid={Boolean(errors.name)}
          {...register("name", {
            required: "Name is required",
            maxLength: { value: 255, message: "Name is too long" },
          })}
        />
      </Field>

      <Field
        label="Description"
        htmlFor="project-description"
        error={errors.description?.message}
      >
        <TextInput
          id="project-description"
          data-testid="project-description"
          invalid={Boolean(errors.description)}
          {...register("description", {
            maxLength: { value: 2000, message: "Description is too long" },
          })}
        />
      </Field>

      <Field
        label="Slug"
        htmlFor="project-slug"
        error={errors.slug?.message}
        help="Used in URLs. Lowercase, hyphen-separated."
      >
        <TextInput
          id="project-slug"
          data-testid="project-slug"
          invalid={Boolean(errors.slug)}
          {...slugField}
          onChange={(e) => {
            slugEdited.current = true;
            slugField.onChange(e);
          }}
        />
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          isLoading={create.isPending}
          data-testid="project-submit"
        >
          Create project
        </Button>
      </div>
    </form>
  );
}

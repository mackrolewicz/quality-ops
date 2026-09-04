import { useForm } from "react-hook-form";

import { useUpdateProject } from "../../api/projects";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { TextInput } from "../../components/TextInput";
import type { ProjectResponse } from "../../api/types";

interface EditProjectFormValues {
  name: string;
  description: string;
}

interface EditProjectFormProps {
  project: ProjectResponse;
  onSaved: () => void;
  onCancel: () => void;
}

export function EditProjectForm({
  project,
  onSaved,
  onCancel,
}: EditProjectFormProps) {
  const update = useUpdateProject(project.id);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EditProjectFormValues>({
    defaultValues: {
      name: project.name,
      description: project.description ?? "",
    },
  });

  const onSubmit = (values: EditProjectFormValues) => {
    update.mutate(
      {
        name: values.name.trim(),
        description: values.description.trim() || undefined,
      },
      { onSuccess: onSaved },
    );
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {update.isError && <ErrorState error={update.error} />}

      <Field label="Name" htmlFor="edit-project-name" error={errors.name?.message}>
        <TextInput
          id="edit-project-name"
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
        htmlFor="edit-project-description"
        error={errors.description?.message}
      >
        <TextInput
          id="edit-project-description"
          data-testid="project-description"
          invalid={Boolean(errors.description)}
          {...register("description", {
            maxLength: { value: 2000, message: "Description is too long" },
          })}
        />
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="submit"
          isLoading={update.isPending}
          data-testid="project-submit"
        >
          Save changes
        </Button>
      </div>
    </form>
  );
}

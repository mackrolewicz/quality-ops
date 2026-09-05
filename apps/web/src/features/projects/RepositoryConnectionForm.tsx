import { useForm } from "react-hook-form";

import {
  useCreateRepositoryConnection,
  useUpdateRepositoryConnection,
} from "../../api/repositories";
import { Button } from "../../components/Button";
import { ErrorState } from "../../components/ErrorState";
import { Field } from "../../components/Field";
import { Select } from "../../components/Select";
import { TextInput } from "../../components/TextInput";
import type {
  RepositoryConnectionResponse,
  RepositoryProvider,
} from "../../api/types";

interface RepositoryConnectionFormValues {
  provider: RepositoryProvider;
  host: string;
  ownerPath: string;
  repoName: string;
  defaultRef: string;
  credentialRef: string;
}

interface RepositoryConnectionFormProps {
  projectId: string;
  connection?: RepositoryConnectionResponse;
  onDone: () => void;
  onCancel: () => void;
}

const PROVIDERS: RepositoryProvider[] = ["GITHUB", "GITLAB"];
const CREDENTIAL_REF_PATTERN = /^[A-Z0-9_]{1,64}$/;

export function RepositoryConnectionForm({
  projectId,
  connection,
  onDone,
  onCancel,
}: RepositoryConnectionFormProps) {
  const create = useCreateRepositoryConnection(projectId);
  const update = useUpdateRepositoryConnection(projectId, connection?.id ?? "");
  const mutation = connection ? update : create;
  const isEdit = Boolean(connection);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RepositoryConnectionFormValues>({
    defaultValues: {
      provider: connection?.provider ?? "GITHUB",
      host: connection?.host ?? "",
      ownerPath: connection?.ownerPath ?? "",
      repoName: connection?.repoName ?? "",
      defaultRef: connection?.defaultRef ?? "main",
      credentialRef: connection?.credentialRef ?? "",
    },
  });

  const onSubmit = (values: RepositoryConnectionFormValues) => {
    const shared = {
      host: values.host.trim() || undefined,
      ownerPath: values.ownerPath.trim(),
      repoName: values.repoName.trim(),
      defaultRef: values.defaultRef.trim() || undefined,
      credentialRef: values.credentialRef.trim() || undefined,
    };
    if (isEdit) {
      update.mutate(
        { ...shared, defaultRef: shared.defaultRef ?? "main" },
        { onSuccess: onDone },
      );
    } else {
      create.mutate({ provider: values.provider, ...shared }, { onSuccess: onDone });
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {mutation.isError && <ErrorState error={mutation.error} />}

      <Field label="Provider" htmlFor="repo-provider" error={errors.provider?.message}>
        <Select
          id="repo-provider"
          data-testid="repo-provider"
          disabled={isEdit}
          {...register("provider", { required: true })}
        >
          {PROVIDERS.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </Select>
      </Field>

      <Field
        label="Host"
        htmlFor="repo-host"
        error={errors.host?.message}
        help="Blank uses the provider's public host (github.com / gitlab.com)."
      >
        <TextInput
          id="repo-host"
          data-testid="repo-host"
          placeholder="github.com"
          invalid={Boolean(errors.host)}
          {...register("host", { maxLength: { value: 255, message: "Host is too long" } })}
        />
      </Field>

      <Field label="Owner / path" htmlFor="repo-owner" error={errors.ownerPath?.message}>
        <TextInput
          id="repo-owner"
          data-testid="repo-owner"
          placeholder="acme"
          invalid={Boolean(errors.ownerPath)}
          {...register("ownerPath", {
            required: "Owner path is required",
            maxLength: { value: 512, message: "Owner path is too long" },
          })}
        />
      </Field>

      <Field label="Repository name" htmlFor="repo-name" error={errors.repoName?.message}>
        <TextInput
          id="repo-name"
          data-testid="repo-name"
          placeholder="web-app"
          invalid={Boolean(errors.repoName)}
          {...register("repoName", {
            required: "Repository name is required",
            maxLength: { value: 255, message: "Repository name is too long" },
          })}
        />
      </Field>

      <Field label="Default ref" htmlFor="repo-default-ref" error={errors.defaultRef?.message}>
        <TextInput
          id="repo-default-ref"
          data-testid="repo-default-ref"
          placeholder="main"
          invalid={Boolean(errors.defaultRef)}
          {...register("defaultRef", {
            maxLength: { value: 255, message: "Default ref is too long" },
          })}
        />
      </Field>

      <Field
        label="Credential ref"
        htmlFor="repo-credential-ref"
        error={errors.credentialRef?.message}
        help="The opaque secret key name (e.g. QUALITYOPS_SCM_CREDENTIAL_DEMO), never a token. Leave blank for a public repo."
      >
        <TextInput
          id="repo-credential-ref"
          data-testid="repo-credential-ref"
          placeholder="DEMO"
          invalid={Boolean(errors.credentialRef)}
          {...register("credentialRef", {
            validate: (v) =>
              v === "" ||
              CREDENTIAL_REF_PATTERN.test(v) ||
              "Must match [A-Z0-9_]{1,64}",
          })}
        />
      </Field>

      <div className="flex justify-end gap-2 pt-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" isLoading={mutation.isPending} data-testid="repo-submit">
          {isEdit ? "Save connection" : "Add connection"}
        </Button>
      </div>
    </form>
  );
}

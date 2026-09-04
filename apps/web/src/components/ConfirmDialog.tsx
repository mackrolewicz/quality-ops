import { Button } from "./Button";
import { Modal } from "./Modal";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  body: string;
  confirmLabel?: string;
  isLoading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  confirmTestId?: string;
}

export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = "Delete",
  isLoading = false,
  onConfirm,
  onCancel,
  confirmTestId,
}: ConfirmDialogProps) {
  return (
    <Modal open={open} onClose={onCancel} title={title}>
      <p className="text-sm text-muted">{body}</p>
      <div className="mt-6 flex justify-end gap-2">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          variant="danger"
          isLoading={isLoading}
          onClick={onConfirm}
          data-testid={confirmTestId}
        >
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}

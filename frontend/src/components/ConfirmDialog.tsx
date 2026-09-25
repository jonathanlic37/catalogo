// frontend/src/components/ConfirmDialog.tsx
import { RotateCcw, Trash2 } from 'lucide-react';
import { useEffect, useRef } from 'react';

interface Props {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Diálogo modal nativo (<dialog>): gestiona foco, Escape y capa de fondo de forma accesible. */
export function ConfirmDialog({ open, title, message, confirmLabel, danger = true, onConfirm, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="confirm"
      aria-labelledby="confirm-title"
      onCancel={(e) => {
        e.preventDefault();
        onCancel();
      }}
      onClick={(e) => {
        if (e.target === ref.current) onCancel();
      }}
    >
      <div className={danger ? 'confirm-icon' : 'confirm-icon neutral'} aria-hidden="true">
        {danger ? <Trash2 size={22} /> : <RotateCcw size={22} />}
      </div>
      <h2 id="confirm-title">{title}</h2>
      <p>{message}</p>
      <div className="confirm-actions">
        <button className="btn" onClick={onCancel} autoFocus>
          Cancelar
        </button>
        <button className={danger ? 'btn btn-danger' : 'btn btn-primary'} onClick={onConfirm}>
          {confirmLabel}
        </button>
      </div>
    </dialog>
  );
}

// frontend/src/components/ui/StatCard.tsx
import type { ReactNode } from 'react';

export type StatTone = 'total' | 'ok' | 'warn' | 'danger';

export interface StatCardProps {
  icon: ReactNode;
  tone: StatTone;
  label: string;
  value?: number | string;
  hint?: string;
  /** Si se indica, la tarjeta es un atajo (p. ej. filtra la tabla) y se renderiza como botón. */
  onClick?: () => void;
  active?: boolean;
}

/** Tarjeta de resumen de cristal. Como atajo expone aria-pressed para que el estado sea anunciable. */
export function StatCard({ icon, tone, label, value, hint, onClick, active = false }: StatCardProps) {
  const content = (
    <>
      <div className={`stat-icon tone-${tone}`} aria-hidden="true">
        {icon}
      </div>
      <div>
        <div className="stat-value">{value ?? '–'}</div>
        <div className="stat-label">{label}</div>
        {hint && <div className="stat-hint">{hint}</div>}
      </div>
    </>
  );

  if (!onClick) return <div className="stat">{content}</div>;
  return (
    <button
      type="button"
      className={active ? 'stat stat-button active' : 'stat stat-button'}
      onClick={onClick}
      aria-pressed={active}
      title={active ? 'Quitar filtro' : `Ver solo: ${label.toLowerCase()}`}
    >
      {content}
    </button>
  );
}

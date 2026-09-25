// frontend/src/components/ui/StatusPill.tsx
import { AlertTriangle, CheckCircle2, Clock, MinusCircle, type LucideIcon } from 'lucide-react';

/** Tono semántico del sistema DAKA Glass: verde activo, amarillo pendiente, rojo error, gris inactivo. */
export type StatusTone = 'ok' | 'warn' | 'danger' | 'muted';

const TONE: Record<StatusTone, { className: string; icon: LucideIcon }> = {
  ok: { className: 'pill pill-ok', icon: CheckCircle2 },
  warn: { className: 'pill pill-warn', icon: Clock },
  danger: { className: 'pill pill-danger', icon: AlertTriangle },
  muted: { className: 'pill pill-muted', icon: MinusCircle },
};

export interface StatusPillProps {
  tone: StatusTone;
  label: string;
  /** Icono alternativo; por defecto, el del tono. El color nunca es la única señal. */
  icon?: LucideIcon;
}

/** Pill de estado: texto sólido sobre fondo translúcido al 15% del mismo color, siempre con icono. */
export function StatusPill({ tone, label, icon }: StatusPillProps) {
  const { className, icon: DefaultIcon } = TONE[tone];
  const Icon = icon ?? DefaultIcon;
  return (
    <span className={className}>
      <Icon size={14} aria-hidden="true" />
      {label}
    </span>
  );
}

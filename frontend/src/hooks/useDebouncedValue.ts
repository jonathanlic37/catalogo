// frontend/src/hooks/useDebouncedValue.ts
import { useEffect, useState } from 'react';

/** Devuelve `value` tras `delayMs` sin cambios: evita una petición al servidor por cada tecla. */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

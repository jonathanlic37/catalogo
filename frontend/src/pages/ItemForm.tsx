// frontend/src/pages/ItemForm.tsx
import { AlertCircle, ArrowLeft, Info, Save } from 'lucide-react';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useItem, useSaveItem } from '../hooks/useItems';
import type { Estado, ItemInput, TipoContenido } from '../types/item';

const EMPTY: ItemInput = { nombre: '', descripcion: '', estado: 'ACTIVO', tipo: 'PRODUCTO' };
const TIPOS: { value: TipoContenido; label: string }[] = [
  { value: 'PRODUCTO', label: 'Producto' },
  { value: 'SERVICIO', label: 'Servicio' },
  { value: 'CONTENIDO', label: 'Contenido' },
];
const MAX_NOMBRE = 120;
const MAX_DESC = 1000;

export function ItemForm() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const existing = useItem(id);
  const save = useSaveItem(id);
  const [form, setForm] = useState<ItemInput>(EMPTY);
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});

  // Se rellena una sola vez: una recarga en segundo plano (p. ej. al volver a la pestaña) no debe
  // pisar lo que el usuario ya está escribiendo.
  const initialized = useRef(false);
  useEffect(() => {
    if (existing.data && !initialized.current) {
      initialized.current = true;
      setForm({
        nombre: existing.data.nombre,
        descripcion: existing.data.descripcion ?? '',
        estado: existing.data.estado,
        tipo: existing.data.tipo ?? 'PRODUCTO',
      });
    }
  }, [existing.data]);

  const serverErrors = save.error instanceof ApiError ? save.error.fieldErrors : {};
  const errors = { ...serverErrors, ...clientErrors };

  function validate(): Record<string, string> {
    const e: Record<string, string> = {};
    if (!form.nombre.trim()) e.nombre = 'El nombre es obligatorio';
    else if (form.nombre.length > MAX_NOMBRE) e.nombre = `Máximo ${MAX_NOMBRE} caracteres`;
    if (form.descripcion.length > MAX_DESC) e.descripcion = `Máximo ${MAX_DESC} caracteres`;
    return e;
  }

  function onSubmit(ev: FormEvent) {
    ev.preventDefault();
    const found = validate();
    setClientErrors(found);
    if (Object.keys(found).length > 0) return;
    save.mutate(form, { onSuccess: () => navigate('/') });
  }

  if (id && existing.isLoading) return <p aria-busy="true">Cargando…</p>;
  if (id && existing.error)
    return (
      <div className="alert" role="alert">
        <AlertCircle size={18} aria-hidden="true" /> {existing.error.message}
      </div>
    );

  return (
    <section>
      <Link className="back" to="/">
        <ArrowLeft size={18} aria-hidden="true" /> Volver al catálogo
      </Link>

      <form className="card form-card" onSubmit={onSubmit} noValidate>
        <h1>{id ? 'Editar ítem' : 'Nuevo ítem'}</h1>
        <p className="hint" style={{ marginTop: 0 }}>
          {id ? 'Modifica los datos y guarda para enviarlos a la fuente de verdad.' : 'Completa los datos del nuevo ítem.'}
        </p>

        <div className="field">
          <div className="field-row">
            <label htmlFor="nombre">Nombre</label>
            <span className="counter">
              {form.nombre.length}/{MAX_NOMBRE}
            </span>
          </div>
          <input
            id="nombre"
            type="text"
            value={form.nombre}
            maxLength={MAX_NOMBRE}
            autoFocus={!id}
            placeholder="Ej. Café molido 500 g"
            aria-invalid={!!errors.nombre}
            aria-describedby={errors.nombre ? 'nombre-error' : undefined}
            onChange={(e) => setForm({ ...form, nombre: e.target.value })}
          />
          {errors.nombre && (
            <div className="field-error" id="nombre-error" role="alert">
              <AlertCircle size={15} aria-hidden="true" /> {errors.nombre}
            </div>
          )}
        </div>

        <div className="field">
          <div className="field-row">
            <label htmlFor="descripcion">Descripción</label>
            <span className="counter">
              {form.descripcion.length}/{MAX_DESC}
            </span>
          </div>
          <textarea
            id="descripcion"
            value={form.descripcion}
            placeholder="Detalles opcionales del ítem"
            aria-invalid={!!errors.descripcion}
            aria-describedby={errors.descripcion ? 'descripcion-error' : undefined}
            onChange={(e) => setForm({ ...form, descripcion: e.target.value })}
          />
          {errors.descripcion && (
            <div className="field-error" id="descripcion-error" role="alert">
              <AlertCircle size={15} aria-hidden="true" /> {errors.descripcion}
            </div>
          )}
        </div>

        <div className="field">
          <span className="label" id="tipo-label">
            Tipo de contenido
          </span>
          <div className="segmented" role="radiogroup" aria-labelledby="tipo-label">
            {TIPOS.map((t) => (
              <label key={t.value}>
                <input
                  type="radio"
                  name="tipo"
                  checked={form.tipo === t.value}
                  onChange={() => setForm({ ...form, tipo: t.value })}
                />
                <span>{t.label}</span>
              </label>
            ))}
          </div>
        </div>

        <div className="field">
          <span className="label" id="estado-label">
            Estado
          </span>
          <div className="segmented" role="radiogroup" aria-labelledby="estado-label">
            {(['ACTIVO', 'INACTIVO'] as Estado[]).map((value) => (
              <label key={value}>
                <input
                  type="radio"
                  name="estado"
                  checked={form.estado === value}
                  onChange={() => setForm({ ...form, estado: value })}
                />
                <span>{value === 'ACTIVO' ? 'Activo' : 'Inactivo'}</span>
              </label>
            ))}
          </div>
        </div>

        {save.error && !Object.keys(serverErrors).length && (
          <div className="alert" role="alert" style={{ marginTop: '1.25rem' }}>
            <AlertCircle size={18} aria-hidden="true" /> {save.error.message}
          </div>
        )}

        <div className="form-note">
          <Info size={18} aria-hidden="true" />
          <span>El cambio se guarda de inmediato y queda pendiente hasta que la fuente de verdad lo confirme.</span>
        </div>

        <div className="form-actions">
          <button className="btn btn-primary" type="submit" disabled={save.isPending}>
            <Save size={18} aria-hidden="true" /> {save.isPending ? 'Guardando…' : 'Guardar'}
          </button>
          <Link className="btn" to="/">
            Cancelar
          </Link>
        </div>
      </form>
    </section>
  );
}

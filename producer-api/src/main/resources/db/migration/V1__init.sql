-- Esquema inicial, volcado del DDL que genera Hibernate (schema-generation scripts), no escrito a mano.
-- IF NOT EXISTS: las BD creadas antes con ddl-auto=update ya tienen estas tablas (baseline en versión 0).
create table if not exists idempotency_records (status_code integer not null, created_at timestamp not null, request_hash varchar(64) not null, key varchar(100) not null, response_body varchar(4000) not null, primary key (key));
create table if not exists items (fecha_actualizacion timestamp not null, fecha_creacion timestamp not null, last_event_at timestamp, version bigint not null, estado varchar(20) not null check (estado in ('ACTIVO','INACTIVO')), tipo varchar(20) check (tipo in ('PRODUCTO','SERVICIO','CONTENIDO')), id varchar(36) not null, nombre varchar(120) not null, descripcion varchar(1000), primary key (id));
create index if not exists idx_idem_created_at on idempotency_records (created_at);
create index if not exists idx_items_fecha_creacion on items (fecha_creacion, id);

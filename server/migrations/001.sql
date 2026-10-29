CREATE TABLE IF NOT EXISTS participants (
  id UUID PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS crossing_snapshots (
  id BIGSERIAL PRIMARY KEY,
  crossing_id TEXT NOT NULL,
  wait_a INTEGER NOT NULL CHECK (wait_a BETWEEN 0 AND 1440),
  wait_b INTEGER NOT NULL CHECK (wait_b BETWEEN 0 AND 1440),
  boats INTEGER NOT NULL CHECK (boats BETWEEN 0 AND 50),
  operating BOOLEAN NOT NULL,
  weather_a TEXT,
  weather_b TEXT,
  period TEXT,
  notice TEXT,
  source TEXT NOT NULL CHECK (source IN ('oficial','simulado')),
  source_time TIMESTAMPTZ,
  collected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS crossing_snapshots_latest ON crossing_snapshots(crossing_id,collected_at DESC);
CREATE TABLE IF NOT EXISTS collector_state (
  id SMALLINT PRIMARY KEY CHECK (id = 1),
  mode TEXT NOT NULL,
  feed_url TEXT,
  last_attempt_at TIMESTAMPTZ,
  last_success_at TIMESTAMPTZ,
  last_error TEXT,
  failures INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS reports (
  id UUID PRIMARY KEY,
  author_id UUID NOT NULL REFERENCES participants(id),
  crossing_id TEXT NOT NULL CHECK (crossing_id IN ('santos-guaruja','santos-vicente-de-carvalho','bertioga-guaruja')),
  side CHAR(1) NOT NULL CHECK (side IN ('A','B')),
  category TEXT NOT NULL CHECK (category IN ('fila','embarque_lento','interrompida','clima','acidente')),
  wait_minutes INTEGER CHECK (wait_minutes BETWEEN 0 AND 600),
  description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 400),
  latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','resolved')),
  version INTEGER NOT NULL DEFAULT 1,
  idempotency_key UUID NOT NULL,
  request_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(author_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS reports_recent ON reports(crossing_id,created_at DESC);
CREATE TABLE IF NOT EXISTS outbox_events (
  id UUID PRIMARY KEY,
  payload JSONB NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS outbox_events_pending ON outbox_events(created_at) WHERE published_at IS NULL;

-- Bastidores: sinal de vida de cada processo, entrega de cada evento por réplica e contadores didáticos.
CREATE TABLE IF NOT EXISTS node_heartbeats (
  node TEXT PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('api','worker','collector')),
  info JSONB NOT NULL DEFAULT '{}',
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS event_deliveries (
  event_id UUID NOT NULL,
  replica TEXT NOT NULL,
  clients INTEGER NOT NULL DEFAULT 0,
  delivered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(event_id,replica)
);
CREATE INDEX IF NOT EXISTS event_deliveries_recent ON event_deliveries(delivered_at);
CREATE TABLE IF NOT EXISTS system_counters (
  name TEXT PRIMARY KEY,
  value BIGINT NOT NULL DEFAULT 0
);

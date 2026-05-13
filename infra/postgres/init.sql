CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS storage_bootstrap (
  id text PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO storage_bootstrap (id)
VALUES ('olla-nest-postgres-ready')
ON CONFLICT (id) DO NOTHING;

-- Base schema: two tables used by scheduler + load endpoints.
CREATE TABLE IF NOT EXISTS performance_data (
  id           BIGSERIAL PRIMARY KEY,
  timestamp    TIMESTAMPTZ NOT NULL DEFAULT NOW(),  -- we keep timestamptz
  metric_name  VARCHAR(50)  NOT NULL,
  metric_value DOUBLE PRECISION NOT NULL,
  metadata     JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_perf_ts ON performance_data (timestamp DESC);

CREATE TABLE IF NOT EXISTS computation_results (
  id               BIGSERIAL PRIMARY KEY,
  timestamp        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  computation_type VARCHAR(50) NOT NULL,
  input_size       INTEGER     NOT NULL,
  result           TEXT        NOT NULL,
  duration_ms      DOUBLE PRECISION NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_comp_ts ON computation_results (timestamp DESC);

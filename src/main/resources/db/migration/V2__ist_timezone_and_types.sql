-- Ensure database sessions default to IST for human-readable timestamps.
-- (Clients can still override; app also sets JDBC TZ.)
DO $$
BEGIN
  BEGIN
    EXECUTE 'ALTER DATABASE appdb SET timezone TO ''Asia/Kolkata''';
  EXCEPTION WHEN others THEN
    -- ignore if not superuser / not permitted in some managed setups
    RAISE NOTICE 'Skipping ALTER DATABASE timezone (insufficient privileges)';
  END;
END$$;

-- Columns already timestamptz in V1; keep for idempotency/readability.
ALTER TABLE performance_data
  ALTER COLUMN timestamp TYPE TIMESTAMPTZ;
ALTER TABLE computation_results
  ALTER COLUMN timestamp TYPE TIMESTAMPTZ;

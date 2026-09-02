-- H2는 기본 NULLS DISTINCT 동작을 유지해야 한다.
SET LOCK_TIMEOUT 5000;

ALTER TABLE batch_job_execution ADD COLUMN IF NOT EXISTS active_key VARCHAR(16);

UPDATE batch_job_execution
SET active_key = CASE
    WHEN status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') THEN 'ACTIVE'
    ELSE NULL
END;

ALTER TABLE batch_job_execution ALTER COLUMN params_hash SET NOT NULL;

ALTER TABLE batch_job_execution ADD CONSTRAINT IF NOT EXISTS batch_job_exec_status_active_key_chk CHECK (
    (status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') AND active_key = 'ACTIVE')
    OR (status IN ('COMPLETED', 'COMPLETED_WITH_SKIPS') AND active_key IS NULL)
);

ALTER TABLE batch_job_execution ADD CONSTRAINT IF NOT EXISTS batch_job_exec_active_params_hash_v2_chk CHECK (
    active_key IS NULL
    OR params_hash = ''
    OR REGEXP_LIKE(params_hash, '^[0-9a-f]{64}$')
);

DROP INDEX IF EXISTS batch_job_exec_active_uidx;
DROP INDEX IF EXISTS batch_job_execution_active_uidx;
CREATE UNIQUE INDEX IF NOT EXISTS batch_job_execution_active_uidx
    ON batch_job_execution (job_name, params_hash, active_key);

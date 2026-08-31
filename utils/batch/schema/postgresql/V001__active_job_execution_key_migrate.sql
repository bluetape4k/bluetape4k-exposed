-- 기존 writer를 정지한 maintenance window에서 실행한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE batch_job_execution ADD COLUMN active_key VARCHAR(16);

UPDATE batch_job_execution
SET active_key = CASE
    WHEN status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') THEN 'ACTIVE'
    ELSE NULL
END;

ALTER TABLE batch_job_execution ALTER COLUMN params_hash SET NOT NULL;

ALTER TABLE batch_job_execution ADD CONSTRAINT batch_job_exec_status_active_key_chk CHECK (
    (status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') AND active_key = 'ACTIVE')
    OR (status IN ('COMPLETED', 'COMPLETED_WITH_SKIPS') AND active_key IS NULL)
);

DROP INDEX IF EXISTS batch_job_exec_active_uidx;
CREATE UNIQUE INDEX batch_job_execution_active_uidx
    ON batch_job_execution (job_name, params_hash, active_key);
COMMIT;

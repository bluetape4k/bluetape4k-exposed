-- CHECK를 강제하는 MySQL 8.0.16+ InnoDB에서 실행한다.
SET SESSION innodb_lock_wait_timeout = 5;

ALTER TABLE batch_job_execution ADD COLUMN active_key VARCHAR(16) NULL;

UPDATE batch_job_execution
SET active_key = CASE
    WHEN status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') THEN 'ACTIVE'
    ELSE NULL
END;

ALTER TABLE batch_job_execution MODIFY params_hash VARCHAR(64) NOT NULL;

ALTER TABLE batch_job_execution ADD CONSTRAINT batch_job_exec_status_active_key_chk CHECK (
    (status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') AND active_key = 'ACTIVE')
    OR (status IN ('COMPLETED', 'COMPLETED_WITH_SKIPS') AND active_key IS NULL)
);

CREATE UNIQUE INDEX batch_job_execution_active_uidx
    ON batch_job_execution (job_name, params_hash, active_key);

-- MySQL 8.0.16+ InnoDB 읽기 전용 사전 점검.
SET SESSION innodb_lock_wait_timeout = 5;

SELECT status, COUNT(*) AS row_count
FROM batch_job_execution
GROUP BY status
HAVING status IS NULL
    OR status NOT IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED', 'COMPLETED', 'COMPLETED_WITH_SKIPS');

SELECT status, COUNT(*) AS null_params_hash_count
FROM batch_job_execution
WHERE params_hash IS NULL
GROUP BY status;

SELECT job_name, params_hash, COUNT(*) AS active_row_count
FROM batch_job_execution
WHERE status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED')
GROUP BY job_name, params_hash
HAVING job_name IS NULL OR params_hash IS NULL OR COUNT(*) > 1;

SELECT engine
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'batch_job_execution'
  AND engine <> 'InnoDB';

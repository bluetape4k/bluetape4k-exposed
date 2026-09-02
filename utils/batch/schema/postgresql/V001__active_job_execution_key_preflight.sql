-- Issue #771 읽기 전용 사전 점검.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

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

SELECT COUNT(*) AS legacy_active_params_hash_count
FROM batch_job_execution
WHERE status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED')
  AND params_hash <> ''
  AND params_hash !~ '^[0-9a-f]{64}$';

SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'batch_job_execution'
  AND indexname IN ('batch_job_exec_active_uidx', 'batch_job_execution_active_uidx');
ROLLBACK;

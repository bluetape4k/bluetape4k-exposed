-- 트래픽 재개 전에 모든 진단 row/count가 없어야 한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

SELECT COUNT(*) AS invalid_mapping_count
FROM batch_job_execution
WHERE (status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') AND active_key IS DISTINCT FROM 'ACTIVE')
   OR (status IN ('COMPLETED', 'COMPLETED_WITH_SKIPS') AND active_key IS NOT NULL)
   OR params_hash IS NULL;

SELECT job_name, params_hash, COUNT(*) AS active_row_count
FROM batch_job_execution
WHERE active_key = 'ACTIVE'
GROUP BY job_name, params_hash
HAVING COUNT(*) > 1;

SELECT COUNT(*) AS legacy_active_params_hash_count
FROM batch_job_execution
WHERE active_key = 'ACTIVE'
  AND params_hash <> ''
  AND params_hash !~ '^[0-9a-f]{64}$';

SELECT indexname
FROM pg_indexes
WHERE tablename = 'batch_job_execution'
  AND indexname = 'batch_job_execution_active_uidx';
ROLLBACK;

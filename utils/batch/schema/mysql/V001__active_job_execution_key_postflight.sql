-- 트래픽 재개 전에 모든 진단 row/count가 없어야 한다.
SET SESSION innodb_lock_wait_timeout = 5;

SELECT COUNT(*) AS invalid_mapping_count
FROM batch_job_execution
WHERE (status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') AND (active_key IS NULL OR active_key <> 'ACTIVE'))
   OR (status IN ('COMPLETED', 'COMPLETED_WITH_SKIPS') AND active_key IS NOT NULL)
   OR params_hash IS NULL;

SELECT job_name, params_hash, COUNT(*) AS active_row_count
FROM batch_job_execution
WHERE active_key = 'ACTIVE'
GROUP BY job_name, params_hash
HAVING COUNT(*) > 1;

SELECT index_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'batch_job_execution'
  AND index_name = 'batch_job_execution_active_uidx';

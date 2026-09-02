-- 트래픽 재개 전에 모든 count가 0이고 index row가 존재해야 한다.
SET LOCK_TIMEOUT 5000;

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
  AND NOT REGEXP_LIKE(params_hash, '^[0-9a-f]{64}$');

SELECT INDEX_NAME
FROM INFORMATION_SCHEMA.INDEXES
WHERE TABLE_NAME = 'BATCH_JOB_EXECUTION'
  AND INDEX_NAME = 'BATCH_JOB_EXECUTION_ACTIVE_UIDX';

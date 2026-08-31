-- Issue #771 읽기 전용 사전 점검. 반환되는 진단 row/count가 없어야 한다.
SET LOCK_TIMEOUT 5000;

SELECT status, COUNT(*) AS row_count
FROM batch_job_execution
GROUP BY status
HAVING status IS NULL
    OR status NOT IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED', 'COMPLETED', 'COMPLETED_WITH_SKIPS');

SELECT job_name, params_hash, COUNT(*) AS active_row_count
FROM batch_job_execution
WHERE status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED')
GROUP BY job_name, params_hash
HAVING job_name IS NULL OR params_hash IS NULL OR COUNT(*) > 1;

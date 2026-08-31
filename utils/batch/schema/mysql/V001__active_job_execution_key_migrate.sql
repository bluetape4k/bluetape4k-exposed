-- CHECK를 강제하는 MySQL 8.0.16+ InnoDB에서 실행한다.
-- MySQL DDL은 implicit commit되므로 각 DDL을 information_schema로 guard한다.
-- 중간 실패 후에도 preflight를 다시 통과하면 이 파일을 처음부터 재실행할 수 있다.
SET SESSION innodb_lock_wait_timeout = 5;

SET @migration_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE batch_job_execution ADD COLUMN active_key VARCHAR(16) NULL',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'batch_job_execution'
      AND column_name = 'active_key'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

UPDATE batch_job_execution
SET active_key = CASE
    WHEN status IN ('STARTING', 'RUNNING', 'FAILED', 'STOPPED') THEN 'ACTIVE'
    ELSE NULL
END;

ALTER TABLE batch_job_execution MODIFY params_hash VARCHAR(64) NOT NULL;

SET @migration_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE batch_job_execution ADD CONSTRAINT batch_job_exec_status_active_key_chk CHECK ((status IN (''STARTING'', ''RUNNING'', ''FAILED'', ''STOPPED'') AND active_key = ''ACTIVE'') OR (status IN (''COMPLETED'', ''COMPLETED_WITH_SKIPS'') AND active_key IS NULL))',
        'SELECT 1'
    )
    FROM information_schema.table_constraints
    WHERE table_schema = DATABASE()
      AND table_name = 'batch_job_execution'
      AND constraint_name = 'batch_job_exec_status_active_key_chk'
      AND constraint_type = 'CHECK'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'CREATE UNIQUE INDEX batch_job_execution_active_uidx ON batch_job_execution (job_name, params_hash, active_key)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'batch_job_execution'
      AND index_name = 'batch_job_execution_active_uidx'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

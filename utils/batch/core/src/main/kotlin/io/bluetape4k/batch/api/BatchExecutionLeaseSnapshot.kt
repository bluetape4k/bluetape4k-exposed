package io.bluetape4k.batch.api

import java.io.Serializable

/**
 * Job과 현재 Step의 lease 갱신 결과를 함께 나타내는 snapshot.
 *
 * repository는 Job과 Step을 같은 transaction 경계에서 갱신한 경우에만 이 값을
 * 반환한다. [stepExecution]이 null이면 Job-only 실행 구간을 의미한다.
 */
data class BatchExecutionLeaseSnapshot(
    val jobExecution: JobExecution,
    val stepExecution: StepExecution?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

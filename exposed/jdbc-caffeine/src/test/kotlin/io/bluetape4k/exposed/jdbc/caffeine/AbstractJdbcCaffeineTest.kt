package io.bluetape4k.exposed.jdbc.caffeine

import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging

/**
 * exposed-jdbc-caffeine 통합 테스트 베이스 클래스.
 *
 * - H2_MYSQL, PostgreSQL, MySQL_V8 멀티 DB 지원 (Exposed withTables 패턴)
 * - Redis/Testcontainers 불필요 (Caffeine 로컬 캐시)
 */
abstract class AbstractJdbcCaffeineTest: AbstractExposedTest() {
    companion object: KLogging() {
        @JvmStatic
        protected val faker = Fakers.faker

        /**
         * Caffeine 캐시 테스트는 H2_MYSQL, PostgreSQL, MySQL_V8 DB를 사용합니다.
         *
         * `EXPOSED_TEST_DB`가 지정된 matrix 실행에서는 해당 데이터베이스만
         * 반환하여 receipt의 DB 라벨과 실제 연결 대상을 일치시킵니다.
         */
        @JvmStatic
        fun getEnabledDialects() = when (System.getenv("EXPOSED_TEST_DB")?.uppercase()) {
            "H2" -> setOf(TestDB.H2_MYSQL)
            "POSTGRESQL" -> setOf(TestDB.POSTGRESQL)
            "MYSQL_V8" -> setOf(TestDB.MYSQL_V8)
            else -> setOf(TestDB.H2_MYSQL, TestDB.POSTGRESQL, TestDB.MYSQL_V8)
        }

        const val ENABLE_DIALECTS_METHOD = "getEnabledDialects"
    }
}

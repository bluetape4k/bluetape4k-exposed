package io.bluetape4k.exposed.tests.crypt

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.crypt.Argon2Hasher
import org.jetbrains.exposed.v1.crypt.BCryptHasher
import org.jetbrains.exposed.v1.crypt.Hashed
import org.jetbrains.exposed.v1.crypt.Hasher
import org.jetbrains.exposed.v1.crypt.PasswordEncoderHasher
import org.jetbrains.exposed.v1.crypt.Pbkdf2Hasher
import org.jetbrains.exposed.v1.crypt.SCryptHasher
import org.jetbrains.exposed.v1.crypt.hash
import org.jetbrains.exposed.v1.crypt.hashed
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import java.sql.SQLException

class JdbcHashedColumnContractTest {
    private val plainText = "fixture-password-not-for-production"

    private class Secrets(hasher: Hasher): Table("crypt_contract_jdbc") {
        val id = integer("id")
        val secret = varchar("secret", 512).hashed(hasher)
        val optional = varchar("optional", 512).nullable().hashed(hasher)
        override val primaryKey = PrimaryKey(id)
    }

    // transformer를 거치지 않는 같은 물리 테이블의 읽기 전용 관찰 경로다.
    private object RawSecrets: Table("crypt_contract_jdbc") {
        val secret = varchar("secret", 512)
    }

    @ParameterizedTest
    @ValueSource(strings = ["bcrypt", "argon2", "pbkdf2", "scrypt"])
    fun `각 알고리즘의 저장 조회 nullable 재저장은 평문을 보존하지 않는다`(algorithm: String) {
        val hasher = when (algorithm) {
            "bcrypt" -> BCryptHasher(4) // 테스트 비용이며 production 권장 strength가 아니다.
            "argon2" -> Argon2Hasher()
            "pbkdf2" -> Pbkdf2Hasher()
            else -> SCryptHasher()
        }
        val table = Secrets(hasher)
        withTables(TestDB.H2, table) {
            table.insert {
                it[id] = 1
                it[secret] = table.secret.hash(plainText)
                it[optional] = null
            }

            val row = table.selectAll().single()
            row[table.optional].shouldBeNull()

            val loaded = row[table.secret]
            loaded.matches(plainText).shouldBeTrue()
            loaded.matches("wrong-input").shouldBeFalse()
            loaded.toString() shouldBeEqualTo "Hashed(***)"

            val encoded = RawSecrets.selectAll().single()[RawSecrets.secret]
            encoded shouldNotBeEqualTo plainText
            encoded shouldNotContain plainText
            encoded shouldBeEqualTo loaded.encodedValue

            table.update {
                it[secret] = loaded
                it[optional] = table.optional.hash(plainText)
            }
            RawSecrets.selectAll().single()[RawSecrets.secret] shouldBeEqualTo encoded

            table.selectAll().single()[table.optional]?.matches(plainText).shouldBeTrue()
            table.update {
                it[secret] = Hashed(hasher, encoded)
                it[optional] = null
            }
            RawSecrets.selectAll().single()[RawSecrets.secret] shouldBeEqualTo encoded
        }
    }

    @Test
    fun `custom hasher는 최초 변환에만 호출되고 재저장에서는 호출되지 않는다`() {
        var calls = 0
        val delegate = BCryptHasher(4)
        val custom = object: Hasher {
            override fun hash(plainText: String): Hashed {
                calls++
                return Hashed(this, delegate.hash(plainText).encodedValue)
            }

            override fun matches(plainText: String, encodedValue: String): Boolean =
                delegate.matches(plainText, encodedValue)
        }
        val table = Secrets(custom)
        withTables(TestDB.H2, table) {
            table.insert {
                it[id] = 1
                it[secret] = table.secret.hash(plainText)
                it[optional] = null
            }

            val loaded = table.selectAll().single()[table.secret]

            loaded.matches(plainText).shouldBeTrue()
            table.update { it[secret] = loaded }

            calls shouldBeEqualTo 1
            RawSecrets.selectAll().single()[RawSecrets.secret] shouldBeEqualTo loaded.encodedValue
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `인증 성공 후에만 비용 또는 알고리즘을 승격한다`(changeAlgorithm: Boolean) {
        val oldEncoder = BCryptPasswordEncoder(4)
        val encoders = mapOf<String, PasswordEncoder>(
            "bcrypt" to BCryptPasswordEncoder(5),
            "pbkdf2" to Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
        )
        val current = DelegatingPasswordEncoder(if (changeAlgorithm) "pbkdf2" else "bcrypt", encoders)
        val table = Secrets(PasswordEncoderHasher(current))
        val oldHash = "{bcrypt}" + oldEncoder.encode(plainText)

        withTables(TestDB.H2, table) {
            table.insert {
                it[id] = 1
                it[secret] = Hashed(PasswordEncoderHasher(current), oldHash)
                it[optional] = null
            }
            val loaded = table.selectAll().single()[table.secret]
            current.upgradeEncoding(loaded.encodedValue).shouldBeTrue()
            loaded.matches("wrong-input").shouldBeFalse()

            RawSecrets.selectAll().single()[RawSecrets.secret] shouldBeEqualTo oldHash
            if (loaded.matches(plainText) && current.upgradeEncoding(loaded.encodedValue)) {
                table.update {
                    it[secret] = table.secret.hash(plainText)
                }
            }

            val upgraded = table.selectAll().single()[table.secret]
            upgraded.matches(plainText).shouldBeTrue()
            current.upgradeEncoding(upgraded.encodedValue).shouldBeFalse()
            upgraded.encodedValue shouldNotBeEqualTo oldHash
        }
    }

    @Test
    fun `SQL logger와 중복 키 예외에 평문이 포함되지 않는다`() {
        val table = Secrets(BCryptHasher(4))
        val statements = mutableListOf<String>()
        val failure = assertFailsWith<Exception> {
            withTables(TestDB.H2, table) {
                addLogger(object: SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        statements += context.sql(transaction)
                        statements += context.args.joinToString { (_, value) -> value.toString() }
                    }
                })
                repeat(2) {
                    table.insert { it[id] = 1; it[secret] = table.secret.hash(plainText); it[optional] = null }
                }
            }
        }
        statements.shouldNotBeEmpty()

        generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .any { it.sqlState == "23505" }.shouldBeTrue()

        statements.any { it.contains("INSERT", ignoreCase = true) }.shouldBeTrue()
        statements.joinToString() shouldNotContain plainText

        failure.stackTraceToString() shouldNotContain plainText

        // 검출기가 평문을 포함한 입력을 실제로 거부하는지 별도 대조한다.
        assertFailsWith<AssertionError> {
            plainText.contains(plainText).shouldBeFalse()
        }
    }
}

package io.bluetape4k.exposed.core.dao.id

import org.jetbrains.exposed.v1.core.Table.UuidVersion
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Kotlin `kotlin.uuid.Uuid`를 기본 키로 사용하는 Exposed [UuidTable] 어댑터입니다.
 *
 * 기본 생성 전략은 `UuidVersion.V4`이며, 시간 정렬 가능한 UUID V7을 사용하려면
 * [uuidVersion]에 `UuidVersion.V7`을 명시합니다. ID는 데이터베이스 기본값이 아니라
 * INSERT 직전에 클라이언트에서 생성되며, 테이블의 추가 Kotlin UUID 컬럼에는 영향을
 * 주지 않습니다.
 *
 * ```kotlin
 * object Events: KotlinUuidTable("events", uuidVersion = UuidVersion.V7) {
 *     val payload = text("payload")
 * }
 * ```
 *
 * @param name 테이블 이름입니다. 비워 두면 Exposed가 클래스 이름에서 결정합니다.
 * @param columnName 기본 키 컬럼 이름이며 기본값은 `id`입니다.
 * @param uuidVersion ID 생성에 사용할 UUID 버전이며 기본값은 `UuidVersion.V4`입니다.
 */
@OptIn(ExperimentalUuidApi::class)
open class KotlinUuidTable(
    name: String = "",
    columnName: String = "id",
    uuidVersion: UuidVersion = UuidVersion.V4,
): UuidTable(name, columnName, uuidVersion)

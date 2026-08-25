# Issue #719 UUID public ABI 마이그레이션

## 결정

JDBC와 R2DBC repository의 Kotlin `kotlin.uuid.Uuid` 및 Java
`java.util.UUID` 특수화는 2.0에서 filesystem-safe canonical JVM class 이름을
사용합니다.

| 1.x 이름 | 2.0 canonical 이름 | 식별자 타입 |
|---|---|---|
| `UuidJdbcRepository` | `KotlinUuidJdbcRepository` | `kotlin.uuid.Uuid` |
| `UUIDJdbcRepository` | `JavaUuidJdbcRepository` | `java.util.UUID` |
| `UuidSoftDeletedJdbcRepository` | `KotlinUuidSoftDeletedJdbcRepository` | `kotlin.uuid.Uuid` |
| `UUIDSoftDeletedJdbcRepository` | `JavaUuidSoftDeletedJdbcRepository` | `java.util.UUID` |
| `UuidR2dbcRepository` | `KotlinUuidR2dbcRepository` | `kotlin.uuid.Uuid` |
| `UUIDR2dbcRepository` | `JavaUuidR2dbcRepository` | `java.util.UUID` |
| `UuidSoftDeletedR2dbcRepository` | `KotlinUuidSoftDeletedR2dbcRepository` | `kotlin.uuid.Uuid` |
| `UUIDSoftDeletedR2dbcRepository` | `JavaUuidSoftDeletedR2dbcRepository` | `java.util.UUID` |

## 호환성 계약

- 기존 이름은 deprecated source-only `typealias`로 제공됩니다.
- typealias는 JVM class를 생성하지 않으므로 이미 컴파일된 1.x consumer는
  canonical 이름으로 재컴파일해야 합니다.
- 2.0은 breaking-change major line이므로 legacy binary forwarding class를
  추가하지 않습니다. forwarding class는 case-insensitive filesystem에서
  같은 충돌을 다시 만들기 때문입니다.
- 애플리케이션 소스의 import, 구현 상위 타입, 문서 예제를 canonical 이름으로
  변경합니다.

## 적용 예

아래 코드는 canonical 이름의 매핑만 보여 주는 축약 예시입니다. 실제
repository 구현에서는 해당 모듈의 repository 필수 멤버를 함께 구현해야 합니다.

```kotlin
class UserJdbcRepository : KotlinUuidJdbcRepository<User> {
    // 기존 UuidJdbcRepository 구현 본문은 그대로 유지합니다.
}

class UserR2dbcRepository : JavaUuidR2dbcRepository<User> {
    // java.util.UUID 기반 repository는 JavaUuid 접두사를 사용합니다.
}
```

Soft-delete 특수화도 같은 규칙을 따릅니다.

```kotlin
class UserJdbcRepository : KotlinUuidSoftDeletedJdbcRepository<User, UserTable>
class UserR2dbcRepository : JavaUuidSoftDeletedR2dbcRepository<User, UserTable>
```

## 검증 계약

- `checkKotlinAbi`는 네 계열의 canonical descriptor를 모두 포함하고 legacy
  case-only JVM class를 포함하지 않아야 합니다.
- Linux와 macOS에서 `jar tf` class 목록을 비교해 filesystem 차이를 재현하지
  않아야 합니다.
- `javap`로 canonical class가 public interface이고 legacy binary class가
  생성되지 않음을 확인합니다.
- README 영어/한국어의 표와 migration 안내는 같은 이름 매핑을 유지합니다.

## 범위 밖

- 저장 데이터, 테이블, 컬럼, UUID 직렬화 형식은 변경하지 않습니다.
- `UUIDAuditable*` 등 이미 case-only 충돌이 없는 별도 이름은 이 이슈에서
  변경하지 않습니다.
- release artifact 비교(`japicmp`/`Revapi`)와 2.0 release note 승격은 release
  작업에서 별도로 검증합니다.

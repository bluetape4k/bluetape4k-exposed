# Issue 121 saveAll Lesson

## 배경

Milestone 1.8.1 issue #121은 core JDBC와 R2DBC repository contract에 batch `saveAll`
API를 요청했습니다.

## 결정

core repository는 generic entity-to-column mapper를 소유하지 않습니다. repository hook
`BatchInsertStatement.bindSave(entity)`를 추가하고 default `saveAll` implementation이
Exposed `batchInsert`를 호출하게 합니다.

기존 repository fixture가 이미 `save(entity): E`를 제공하므로 core interface에
`save(entity)`를 추가하지 않습니다. return-type-only overload는 source compatible하지
않습니다.

## 결과

`JdbcRepository`와 `R2dbcRepository`는 이제 default `saveAll` API를 노출합니다.
default behavior가 필요한 repository implementation은 table-specific insert assignment로
`bindSave`를 override합니다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc:compileKotlin :bluetape4k-exposed-r2dbc:compileKotlin`
- `./gradlew :bluetape4k-exposed-jdbc:test --tests "io.bluetape4k.exposed.jdbc.repository.ActorJdbcRepositoryTest" --tests "io.bluetape4k.exposed.jdbc.repository.AuditableJdbcRepositoryEdgeCaseTest"`
- `./gradlew :bluetape4k-exposed-r2dbc:test --tests "io.bluetape4k.exposed.r2dbc.repository.ActorR2dbcRepositoryTest" --tests "io.bluetape4k.exposed.r2dbc.repository.AuditableR2dbcRepositoryTest"`
- `git diff --check`

## 후속 지침

이 repository에 generic persistence helper를 추가할 때는 먼저 repository contract가
충분한 table mapping 정보를 갖는지 확인합니다. reflection 또는 implicit mapper 가정보다
작고 명시적인 binding hook을 우선합니다.

사용자 지시로 Claude advisor/review와 external Codex CLI review는 건너뛰었으며, 이
session은 local implementation, review, verification을 수행했습니다.

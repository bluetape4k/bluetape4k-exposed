# 이슈 #645 JDBC·R2DBC cursor pagination 교훈

날짜: 2026-08-12
이슈: #645
마일스톤: 1.13.0

## 결과

기존 `findPage` 계약을 건드리지 않고 JDBC와 R2DBC 저장소에 동일한 typed primary-key cursor API를
추가했습니다. `ExposedCursorPage<T, C>`는 `hasNext`와 `nextCursor`의 불변식을 생성 시점에 검증하고,
두 adapter는 `LIMIT pageSize + 1`을 사용하는 한 번의 bounded SELECT만 실행합니다.

## 재사용할 설계 규칙

- 커서는 전송 토큰이 아니라 `IdTable.id`의 raw 값입니다. encode, decode, 서명, 만료, tenant/권한
  범위와 다음 요청의 predicate·sort 재사용은 호출자가 소유합니다.
- `ID : Comparable<ID>` 제약은 비교 가능성을 compile time에 고정합니다. `EntityIDColumnType`에서
  실제 `Column<ID>`로 내리는 unchecked cast는 adapter 내부 한 곳에만 두고, `CompositeID`는 범위에서
  제외합니다.
- `ASC` 세 변형은 엄격한 `>`, `DESC` 세 변형은 엄격한 `<`를 사용합니다. primary key가 non-null이므로
  null placement 변형은 방향만 의미가 있습니다.
- 기본 predicate는 `Op.TRUE`라서 soft-delete 행을 숨기지 않습니다. 활성 목록은 호출자가
  `isDeleted eq false` 같은 predicate를 매 요청에 전달해야 합니다.
- R2DBC의 suspend row mapper는 일반 `map`/`buildList`로 바꾸지 않고 명시적 loop에서 호출해 suspend
  경계를 보존합니다. 취소 예외는 변환하지 않고 다시 전파해 트랜잭션 rollback과 pool 연결 회수를
  바깥 경계에 맡깁니다.

## 검증 근거

- `ExposedCursorPageTest`: 상태 불변식, 빈 결과와 null cursor, 잘못된 상태 거부.
- JDBC cursor 테스트: sparse ID, 여섯 `SortOrder` 변형, predicate 결합, page-size 상한, 페이지 사이
  삭제·삽입, predicate 불일치 삽입, SQL logger의 count 0회/단일 SELECT/strict boundary.
- R2DBC cursor 테스트: JDBC와 같은 행렬에 더해 별도 connection identity, size-one pool에서 취소 시
  `CancellationException` 재전파, 미커밋 write rollback, connection release와 후속 query 성공.
- H2·PostgreSQL·MySQL 테스트 경로에서 동작을 확인했습니다. JDBC Gradle 플러그인 초기화 중 한 번
  발생한 Dokka `kotlinx/serialization/StringFormat` classpath 오류는 단독 재실행에서 재현되지 않았고,
  코드 실패로 분류하지 않았습니다.

## 문서와 릴리스 경계

모듈 README EN/KO와 소스 KDoc에는 사용 예, 1..10,000 상한, count/offset 부재, soft-delete predicate,
caller-owned token, R2DBC 취소 계약을 함께 기록합니다. `docs/manual/**`는 1.12.1 immutable release
ref에 고정되어 있으므로 이 개발 PR에서 수정하지 않습니다. 1.13.0 release promotion 때 같은 계약을
manual landing과 repository-patterns EN/KO에 반영하고 release-tree validator를 실행해야 합니다.

## 다음 작업

PR CI에서 전체 core/JDBC/R2DBC 통합·정적 검증과 ABI read-back을 다시 실행합니다. CI가 통과한 뒤에도
merge는 별도 최신 승인 게이트이며, merge 전에는 exact PR head·리뷰·workflow 상태를 다시 확인합니다.

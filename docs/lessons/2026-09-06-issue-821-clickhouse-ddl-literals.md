# #821: SQL 제약 제거는 원문 전체가 아니라 구문 위치를 대상으로 한다

## 잘못된 전제와 재현

기존 정규식은 NULL·PRIMARY KEY·REFERENCES가 항상 DDL 제약이라고 가정했다.
하지만 `DEFAULT 'hello NULL world'`의 NULL도 제거해 저장 기본값을 변경했다.
회귀 테스트 5건 모두 실제 결과 불일치로 실패했다. 인용 식별자, escape된 인용 부호,
`DEFAULT NULL`, 인용된 PK/FK 대상도 같은 구분 실패에 영향을 받았다.

## 결정과 재발 방지

- 문자열·인용 식별자·주석은 같은 길이로 마스킹하고 원문 인덱스를 유지한다.
  결과는 원문에서 제약 구간만 제거하여 만들고, 보호 영역을 다시 인코딩하지 않는다.
- 괄호 깊이와 DEFAULT 문맥을 구분한다. 함수식 내부 NULL과 nullable 컬럼의 NULL은 다르다.
- Exposed의 final Column/descriptionDdl을 우회해 타입·기본값 생성 규칙 전체를 복제하지 않는다.
  어댑터가 지원하는 Exposed 생성 DDL 범위를 명시하고 범용 parser 의존성을 만들지 않는다.
- 다음 DDL 변경은 키워드 포함 리터럴·인용 식별자·escape·기본값 식을 먼저 RED로 고정한다.
  SQL 문자열 검사뿐 아니라 실제 서버의 schema 기본값과 기본값으로 저장한 행을 확인한다.

## 검증과 수정 과정의 교훈

- 기존 단위 테스트 25건 통과 → 새 5건 실패 → 수정 후 30건 통과.
- 주석·CASE 식·FK 동작과 실제 ClickHouse 검증을 추가한 첫 모듈 전체 157건 통과, skip 0.
- 설계 리뷰는 Exposed가 DEFAULT 뒤에 NULL 제약을 붙인다는 누락을 발견했다.
  처음 테스트는 반대 순서를 사용해 오류를 놓쳤다. 실제 nullable 컬럼 생성 테스트와 배열 기본값
  테스트를 추가해 2건 RED를 확인하고, 최종 NULL 제약과 DEFAULT 값의 NULL을 분리했다.
  배열 괄호도 깊이에 포함한 뒤 최종 전체 159건과 Detekt·ABI 검사가 통과했다.
  향후 문법 변환 검사는 수동 문자열과 실제 라이브러리 생성 결과를 함께 사용한다.
- Detekt는 인용 처리 복잡도·중첩·줄 길이·return 수 6건을 검출했다.
  인용 영역 끝 탐색을 private helper로 분리하고 제한을 만족시킨 뒤 전체 검사를 다시 통과했다.
  초기 줄 길이 수정에서 한 줄을 놓쳐 1건이 남았으므로 최종 결과를 읽기 전에는 정적 검사 완료를 선언하지 않는다.
- `test detekt checkKotlinAbi --no-parallel` 최종 실행과 `git diff --check` 통과.

테이블 수준 FK 제거와 `ClickHouseStringColumnType`의 문자열 기본값 인용 처리는 기존 한계다.
전자는 지원 범위를 README/KDoc에 명시했고 후자는 테스트 중 실제 생성 DDL에 인용 부호가
없음을 확인했다. 이 변경은 기본값 직렬화나 FK 지원을 추가하지 않는다.
문자열은 기존 varchar 경로로, nullable suffix는 숫자·null 경로로 분리해 검증했다.

로그는 해당 worktree의 `build/issue821/`에 있다. 전체 생태계나 다른 Exposed 버전의
호환성을 검증한 결과는 아니며, 임의 수동 SQL은 지원 범위 밖이다.

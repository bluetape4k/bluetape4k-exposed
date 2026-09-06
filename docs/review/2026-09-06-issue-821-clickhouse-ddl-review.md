# #821 ClickHouse DDL 리뷰

## 범위와 증거

- 기준 커밋: `f2fe16416461ec65dca7954d14dcae8664b1b2c1`.
- 대상: ClickHouseTable, 새 단위·실서버 테스트, README 두 언어, CHANGELOG, 계획·교훈.
- 근거: `build/issue821/red.log`, `review-red.log`, `review-verified.log`,
  모듈 JUnit XML, 현재 Exposed 1.5.0 JAR 및 실제 createStatement 결과.
- Kotlin 패턴·테스트·최종 체크리스트와 writer SPW-01–05를 적용했다.

## 코드 리뷰

native code-reviewer는 제한 시간 내 사용 가능한 결과를 제공하지 않아 중단했다.
다음 판정은 메인 세션의 **inline fallback review**이며 독립 코드 검증이나 특정 모델의
판정이 아니다. native 실패 기록은 workflow receipt에 남긴다.

- 정확성: ClickHouseTable.kt의 인용 마스크·괄호/배열 깊이·최종 nullability 구간을 확인했다.
  원문의 보호 영역을 변경하지 않으며 DEFAULT NULL 값 자체와 IS NOT NULL은 보존한다.
- 보안: 신뢰할 수 없는 SQL을 안전하게 만드는 API가 아니다. Exposed 생성 DDL 변환으로
  범위를 제한하며 기존 raw option 거부와 engine 경계는 유지한다. 비밀정보 출력은 추가하지 않았다.
- API/호환성: 기존 public API 변경과 의존성 추가 없음. checkKotlinAbi 통과.
- 자원/동시성: 생산 코드는 순수 문자열 변환이다. 실서버 테스트는 공유 launcher와 순차 실행,
  finally의 자체 테이블 drop을 사용한다. 취소·스레드 모델을 변경하지 않는다.
- 테스트: 5건 최초 RED, 리뷰 수정 RED 2건, 최종 159건 통과·실패 0·skip 0.
  리터럴·escape·식별자·주석·DEFAULT·배열·PK/인라인 REFERENCES·실제 기본값 저장을 검증한다.
- 성능: schema 생성 경로에 한정된 마스크·위치 배열이며 새로운 parser 의존성은 없다.
  범용 SQL parser 성능이나 임의 크기 DDL 처리량을 검증한 결과는 아니다.

## 독립 설계 리뷰와 조치

독립 architect 최종 판정은 **WATCH**, P0/P1=0/0이며 merge blocker는 없다.
Exposed DDL 순서와의 결합은 실제 생성 결과 회귀 테스트로 관리하고,
기존 테이블 수준 FK 미지원은 문서화한 별도 범위로 유지한다.
코드 APPROVE + 설계 WATCH를 종합한 최종 판정은 **COMMENT**이며 두 주의사항을 수용해 기록했다.

- P1: DEFAULT 뒤에 붙는 NULL 제약이 보존될 수 있었다.
  실제 Exposed nullable 컬럼으로 RED를 재현하고 최종 suffix와 배열 깊이를 보강했다.
  ClickHouseDefaultLiteralTest의 생성·삽입·조회와 ClickHouseDdlSanitizerTest로 회귀를 고정했다.
- 기존 테이블 수준 FK 미지원은 이번 리터럴 훼손 수정과 분리했다.
  기본 키·인라인 REFERENCES 제거로 README/KDoc 범위를 좁히고 외래 키 선언 금지를 명시했다.
- 문자열 전용 ClickHouseColumnType의 기본값 인용 문제는 별도 기존 한계다.
  실제 SQL에 인용 부호가 없는 실패 증거를 교훈과 로그에 남겼으며 직렬화 변경은 하지 않았다.

## 검증과 남은 경계

- `test detekt checkKotlinAbi --no-parallel`: PASS, JUnit 159/159, skip 0.
- `git diff --check`: PASS. IDE 도구 대신 compile·Detekt를 사용했다.
- 영어·한국어 README 범위 동일. 계획·교훈·리뷰의 source-to-claim과 한국어 기술 용어를 재확인했다.
- 테이블 수준 FK·custom String 기본값 직렬화, 다른 Exposed 버전, 전체 생태계 검증은 범위 밖이다.
- 이번 변경의 미해결 P0/P1: 0. 코드 판정: APPROVE, 독립 설계: WATCH.
  최종 전체 검증은 상위 lane이 159건 통과를 확인했다. PR exact-head CI와 별도 머지 승인은 필요하다.

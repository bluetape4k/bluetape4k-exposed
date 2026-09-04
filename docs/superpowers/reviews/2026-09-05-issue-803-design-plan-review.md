# #803 설계·계획 검토

## 범위와 기준

기준 commit: `81ab8b0052734b18ece5e3673ffcc0fc285cc10b`.
대상은 같은 날짜의 #803 설계와 구현 계획이다. 구현 검증은 아직 수행 전이며,
이 문서는 설계·계획 gate만 판정한다.

## 설계 리뷰

| 관점 | 확인 주체 | 최종 결과 |
|---|---|---|
| 성능 | 독립 code-reviewer | PASS: SQL tuple·parameter-set 증거 분리, bounded Sequence |
| 안정성 | 독립 verifier | PASS: 해당 호출/이전 쓰기/자체 트랜잭션 경계 분리 |
| 보안 | 독립 code-reviewer | PASS: 숫자 메타데이터만 오류에 포함, Long 계산 |
| 운영 | 독립 verifier | PASS: writer 검증을 자체 트랜잭션 전에 수행 |
| API | 독립 code-reviewer | PASS: 기존 descriptor와 필수 신규 Boolean |
| 호출자 | 독립 writer | PASS: 네 진입점의 ignore/생성 값/반환 타입 구분 |
| 통합 | 주 세션 | PASS: 최종 P0=0/P1=0 |

## 계획 리뷰와 조치

- 성능: 독립 리뷰의 dialect 실제 한도 요구는 승인된 upstream 추정치 계약과 구분했다.
  모든 DB의 실제 한도를 보장하지 않음을 계획·설계에 명시한다. 생성 값 요청=false는
  입력/결과 zip 대신 별도 SELECT로 저장 결과를 검증한다.
- 안정성: 독립 리뷰에 따라 `[new, dup, another-new]` rollback 사례와 바인더 진입 시
  실제 Job 취소 지점을 고정했다. 서버 실행 중 취소 전체 검증은 #808에 남긴다.
  `--max-workers=1`과 기존 TestMutexService로 DB task를 순차 실행한다.
- 보안: 독립 리뷰 PASS. bounded 수집과 Long 계산, 신규 logging/catch 없음.
- 운영: 독립 리뷰 PASS. 검증·복구·ABI·CI gate와 기존 기본 동작 유지.
- API: 독립 리뷰에 따라 기존 synthetic constructor bridge의 `javap` 확인과
  `saveAll` 무변경/기존 전체 테스트를 명시했다.
- 호출자 및 수정 후 통합: native `agent thread limit reached`가 반복되어 주 세션이
  fallback 검토했다. 이를 추가 독립 리뷰로 계산하지 않는다. 공개 예시, 두 언어 README,
  반환값 제약, 추정 한도, 테스트/PR/별도 머지 gate가 계획에 연결되어 있다.

최종 계획 P0=0/P1=0. 실제 코드의 계약 충족 여부는 RED/GREEN, H2/PostgreSQL,
ABI 및 구현 리뷰에서 별도로 판정한다. 계획 PASS는 구현 완료를 의미하지 않는다.

## 문서 DoD

SPW-01~05 PASS: 한국어 개발자 문서, 태그 소스와 로컬 API 기준, 식별자/수치 보존,
spec-to-plan 대응, Markdown read-back, terminology audit findings=0.
기준 writer H2 테스트 JDBC 4 PASS/1 기존 skip, R2DBC 4 PASS.
repository 테스트 소스 JDBC/R2DBC compile PASS.
configuration cache 문제는 `--no-configuration-cache` 우회 검증으로 분리한다.

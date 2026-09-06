# #826 publication POM 라이선스 수정 계획

## 승인과 범위

사용자는 MIT LICENSE와 Apache-2.0 POM 불일치를 수정하는 PR의 선행 작업을 승인했다.
Type C로 `develop@9f86e603`에서 분리한 `fix/pom-license-mit` 브랜치를 사용한다.
PR 대상은 bluetape4k/bluetape4k-exposed의 develop이다. 저장소 LICENSE·공개 Kotlin API·버전은 바꾸지 않는다.
발행과 머지는 수정된 정확한 head 검증 후 별도 승인 단계다.

## 실행 순서

1. C-01/02: 45개 생성 POM의 Apache-2.0과 LICENSE:1의 MIT 불일치를 재확인하고 관련 이슈를 등록한다.
2. C-03: 기존 Ruby Minitest에 정상 MIT, 라이선스 누락, Apache, 잘못된 URL, 복수 선언 검사를 추가한다. 잘못된 선언이 기존 검사에서 통과하는 RED를 확인한다.
3. C-04: root와 BOM의 Gradle POM 이름·URL을 MIT로 변경하고 기존 PomAudit에 루트 publication 라이선스 검사를 추가한다.
4. C-05: Ruby 회귀 테스트와 syntax, 45개 POM 생성·Maven 모델 검증, actionlint, diff check를 실행한다. CI·개발 버전 발행·정식 발행 경로에 동일 단위 테스트를 연결한다.
5. C-06/07: 교훈·독립 리뷰 또는 명시적 inline fallback을 기록하고 PR을 생성한다. 공개 GitHub 본문은 한국어, assignee debop, milestone 2.1.0이다.

## 완료 조건과 복구

잘못된 POM은 누락이나 복수 선언을 포함해 검사 단계에서 실패해야 한다.
정상 45개 POM에는 MIT 이름·URL만 있어야 하며 기존 dependency-version 검사 결과를 보존한다.
배포 오류 복구가 아니라 잘못된 신규 발행 방지다. 기존 배포 artifact와 tag는 수정하지 않는다.
실패 시 수정한 source/validator/test만 재검토하며 publication을 실행하지 않는다.

## 검증 결과

- C-01~C-06 완료: #826 등록, RED 4건, GREEN 10개 테스트/30개 assertion.
- 공통 설정만 수정한 첫 전체 검사에서 BOM의 별도 Apache 선언을 발견했다.
  독립 리뷰의 P1 지적을 반영한 뒤 재생성·검증했다.
- 전체 POM 검사: failures=0, files=45, dependencies=13138, maven_models=45.
- metadata audit 테스트 15개/27개 assertion, Ruby syntax와 actionlint 통과.
- POM 독립 재리뷰: P0=0, P1=0, APPROVE. PR #827을 생성했다.
- 최초 CI는 POM·ABI·컴파일을 통과했으나 JDBC Caffeine 테스트에서 실패했다.
  C-07~C-09는 추가 수정·새 head CI·머지 승인 대기다. 머지·발행은 수행하지 않았다.
- 원본·검증: root/BOM Gradle 설정, PomAudit, #826, 아래 교훈의 재현 기록.
- 작성 검증: 한국어 유지보수 계획으로 승인 범위·실행 순서·실패 복구·검증 근거를 대조했다.
  SPW-01~SPW-05와 KO-01~KO-07 결과는 리뷰 문서에 기록한다.

## 승인된 CI 추가 수정

사용자는 PR #827의 JDBC Caffeine DB 선택·fixture 종료 경계 수정을 별도로 승인했다.
같은 저장소·base·head를 유지하며, production 코드나 fixture API는 변경하지 않는다.

1. 완료 — `af69a83f`의 전체 H2 모듈에서 테이블 누락을 재현했다.
   단독 테스트 2개는 통과했지만 전체 181개에서는 1개가 실패했다.
2. 완료 — 두 close/flush 테스트에서 count를 fixture 내부로 이동했다.
   close 후 commit으로 이전 읽기 트랜잭션을 끝내고, 같은 DB에서 검증 후 기본 테이블 정리를 실행한다.
   put 실패 시에도 finally에서 repository를 닫는다.
3. 완료 — H2 전체 179개 성공·기존 제외 2개, detekt, PostgreSQL·MySQL 타깃 각 2개가 통과했다.
   독립 리뷰의 시간 초과를 기록하고 inline fallback review로 P0=0/P1=0을 확인했다.
4. 대기 — 별도 커밋으로 PR에 반영하고 새 head CI를 확인한다. 머지·발행 승인과는 구분한다.

DB 선택 문제가 남으면 JDBC Caffeine 테스트만 다시 조사한다. fixture permit을 제거하거나
기본 DB를 전역 고정하거나 재시도 횟수를 늘리는 방식으로 우회하지 않는다.

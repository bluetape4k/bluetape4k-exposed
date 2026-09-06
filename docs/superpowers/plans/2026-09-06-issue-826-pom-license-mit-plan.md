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
- 독립 재리뷰: P0=0, P1=0, APPROVE. CI·머지·발행은 별도이며 아직 수행하지 않았다.
- C-07~C-09 대기: PR 생성 후 정확한 head의 CI를 확인하고 머지 승인을 받는다.
- 원본·검증: root/BOM Gradle 설정, PomAudit, #826, 아래 교훈의 재현 기록.
- 작성 검증: 한국어 유지보수 계획으로 승인 범위·실행 순서·실패 복구·검증 근거를 대조했다.
  SPW-01~SPW-05와 KO-01~KO-07 결과는 리뷰 문서에 기록한다.

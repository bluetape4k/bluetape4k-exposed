# 검토 - Issue 341 캐시 종료 수명주기 (2026-07-05)

## 범위

- 이슈: #341
- 모듈:
  - `:bluetape4k-exposed-jdbc-lettuce`
  - `:bluetape4k-exposed-r2dbc-lettuce`
  - `:bluetape4k-exposed-jdbc-caffeine`
  - `:bluetape4k-exposed-r2dbc-caffeine`

## 발견 사항

- P0: 없음.
- P1: 없음.

## 근거

- Lettuce 종료 경로에서 이제 Near Cache 정리와 백킹 캐시 정리를 분리한다.
- 일시 중단 Near Cache 종료 경로에서는 `CancellationException`을 별도로 포착한 뒤 다시 던진다.
- Caffeine 종료 경로에서는 플러시 후 정리 작업에 앞서 기존의 제한된 write-behind 완료 대기를 유지한다.
- Caffeine 캐시 무효화에 실패해도 더 이상 리포지토리 스코프 취소를 건너뛰지 않는다.

## 검증

- 영향받는 모듈의 기준선 컴파일: PASS.
- 변경 후 영향받는 모듈 컴파일: PASS.
- 종료 수명주기 집중 회귀 테스트: PASS, 6개 통과.
- 영향받는 모듈의 직렬 테스트: PASS, 308개 통과 및 22개 보류.

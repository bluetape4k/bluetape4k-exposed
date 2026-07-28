# 리뷰 - Issue 350 JDBC Lettuce Coverage (2026-07-05)

## 범위

- Issue: #350
- 모듈: `:bluetape4k-exposed-jdbc-lettuce`
- 초점: `ExposedLettuceSuspendedLoadedMap`

## 발견 사항

- P0: 없음.
- P1: 없음.

## 근거

- baseline Kover XML에서 `jdbc-lettuce` instruction coverage는 74.78%였습니다.
- 가장 큰 missed source file은 `ExposedLettuceSuspendedLoadedMap.kt`였습니다.
- suspended map read-through, write-through, write-behind close, suspend close, write failure handling에 대한 direct Redis-backed test를 추가했습니다.

## 검증

- 새 focused test class: PASS, 5 passing.
- module test와 Kover XML/log: PASS, 803 passing 및 72 pending.
- 최종 `jdbc-lettuce` instruction coverage: 85.08%.
- 최종 Kover line coverage: 84.6626%.

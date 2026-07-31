# 이슈 337 단언문 스타일 정리

## 배경

이슈 #337에서는 기존 #335 스택형 PR 기반 위에서 저장소 전반의 테스트 단언문을 bluetape4k 단언문 스타일로 통일해야 했다.

## 결정

- 작업은 `chore/issue-312-314-code-pattern-audit`를 기반으로 하는 스택형 브랜치로 유지한다.
- 이슈에 명시된 패턴에 한해서만 기계적으로 단언문을 변경한다.
- 호출부를 변경하지 않고 작은 로컬 래퍼를 사용해 방언별 도우미 메시지를 보존한다.

## 결과

- 이슈 범위에서 `kotlin.test` 단언문 및 실패 함수 사용을 제거했다.
- Java 스타일 `.shouldBeEqualTo(...)`와 불리언 동등성 단언문을 Kotlin 중위 표기법의 bluetape4k 단언문 스타일로 교체했다.
- 성공한 블록이 예기치 않은 예외로 포착되지 않고 의도한 단언 실패를 발생시키도록 R2DBC suspend 도우미 분기 하나를 수정했다.

## 검증

- 금지 패턴 검사 결과 일치 항목이 0개였다.
- `git diff --check`가 통과했다.
- 전체 `compileTestKotlin`이 통과했다.
- 대상 core/readable/r2dbc/batch/JDBC/R2DBC/demo 테스트가 통과했다.
- `--no-configuration-cache`를 사용한 저장소 전체 `test`가 통과했다.

## 향후 지침

- 값의 타입이 `Any?`라면 `shouldBeTrue()`와 같은 불리언 전용 매처를 사용하기 전에 예상 Kotlin 타입으로 캐스팅한다.
- bluetape4k 테스트에 `kotlin.test.assert*` 또는 `kotlin.test.fail`을 다시 도입하지 않는다. `bluetape4k-assertions` 매처와 `assertFailsWith`를 사용한다.

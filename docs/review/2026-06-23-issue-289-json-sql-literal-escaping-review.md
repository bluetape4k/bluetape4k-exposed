# 이슈 #289 JSON SQL 리터럴 이스케이프 리뷰

## 범위

- `exposed/jackson2`
- `exposed/jackson3`
- `exposed/fastjson2`
- 이슈 #289: 직렬화한 JSON을 SQL 리터럴/기본값 문자열로 렌더링하기 전에 이스케이프한다.

## 검토 결과

로컬 리뷰에서 P0/P1 이슈는 발견되지 않았다.

독립 code-reviewer 서브에이전트도 P0/P1 = 0으로 보고했다.

## 근거

- 이제 `JacksonColumnType.nonNullValueToString`은 직렬화한 JSON 문자열을 Exposed의 `TextColumnType.nonNullValueToString`에 위임한다.
- `FastjsonColumnType.nonNullValueToString`도 같은 이스케이프 경로를 사용한다.
- H2는 필수 `JSON ` 접두사를 유지하면서 이스케이프한 문자열 리터럴 본문을 재사용한다.
- 단위 테스트는 Jackson2, Jackson3, Fastjson2에서 렌더링한 SQL 리터럴과 기본값 문자열에 포함된 작은따옴표, CR, LF를 검증한다.

## 검증

- RED: 프로덕션 코드를 변경하기 전에 Jackson2 단위 테스트가 실패했다.
- GREEN: `./gradlew :bluetape4k-exposed-jackson2:test --tests "io.bluetape4k.exposed.core.jackson.JacksonColumnTypeUnitTest" :bluetape4k-exposed-jackson3:test --tests "io.bluetape4k.exposed.core.jackson3.Jackson3ColumnTypeUnitTest" :bluetape4k-exposed-fastjson2:test --tests "io.bluetape4k.exposed.core.fastjson2.FastjsonColumnTypeUnitTest"` 명령이 통과했다.
- 모듈 테스트: `./gradlew :bluetape4k-exposed-jackson2:test :bluetape4k-exposed-jackson3:test :bluetape4k-exposed-fastjson2:test` 명령이 통과했다.
- 빌드/정적 분석: `./gradlew :bluetape4k-exposed-jackson2:build :bluetape4k-exposed-jackson3:build :bluetape4k-exposed-fastjson2:build detekt` 명령이 통과했다.
- 공백 검사: `git diff --check` 명령이 통과했다.
- 독립 리뷰어: 6개 파일을 범위로 한 리뷰에서 정확성 또는 보안과 관련된 P0/P1 회귀가 없다고 보고했다.

## 잔여 위험

- 이 수정은 의도적으로 Exposed의 문자열 리터럴 이스케이프 의미 체계를 따른다. 향후 Exposed 버전에서 리터럴 이스케이프 동작이 변경되면 JSON 리터럴 렌더링도 그 변경을 따르게 된다.

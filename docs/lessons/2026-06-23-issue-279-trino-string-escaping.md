# 교훈 - Trino 문자열 리터럴 이스케이프 (2026-06-23)

Issue: #279
Module: `:bluetape4k-exposed-trino`

## L1: 인용된 SQL 리터럴을 직접 조합하지 마세요

### 문제

Trino function override는 `'`를 직접 출력하고 호출자가 제공한 문자열을 추가한 후 닫는 `'`를 출력했습니다. 단순한 separator와 substring에는 안전해 보였지만, 따옴표를 포함한 값이 리터럴 경계를 깨뜨렸습니다.

### 교훈

dialect override에 문자열 리터럴이 필요하면 `stringLiteral` 또는 동등한 column-type 인수 경로처럼 Exposed 리터럴 렌더링으로 값을 전달합니다. 회귀가 일반 happy-path 값 뒤에 숨지 않도록 테스트에는 따옴표와 control-like 텍스트(`)`, `--`, `;`)를 모두 포함해야 합니다.

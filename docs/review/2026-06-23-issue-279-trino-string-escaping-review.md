# 리뷰 - Issue #279 Trino String Literal Escaping

날짜: 2026-06-23
이슈: #279
모듈: `:bluetape4k-exposed-trino`

## 발견 사항

`TrinoFunctionProvider`는 quoted SQL literal을 수동으로 열고 caller-provided text를 붙인 뒤 literal을 닫는 방식으로 `groupConcat` separator와 `locate` substring을 렌더링했습니다.

## 원인

구현이 Exposed string literal rendering을 우회했습니다. `'`를 포함한 값은 생성된 SQL literal을 종료할 수 있었고, `)`, `--`, `;` 같은 control-like text는 주변 SQL stream에 그대로 남았습니다.

## 수정

두 값을 모두 Exposed `stringLiteral`로 렌더링해, 설정된 column type이 text를 escape한 뒤 Trino SQL에 붙도록 했습니다.

## 검증

- quote가 포함된 separator/substring과 control-like separator/substring에 대한 regression test를 추가했습니다.
- 새 test가 이전 direct-append 구현에서 실패함을 확인했습니다.
- 수정 후 `:bluetape4k-exposed-trino:test`가 통과함을 확인했습니다.

# Projects 1.10.0 BOM Handoff

## 배경

`bluetape4k-projects` 1.10.0이 release되었고 Maven Central에서
`bluetape4k-bom:1.10.0`이 보입니다.

## 결정

repository 자체 release line은 바꾸지 않고 local catalog의 `bluetape4k-bom` version을
1.9.2에서 1.10.0으로 업데이트합니다.

## 결과

build는 shared bluetape4k module version에 stable projects 1.10.0 BOM을 소비합니다.

## 검증

- `bluetape4k-bom:1.10.0`의 Maven Central HTTP 200.

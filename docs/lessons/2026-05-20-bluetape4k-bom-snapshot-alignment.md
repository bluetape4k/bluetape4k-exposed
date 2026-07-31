# bluetape4k BOM Snapshot Alignment

## 배경

shared dependency catalog upgrade 뒤 `bluetape4k-dependencies`가 관리하는
`bluetape4k-bom` alias를 `1.8.0`에서 `1.8.1-SNAPSHOT`으로 옮겼습니다.

## 결정

downstream sync verification을 다시 실행하기 전에 이 repository의 local catalog를
central BOM snapshot과 정렬합니다.

## 결과

catalog는 이제 central dependency constraint와 같은 `1.8.1-SNAPSHOT` family에서
bluetape4k module을 resolve합니다.

## 검증

- `bluetape4k-dependencies`에서
  `scripts/sync-shared-versions.py --workspace .. --check --summary`를 실행하면
  branch merge 뒤 이 repository가 더 이상 보고되지 않아야 합니다.

## 향후 메모

central BOM이 새 bluetape4k snapshot을 가리키면 local `bluetape4k-bom` alias를
유지하는 downstream repository는 central 변경 후 자체 sync PR이 필요합니다.

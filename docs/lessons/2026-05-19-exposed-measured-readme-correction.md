# exposed-measured README correction

## 배경

root README는 `exposed-measured`를 Micrometer metrics integration으로 설명했지만,
실제 module은 `bluetape4k-measured` unit용 Exposed custom column type을 제공합니다.

## 결정

root README와 localized README를 수정해 measured-unit column mapping을 설명합니다.
module README의 Micrometer timer flow는 column conversion flow로 교체합니다.

## 결과

문서는 module behavior와 일치합니다. `Measure<T>`, `Temperature`,
`TemperatureDelta` value는 base-unit `DOUBLE` value로 변환되고 read 시 복원됩니다.

## 검증

`.worktrees` 밖 README file에서 `exposed-measured`와 `Micrometer`, `metrics`,
`메트릭`을 함께 검색했고 남은 결과가 없었습니다.

## 향후 지침

`exposed-measured`를 문서화할 때 "measured"는 observability metrics가 아니라
physical/unit measurement로 다룹니다.

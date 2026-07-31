# Dependencies Catalog 2026-05-26-01

## 배경

`bluetape4k-dependencies`는 centralized security dependency line을 담은
`catalog/2026-05-26-01`을 publication했습니다.

## 결정

shared external library version을 local에서 pin하지 않고 downstream 기본
`bluetape4kDependenciesCatalogRef`를 새 catalog tag로 업데이트합니다.

## 결과

repository는 기본적으로 `catalog/2026-05-26-01`에서 shared dependency version을
resolve합니다.

## 검증

`settings.gradle.kts`에서 catalog ref를 확인했습니다.

## 향후 메모

shared external library는 먼저 `bluetape4k-dependencies`에서 업데이트하고 catalog를
tag한 뒤 downstream repository를 해당 tag로 옮깁니다.

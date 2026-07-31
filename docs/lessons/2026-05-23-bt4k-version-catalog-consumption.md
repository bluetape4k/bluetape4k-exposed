# bt4k Version Catalog Consumption

## 배경

`bluetape4k-exposed`는 local Gradle catalog에 shared dependency version을 중복했지만
`bluetape4k-dependencies`가 이미 ecosystem catalog를 publication합니다.

## 결정

`io.github.bluetape4k:bluetape4k-version-catalog`를 `bt4k`로 import하고 shared leaf
dependency constraint는 `bt4kVersion(alias)`로 resolve합니다. module name 및 아직 local로
resolve하는 plugin/BOM train version에는 local alias를 유지합니다.

## 결과

local catalog는 더 이상 선택된 shared leaf dependency alias를 pin하지 않습니다.
dependency management는 version을 `bt4k`에서 읽습니다. 이로써 dependency coordinate는
local에 두면서 governed version value는 centralize합니다.

## 검증

- `git diff --check`
- `./gradlew help --no-daemon --no-configuration-cache`
- `./gradlew compileKotlin --no-daemon --no-configuration-cache`

## 향후 지침

새 shared dependency version에는 `bt4k`를 우선합니다. dependency가 repository-specific이거나
central catalog가 필요한 plugin/BOM use case를 아직 지원하지 않을 때만 local version을
추가합니다.

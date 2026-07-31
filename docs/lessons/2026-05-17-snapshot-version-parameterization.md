# Snapshot Version Parameterization

배경: Central Portal release는 `-SNAPSHOT`을 제거하기 위해 `gradle.properties`를
편집할 필요가 없어야 합니다.

결정: 기본 `snapshotVersion=`은 비워 두고 `publish-snapshot.yml`이
`-PsnapshotVersion=-SNAPSHOT`을 전달하도록 합니다.

결과: `develop`은 release-ready를 유지하면서 snapshot publication은 workflow command에서
명시적으로 수행됩니다.

release-prep 결과: `bluetape4k-*` dependency는 Central Portal deployment 전에
`-SNAPSHOT`이 아닌 formal BOM-named release version을 사용합니다. final aggregator
BOM이 release되기 전에는 upstream library에서 `bluetape4k-dependencies`를 import하지
않습니다.

검증: `actionlint .github/workflows/publish-snapshot.yml`.

향후 guard: `gradle.properties`의 기본값으로 `snapshotVersion=-SNAPSHOT`을 다시
도입하지 않습니다.

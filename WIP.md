# WIP - bluetape4k-exposed

- 기준일: 2026-09-02 KST
- 최신 안정 버전: `2.0.0`
- 안정 tag commit: `d632a0bc0662ae616b786f552150a7fabd1cee3e`
- 현재 개발선: `2.1.0-SNAPSHOT`
- 현재 milestone: `2.1.0`

## 현재 상태

`2.0.0` artifact, GitHub Release, stable manual 배포를 완료했다. `develop`은 `2.1.0` minor 개발선을 사용한다. Batch, Ktor tenant, Spring Boot common 모듈은 더 이상 미배포 모듈이 아니며 안정판 `2.0.0` BOM에 포함된다.

## 다음 개발선 규칙

- `gradle.properties`는 `baseVersion=2.1.0`, 빈 `snapshotVersion`을 유지한다.
- SNAPSHOT workflow가 실행할 때만 `-PsnapshotVersion=-SNAPSHOT`을 주입한다.
- database migration과 publication artifact packaging 검증은 새 변경마다 유지한다.
- 중앙 catalog SHA는 `bluetape4k-dependencies`의 다음 개발선이 병합된 뒤 한 번만 갱신한다.

## 추적

생태계 전체 후속 작업은 [bluetape4k-dependencies #235](https://github.com/bluetape4k/bluetape4k-dependencies/issues/235)에서 추적한다. 신규 기능과 버그는 `2.1.0` milestone에서 관리한다.

# README Visual Semantics 및 Placement

## 배경

README image에는 placeholder-style alt label이 여럿 있었고 benchmark section은 chart가 더
명확한데 diagram-shaped output을 사용했으며 test infrastructure diagram은 usage detail 뒤에
나왔습니다.

## 결정

architecture와 test-support diagram은 해당 README 상단 가까이에 두고 measured benchmark
result에는 chart image를 사용하며 generated image label은 English-only로 둡니다.

## 결과

root README visual order, exposed-jdbc benchmark chart, exposed-batch benchmark
map/chart, JDBC/R2DBC test infrastructure diagram은 이제 section intent와 current source
layout에 맞습니다.

## 검증

- 변경 SVG asset의 `xmllint --noout`
- 새롭거나 변경한 SVG asset의 `rsvg-convert` PNG rendering
- README/Benchmark image-link scan
- Placeholder image-alt 및 broken graph pattern scan
- `./gradlew :bluetape4k-exposed-batch:compileBenchmarkKotlin`

## 향후 메모

benchmark doc을 재생성할 때 README rendering이 GitHub와 presentation/blog reuse에서 안정되게
Mermaid `xychart-beta` block보다 durable PNG chart reference를 우선합니다.

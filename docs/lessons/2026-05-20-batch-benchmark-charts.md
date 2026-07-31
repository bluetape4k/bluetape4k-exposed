# 2026-05-20 — Batch Benchmark Chart

## 배경

H2, MySQL, PostgreSQL의 batch benchmark detail은 Mermaid xychart template을
사용했습니다. render 결과는 직접 PNG chart보다 유용하지 않았고 legend workaround도
필요했습니다.

## 결정

Mermaid benchmark chart block을 `docs/images/readme-charts/` 아래 static SVG + PNG
chart로 교체합니다. detailed result table은 measured source of truth로 유지합니다.

## 결과

각 database detail page에는 data size별 seed throughput, pool size별 seed throughput,
parallelism별 end-to-end throughput의 세 chart가 있습니다.

## 검증

- `xmllint --noout docs/images/readme-charts/*.svg`
- `identify docs/images/readme-charts/*.png`
- touched benchmark detail file에서 남은 Mermaid/ASCII chart block을 검색했습니다.

## 향후

JDBC와 R2DBC value가 여러 order of magnitude에 걸치면 data-size 및 end-to-end 비교에
log scale을 사용합니다.

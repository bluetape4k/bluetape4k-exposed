# Issue 209 README Module Diagram

## 배경

Issue #209는 current module table과 source module에서 root README relationship diagram을
갱신해 달라고 요청했습니다. 기존 root module composition chart는
`docs/images/readme-charts/`에 있었지만 issue는 README-facing SVG 및 PNG asset을
`docs/images/readme-diagrams/` 아래에 요구했습니다.

## 결정

root overview diagram은 유지하고 module chart는 `docs/images/readme-diagrams/` 아래
module relationship diagram으로 교체합니다. asset 편집 전에 `README.md`, `README.ko.md`,
`settings.gradle.kts`에서 module model을 검증하고 각 SVG/PNG pair를 render·inspect합니다.

## 결과

README visual block은 이제 `docs/images/readme-diagrams/`의 PNG asset만 embed합니다.
SVG source는 PNG 옆에 있으며 `docs/images/readme-charts/` 아래의 이전 root module chart는
제거되었습니다.

## 검증

- README module table 및 `settings.gradle.kts`의 source model check
- 두 SVG source의 CairoSVG render
- 생성 SVG file 두 개의 `xmllint --noout`
- README image link check
- `git diff --check`

## 향후 메모

root README diagram refresh에서는 drawing 전에 README module table을
`settings.gradle.kts`와 검증합니다. diagram 자체가 localized domain term을 요구하지
않는다면 localized README에는 shared English-label asset을 유지합니다. manually validated
README-scale SVG asset을 덮어쓸 수 있는 stale generator script를 다시 도입하지 않습니다.

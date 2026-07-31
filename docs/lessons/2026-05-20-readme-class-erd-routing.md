# README Class/ERD Routing

## 배경

README class와 ERD image는 documentation, blog post, presentation 재사용을 위해
bluetape4k workspace 전체에서 다시 생성되었습니다.

## 결정

class 및 ERD diagram에는 blocker-aware lane selection을 갖는 orthogonal connector
routing을 사용합니다. pastel color와 기존 typography는 유지하되 cubic curve와 component
interior를 가로지르는 connector path는 피합니다.

## 결과

재생성한 class/ERD SVG는 relation-aware component placement, 직선 horizontal/vertical
lane, 작은 arrow marker, vertical first/final segment를 갖는 top/bottom port, component
edge 대신 row midline 근처에 둔 horizontal lane을 사용합니다.

## 검증

- `node --check .omx/scripts/refine-readme-diagrams.mjs`
- 변경 class/ERD SVG: cubic connector count `0`
- 변경 class/ERD SVG: card-interior crossing candidate `0`

## 향후 지침

diagram을 재생성할 때 blocker-aware route scoring을 보존하고 broad image churn을
수락하기 전에 contact sheet를 검사합니다.

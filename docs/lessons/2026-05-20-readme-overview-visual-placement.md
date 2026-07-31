# 2026-05-20 — README Overview Visual Placement

## 배경

README diagram과 chart는 decorative generated asset이 아니라 source-backed documentation으로
다뤄야 합니다. current pass는 2026 reference document와 shared README diagram style guide를
사용했지만 module name과 grouping의 authority는 source code와 build layout입니다.

## 결정

root README에 English-only SVG+PNG overview visual을 추가하고 overview diagram을
installation, usage, build instruction보다 먼저 둡니다. 기존 Architecture/Diagram section이
usage example 뒤에 붙어 있었다면 위로 옮깁니다.

## 결과

`bluetape4k-exposed`는 root README overview diagram과 module composition chart를 갖고
README visual placement는 overview-first rule을 따릅니다. generated label은 image 안에
localized text를 넣지 않습니다.

## 검증

- 생성 SVG file을 `xmllint --noout`으로 parse했습니다.
- 생성 PNG file을 `rsvg-convert`로 render했습니다.
- workspace README image-link scan은 missing local image 0건을 보고했습니다.
- workspace Architecture/Diagram ordering scan은 Installation, Usage, Examples, Build
  heading 뒤에 남은 section 0건을 보고했습니다.
- 생성 root overview SVG text에 non-ASCII character가 없었습니다.

## 향후 메모

architecture diagram을 README file 끝에 붙이지 않습니다. overview/architecture diagram은
상단 가까이에 두고 class, sequence, ERD, flow diagram은 설명하는 section 옆에 둡니다.

root overview diagram과 composition chart는 BOM이 있으면 먼저, Examples 또는 Additional
examples가 있으면 마지막에 둡니다. 중간 group은 repo-specific README가 alphabetic grouping을
요구하지 않는 한 source-backed orientation order를 유지합니다.

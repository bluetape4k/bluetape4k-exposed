# Central Dependency Governance Sync

## 배경

downstream Dependabot PR이 shared dependency version을 repository마다 하나씩
업데이트해 bluetape4k organization 전체의 version drift를 만들고 있었습니다.

## 결정

shared dependency version은 먼저 `bluetape4k-dependencies`에서 변경하고
`sync-shared-versions.py`로 이 repository에 materialize합니다. 이 repository는
중앙에서 관리하는 dependency name을 Dependabot에서 ignore하여 이후 PR도 central
source of truth를 통하게 합니다.

## 결과

local version catalog와 `.github/dependabot.yml`은 이제 central dependency-governance
policy를 따릅니다.

## 검증

- 이 repository에서 `sync-shared-versions.py --write --check --summary`
- 이 repository에서 `sync-dependabot-ignores.py --write --check --summary`
- `git diff --check`

## 향후 guard

중앙 관리 dependency의 repo-local Dependabot PR을 merge하지 않습니다.
`bluetape4k-dependencies`를 업데이트한 뒤 이 repository를 sync합니다.

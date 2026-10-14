# Issue #907 — PR issue 연결과 메타데이터는 생성 전에 확정한다

## 맥락

`scripts/ci/__pycache__/` 제외 변경을 준비하면서 연결할 GitHub issue와 PR 메타데이터를 먼저 확정하지 않았다. 사용자가 PR 메타데이터와 issue 연결 누락을 지적했다. 저장소에는 issue와 PR의 assignee, labels, milestone, `Closes` 연결을 live 확인하라는 기존 규칙([issue #816 lesson](2026-09-07-issue-816-tenant-jdbc-registry.md))이 있었지만, 이번 작업 순서에 적용하지 못했다.

중복 확인에서 열린 관련 issue를 찾지 못해 [issue #907](https://github.com/bluetape4k/bluetape4k-exposed/issues/907)을 `2.1.0` milestone에 등록했다. issue는 `debop`에게 할당하고 `ci`, `tech-debt` labels를 붙였다.

## 결정

- PR을 만들기 전에 기존 issue를 검색하고, 연결할 issue와 milestone, assignee, labels를 확정한다. 중복이 없으면 범위를 설명하는 issue를 먼저 등록한다.
- PR body에 `Closes #<issue-number>`를 넣고, issue의 milestone·assignee·labels를 PR에도 맞춘다.
- PR 생성 후 live 조회로 `closingIssuesReferences`와 assignee·labels·milestone을 issue와 대조한다. 대조가 끝나기 전에는 CI·리뷰 단계로 넘어가지 않는다.

다음 명령으로 연결과 메타데이터를 기계적으로 확인할 수 있다.

```bash
gh issue view "$ISSUE" --json state,assignees,labels,milestone
gh pr view "$PR" --json state,assignees,labels,milestone,closingIssuesReferences
```

## 결과와 검증

- `gh issue view 907 --json number,title,state,assignees,labels,milestone,url`: issue #907이 `OPEN`이고 `2.1.0`, `debop`, `ci`, `tech-debt` 메타데이터를 갖는 것을 확인했다.
- `gh pr view 906 --json closingIssuesReferences`: 현재 GitHub CLI에서 issue 연결을 직접 읽을 수 있음을 확인했다. 이 명령은 이번 PR의 연결 증거를 대신하지 않는다.

## 놓친 점과 재발 방지

기존 lesson이 이미 요구한 메타데이터 검증을 PR 생성 전 순서에 넣지 않은 것이 원인이었다. 앞으로 issue 연결과 메타데이터 확인을 PR 생성의 선행 조건으로 두고, PR 생성 후 live read-back까지 마쳐야 다음 단계로 진행한다.

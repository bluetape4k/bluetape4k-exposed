# Issue #314 README Version Refresh Review

## Scope

- Issue: #314 `docs: refresh README dependency versions before 1.12.0`
- Files reviewed:
  - `README.md`
  - `README.ko.md`
  - `spring-boot/spring-modulith/README.md`
  - `spring-boot/spring-modulith/README.ko.md`
  - `exposed/postgresql/README.md`
  - `exposed/postgresql/README.ko.md`

## Findings

- P0/P1: none.
- Latest published GitHub release is `1.11.0`, published on 2026-06-27.
- Root README dependency snippets already use `1.11.0` consistently and do not
  advertise `1.12.0`.
- `spring-boot/spring-modulith` still used `1.10.0`; both locale files now use
  the latest published stable `1.11.0`.
- `exposed/postgresql` still used `1.9.2`; both locale files now use the module
  README placeholder style `${version}` to avoid future stale patch versions.

## Verification

- `git diff --check`: PASS.
- README dependency scan for `io.github.bluetape4k.exposed:*:1.9.x`,
  `1.10.x`, and `1.12.x`: PASS, no matches.
- README dependency scan for explicit `1.11.0`: PASS, root README and Spring
  Modulith README pairs only.

## Residual Risk

- Documentation-only change. No production source, test source, or build logic
  was modified.

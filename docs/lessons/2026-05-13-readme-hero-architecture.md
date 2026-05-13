# README Hero And Architecture Refresh

## Context

The root README had feature and module lists but did not show the full
repository architecture at the entrypoint.

## Decision

Store the generated Exposed workbench image in `docs/assets/exposed-workbench.png`
and add a root Mermaid architecture diagram covering core repositories,
cross-cutting modules, dialect extensions, and Spring Boot 4 integration.

## Outcome

Both README locales now present the project purpose, feature scope, visual hero,
and repository architecture before quick-start examples.

## Verification

- Confirmed the generated asset exists as a PNG under `docs/assets`.
- Verified both README locales reference the shared image path.

## Future Guidance

When adding a root-level module family, update the README architecture diagram,
`AGENTS.md`, and `CLAUDE.md` together.

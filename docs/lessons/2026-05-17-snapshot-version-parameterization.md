# Snapshot Version Parameterization

Context: Central Portal releases should not require editing `gradle.properties`
only to remove `-SNAPSHOT`.

Decision: Keep `snapshotVersion=` empty by default and let
`publish-snapshot.yml` pass `-PsnapshotVersion=-SNAPSHOT`.

Outcome: `develop` stays release-ready, while snapshot publishing remains
explicit in the workflow command.

Release-prep outcome: `bluetape4k-*` dependencies now use formal BOM-named
release versions, not `-SNAPSHOT`, before Central Portal deployment. Do not
import `bluetape4k-dependencies` from upstream libraries before the final
aggregator BOM has been released.

Verification: `actionlint .github/workflows/publish-snapshot.yml`.

Future guard: Do not reintroduce `snapshotVersion=-SNAPSHOT` as the default in
`gradle.properties`.

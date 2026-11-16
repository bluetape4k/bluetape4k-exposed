#!/usr/bin/env python3

from __future__ import annotations

import re
import textwrap
import unittest
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

KTOR_FIXTURE = "ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadmeFixture.kt"
KTOR_READMES = ("ktor/exposed/README.md", "ktor/exposed/README.ko.md")
KTOR_EXAMPLE_NAMES = (
    "jdbc-report",
    "r2dbc-report",
    "snapshot",
    "custom-status",
    "cache-only-installer",
    "ingress-root-route",
    "authenticated-direct-route",
)
KTOR_TERMS = (
    "[a-z][a-z0-9_-]{0,62}",
    "1..16",
    "side-effect-free O(1)",
    "bluetape4k.exposed.ktor.cache.readiness",
    "bluetape4k.exposed.ktor.cache.queue.depth",
    "bluetape4k.exposed.ktor.cache.snapshot.pending",
    "bluetape4k.exposed.ktor.cache.snapshot.dropped",
    "bluetape4k.exposed.ktor.cache.snapshot.observer.failures",
    "component",
    "kind",
    "operation",
    "outcome",
    "entries",
    "events",
    "NaN",
    "128",
    "T_endpoint",
    "J_effective",
    "timeoutSeconds",
    "periodSeconds",
    "failureThreshold",
    "/healthz/exposed",
    "/readyz/exposed",
    "NOT_APPLICABLE",
    "IDLE",
    "RUNNING",
    "DRAINING",
    "FAILED",
    "STOPPED",
    "OUT_OF_SERVICE",
    "identity_collision",
    "rate limiting",
)
KTOR_ENGLISH_FRAGMENTS = (
    "## Cache Readiness Contributors",
    "## Installation and Security",
    "## Readiness Semantics and Budget",
    "## Metrics",
    "## Runbook",
    "`/healthz/exposed` | Probe-free liveness",
    "`/readyz/exposed` | Traffic readiness",
    "`NOT_APPLICABLE`, `IDLE`, or `RUNNING` with no flush error | `cache.<component>=UP`",
    "`DRAINING`, `FAILED`, or `STOPPED`, or any flush error | `cache.<component>=DOWN`",
    "Ktor has no `OUT_OF_SERVICE` response state",
    "T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead",
    "`(2+1)+2+2 = 7s`",
    "timeoutSeconds: 10",
    "periodSeconds: 15",
    "failureThreshold: 3",
    "Exported time-series counts and suffixes depend on the",
    "missing, omitted, or `NaN` gauge is unavailable, not zero",
    "Database `DOWN` | Check caller-owned pool connectivity and credentials",
    "Database `timeout` | Check pool exhaustion, network latency",
    "Cache `timeout` | Check the shared `R` budget and supplier cooperation",
    "A supplier that throws `CancellationException` while the request is still active",
    "request context is rethrown and stops readiness processing",
    "Snapshot cumulative counters rise | Inspect caller-owned drain/observer handling",
    "Meter collision | Keep the older route serving until traffic is withdrawn",
    "Withdraw traffic, start repository drain/close, observe readiness enter `DRAINING` and then `STOPPED`",
)
KTOR_KOREAN_FRAGMENTS = (
    "## Cache Readiness Contributor",
    "## 설치와 보안",
    "## Readiness 의미와 시간 예산",
    "## Metrics",
    "## Runbook",
    "`/healthz/exposed` | Probe를 실행하지 않는 liveness",
    "`/readyz/exposed` | Traffic readiness",
    "`NOT_APPLICABLE`, `IDLE`, `RUNNING` | `cache.<component>=UP`",
    "`DRAINING`, `FAILED`, `STOPPED` 또는 flush error | `cache.<component>=DOWN`",
    "Ktor 응답에는 `OUT_OF_SERVICE` 상태가 없습니다",
    "T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead",
    "`(2+1)+2+2 = 7s`",
    "timeoutSeconds: 10",
    "periodSeconds: 15",
    "failureThreshold: 3",
    "Export된 time-series 수와 suffix는 registry와 distribution",
    "생략됐거나 `NaN`인 gauge는 0이 아니라 unavailable",
    "Database `DOWN` | 호출자 소유 pool의 연결과 credential",
    "Database `timeout` | Pool 고갈, network latency",
    "Cache `timeout` | 공유 `R` 예산과 supplier의 cancellation 협력",
    "Request가 여전히 active인 동안 supplier가 `CancellationException`을 던지면 `DOWN`",
    "Request context 자체가 취소되면 예외를 다시",
    "Snapshot 누적 counter 증가 | 호출자 소유 drain/observer 처리",
    "Meter collision | 이전 route의 traffic을 먼저 회수",
    "Traffic을 회수하고 repository drain/close를 시작한 뒤 readiness가 `DRAINING`, 이어서 `STOPPED`",
)


@dataclass(frozen=True)
class ReadmePair:
    english: str
    korean: str
    source_directories: tuple[str, ...]
    required_names: frozenset[str]
    intentional_undocumented_api: dict[str, str] | None = None


@dataclass(frozen=True)
class PublicSnapshotDeclaration:
    name: str
    internal_api: bool


BLUETAPE_VERSIONED_COORDINATE = re.compile(
    r'io\.github\.bluetape4k(?:\.[A-Za-z0-9_-]+)*:[A-Za-z0-9_.-]+:[^"\')\s]+'
)
PUBLIC_TOP_LEVEL_TYPE = re.compile(
    r"(?m)^(?!(?:internal|private|protected)\b)"
    r"(?:(?:public|expect|actual|data|sealed|enum|annotation|value|fun)\s+)*"
    r"(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)"
)
PUBLIC_TOP_LEVEL_FUNCTION = re.compile(
    r"(?m)^(?!(?:internal|private|protected)\b)"
    r"(?:(?:public|expect|actual|inline|suspend|operator|infix|tailrec|external)\s+)*"
    r"fun\s+(?:<[^>\n]+>\s+)?([^\n(]+)\("
)
PUBLIC_TOP_LEVEL_PROPERTY = re.compile(
    r"(?m)^(?!(?:internal|private|protected)\b)"
    r"(?:(?:public|expect|actual|const|lateinit)\s+)*"
    r"(?:val|var)\s+([A-Za-z_]\w*)"
)
TOP_LEVEL_FUNCTION_NAME = re.compile(r"[A-Za-z_]\w*")

ROLLOUT_CONTRACT_MARKER = (
    "<!-- SNAPSHOT-ROLLOUT-CONTRACT: shadow-warm-only; no-v2-user-reads-or-writes; "
    "write-quiesced-cutover; rebuild-v2-from-db; switch-all-traffic; no-overlapping-user-traffic; "
    "resume-writes; no-cross-namespace-invalidation -->"
)

README_PAIRS = (
    ReadmePair(
        "exposed/cache/README.md",
        "exposed/cache/README.ko.md",
        ("exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot",),
        frozenset(
            {
                "CacheSnapshot",
                "CacheSnapshotMapper",
                "SnapshotCacheConfig",
                "CaffeineSnapshotCacheConfig",
                "SnapshotCacheFailureBuffer",
                "snapshotCacheFailureBuffer",
            }
        ),
        {
            "CacheSnapshotValueValidator": "Advanced validator hook; KDoc is the adapter contract.",
            "InternalSnapshotCacheApi": "Opt-in SPI marker; adapter KDoc is authoritative.",
            "MeasuredInvalidation": "Low-level adapter measurement model; KDoc is authoritative.",
            "SnapshotCacheApplyReport": "Low-level adapter report model; KDoc is authoritative.",
            "SnapshotCacheDeadline": "Low-level adapter deadline SPI; KDoc is authoritative.",
            "SnapshotCacheDrainResult": "Failure-drain result model; KDoc is authoritative.",
            "SnapshotCacheFailureObserver": "Advanced failure observer hook; KDoc is authoritative.",
            "SnapshotCacheLimits": "Low-level adapter limit model; KDoc is authoritative.",
            "SnapshotCacheLookup": "Opaque lookup result; the README documents lookup behavior instead.",
            "SnapshotCacheMutation": "Low-level adapter mutation model; KDoc is authoritative.",
            "SnapshotCacheOperation": "Low-level failure/report enum; KDoc is authoritative.",
            "SnapshotCacheOperationResult": "Low-level adapter result model; KDoc is authoritative.",
            "SnapshotCacheOutcome": "Low-level failure/report enum; KDoc is authoritative.",
            "SnapshotStoreId": "Low-level bounded store identity; KDoc is authoritative.",
            "SnapshotValueSizer": "Advanced sizing hook; KDoc is the adapter contract.",
            "loggingSnapshotCacheFailureObserver": "Optional logging adapter; KDoc is authoritative.",
            "maximumEstimatedPayloadBytes": "Advanced sizing helper; KDoc is authoritative.",
        },
    ),
    ReadmePair(
        "exposed/jdbc-caffeine/README.md",
        "exposed/jdbc-caffeine/README.ko.md",
        ("exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot",),
        frozenset({"JdbcCaffeineSnapshotCache", "jdbcCaffeineSnapshotCache", "stageSnapshot", "stageInvalidation"}),
    ),
    ReadmePair(
        "exposed/r2dbc-caffeine/README.md",
        "exposed/r2dbc-caffeine/README.ko.md",
        ("exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot",),
        frozenset({"R2dbcCaffeineSnapshotCache", "r2dbcCaffeineSnapshotCache", "stageSnapshot", "stageInvalidation"}),
    ),
    ReadmePair(
        "exposed/jdbc-redisson/README.md",
        "exposed/jdbc-redisson/README.ko.md",
        ("exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot",),
        frozenset(
            {
                "JdbcRedissonSnapshotInvalidator",
                "jdbcRedissonSnapshotInvalidator",
                "SnapshotRedissonCodec",
                "quotaHealth",
                "clearSnapshotNamespace",
                "clearMapRetainingMarker",
            }
        ),
    ),
)

CACHE_TERMS = (
    "isFlushJobRunning",
    "workerState",
    "NOT_APPLICABLE",
    "IDLE",
    "RUNNING",
    "DRAINING",
    "FAILED",
    "STOPPED",
)
ACTUATOR_TERMS = (
    "workerState",
    "NOT_APPLICABLE",
    "IDLE",
    "RUNNING",
    "DRAINING",
    "FAILED",
    "STOPPED",
    "UP",
    "OUT_OF_SERVICE",
    "DOWN",
    "ExposedKtorCacheContributor",
)
CACHE_ENGLISH_FRAGMENTS = (
    "`CacheHealthReport.isFlushJobRunning` was removed before the stable release",
    "report.lastFlushError == null && report.workerState in setOf(",
    "CacheWorkerState.NOT_APPLICABLE",
    "CacheWorkerState.IDLE",
    "CacheWorkerState.RUNNING",
    "The old Boolean could not distinguish those states",
)
CACHE_KOREAN_FRAGMENTS = (
    "`CacheHealthReport.isFlushJobRunning`은 stable release 전에 제거됐습니다",
    "report.lastFlushError == null && report.workerState in setOf(",
    "CacheWorkerState.NOT_APPLICABLE",
    "CacheWorkerState.IDLE",
    "CacheWorkerState.RUNNING",
    "기존 Boolean으로는 이 상태들을 구분할 수 없습니다",
)
ACTUATOR_ENGLISH_FRAGMENTS = (
    "`workerState=NOT_APPLICABLE\\|IDLE\\|RUNNING` | `UP`",
    "`workerState=DRAINING\\|STOPPED` | `OUT_OF_SERVICE`",
    "Flush error or `workerState=FAILED` | `DOWN`",
    "explicit",
    "`ExposedKtorCacheContributor`",
)
ACTUATOR_KOREAN_FRAGMENTS = (
    "`workerState=NOT_APPLICABLE\\|IDLE\\|RUNNING` | `UP`",
    "`workerState=DRAINING\\|STOPPED` | `OUT_OF_SERVICE`",
    "Flush error 또는 `workerState=FAILED` | `DOWN`",
    "명시적으로",
    "`ExposedKtorCacheContributor`",
)


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def extract_marked_block(text: str, start: str, end: str) -> str:
    start_index = text.find(start)
    end_index = text.find(end, start_index + len(start))
    if start_index < 0 or end_index <= start_index:
        raise AssertionError(f"Missing markers: {start} .. {end}")
    return text[start_index + len(start) : end_index]


def extract_fixture_example(text: str, name: str) -> str:
    block = extract_marked_block(text, f"// example:{name}:start", f"// example:{name}:end")
    return textwrap.dedent(block).strip()


def extract_readme_example(text: str, name: str) -> str:
    block = extract_marked_block(
        text,
        f"<!-- example:{name}:start -->",
        f"<!-- example:{name}:end -->",
    )
    return "\n".join(line for line in block.splitlines() if not line.strip().startswith("```")).strip()


def has_internal_api_annotation_before(source: str, declaration_start: int) -> bool:
    previous_lines = source[:declaration_start].rstrip().splitlines()
    return bool(previous_lines) and previous_lines[-1].strip() == "@InternalSnapshotCacheApi"


def top_level_public_declarations(source: str) -> set[PublicSnapshotDeclaration]:
    declarations: set[PublicSnapshotDeclaration] = set()
    for match in PUBLIC_TOP_LEVEL_TYPE.finditer(source):
        declarations.add(
            PublicSnapshotDeclaration(
                match.group(1),
                has_internal_api_annotation_before(source, match.start()),
            )
        )
    for match in PUBLIC_TOP_LEVEL_FUNCTION.finditer(source):
        names = TOP_LEVEL_FUNCTION_NAME.findall(match.group(1))
        if names:
            declarations.add(
                PublicSnapshotDeclaration(
                    names[-1],
                    has_internal_api_annotation_before(source, match.start()),
                )
            )
    for match in PUBLIC_TOP_LEVEL_PROPERTY.finditer(source):
        declarations.add(
            PublicSnapshotDeclaration(
                match.group(1),
                has_internal_api_annotation_before(source, match.start()),
            )
        )
    return declarations


def public_snapshot_api_declarations(pair: ReadmePair) -> set[PublicSnapshotDeclaration]:
    declarations: set[PublicSnapshotDeclaration] = set()
    for source_directory in pair.source_directories:
        for source_path in (ROOT / source_directory).rglob("*.kt"):
            declarations.update(top_level_public_declarations(source_path.read_text(encoding="utf-8")))
    return declarations


class SnapshotReadmeDocumentationContractTest(unittest.TestCase):
    def test_locale_pairs_contain_the_same_public_snapshot_api_names(self) -> None:
        for pair in README_PAIRS:
            english = read(pair.english)
            korean = read(pair.korean)
            declarations = public_snapshot_api_declarations(pair)
            public_api_names = {declaration.name for declaration in declarations}
            english_names = {name for name in public_api_names if name in english}
            korean_names = {name for name in public_api_names if name in korean}
            readme_required_names = {
                declaration.name
                for declaration in declarations
                if not declaration.internal_api
            }
            undocumented_english = readme_required_names - set(
                name for name in readme_required_names if name in english
            )
            undocumented_korean = readme_required_names - set(
                name for name in readme_required_names if name in korean
            )
            self.assertEqual(english_names, korean_names, pair.english)
            intentional = pair.intentional_undocumented_api or {}
            self.assertEqual(undocumented_english, set(intentional), pair.english)
            self.assertEqual(undocumented_korean, set(intentional), pair.korean)
            self.assertTrue(all(description.strip() for description in intentional.values()))
            for required_name in pair.required_names:
                self.assertIn(required_name, english)
                self.assertIn(required_name, korean)

    def test_redisson_rollout_docs_require_a_write_quiesced_all_traffic_cutover(self) -> None:
        for pair in README_PAIRS:
            if "jdbc-redisson" not in pair.english:
                continue
            self.assertIn(ROLLOUT_CONTRACT_MARKER, read(pair.english))
            self.assertIn(ROLLOUT_CONTRACT_MARKER, read(pair.korean))

    def test_public_snapshot_api_discovery_covers_modifiers_and_internal_filtering(self) -> None:
        source = textwrap.dedent(
            """
        data class PublicSnapshot(val id: Long)
        sealed interface PublicOutcome
        @JvmInline
        value class PublicId(val value: Long)
        suspend fun <ID : Any> PublicSnapshot.load(id: ID): PublicSnapshot = this
        const val PUBLIC_LIMIT: Int = 1
        @InternalSnapshotCacheApi
        interface InternalAdapterSpi
        internal class InternalSnapshot
        private fun privateLoader(): Unit = Unit
        """
        ).strip()
        self.assertEqual(
            {
                PublicSnapshotDeclaration("PublicSnapshot", internal_api=False),
                PublicSnapshotDeclaration("PublicOutcome", internal_api=False),
                PublicSnapshotDeclaration("PublicId", internal_api=False),
                PublicSnapshotDeclaration("load", internal_api=False),
                PublicSnapshotDeclaration("PUBLIC_LIMIT", internal_api=False),
                PublicSnapshotDeclaration("InternalAdapterSpi", internal_api=True),
            },
            top_level_public_declarations(source),
        )

    def test_snapshot_dependency_coordinates_are_versionless_under_the_consumer_bom(self) -> None:
        for pair in README_PAIRS:
            for path in (pair.english, pair.korean):
                self.assertIsNone(BLUETAPE_VERSIONED_COORDINATE.search(read(path)), path)

    def test_explicit_versions_after_bluetape_artifact_coordinates_are_detected(self) -> None:
        self.assertIsNotNone(
            BLUETAPE_VERSIONED_COORDINATE.search(
                'implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cache:1.12.0")'
            )
        )

    def test_paired_readmes_expose_the_transaction_and_operator_contract_sections(self) -> None:
        markers = {
            "exposed/cache/README.md": "<!-- SNAPSHOT-CACHE-CONTRACT -->",
            "exposed/cache/README.ko.md": "<!-- SNAPSHOT-CACHE-CONTRACT -->",
            "exposed/jdbc-caffeine/README.md": "<!-- JDBC-SNAPSHOT-CACHE -->",
            "exposed/jdbc-caffeine/README.ko.md": "<!-- JDBC-SNAPSHOT-CACHE -->",
            "exposed/r2dbc-caffeine/README.md": "<!-- R2DBC-SNAPSHOT-CACHE -->",
            "exposed/r2dbc-caffeine/README.ko.md": "<!-- R2DBC-SNAPSHOT-CACHE -->",
            "exposed/jdbc-redisson/README.md": "<!-- REDISSON-SNAPSHOT-INVALIDATION -->",
            "exposed/jdbc-redisson/README.ko.md": "<!-- REDISSON-SNAPSHOT-INVALIDATION -->",
        }
        for path, marker in markers.items():
            self.assertIn(marker, read(path), path)


class ExposedKtorDocumentationContractTest(unittest.TestCase):
    def test_ktor_readme_examples_match_the_canonical_fixture_in_both_locales(self) -> None:
        fixture = read(KTOR_FIXTURE)
        for name in KTOR_EXAMPLE_NAMES:
            expected = extract_fixture_example(fixture, name)
            for path in KTOR_READMES:
                self.assertEqual(expected, extract_readme_example(read(path), name), path)

    def test_ktor_readmes_keep_the_readiness_and_operations_contract(self) -> None:
        for path in KTOR_READMES:
            text = re.sub(r"[\t ]+", " ", read(path)).replace("O (1)", "O(1)")
            for term in KTOR_TERMS:
                self.assertIn(term, text, path)

        english = re.sub(r"\s+", " ", read("ktor/exposed/README.md"))
        korean = re.sub(r"\s+", " ", read("ktor/exposed/README.ko.md"))
        for fragment in KTOR_ENGLISH_FRAGMENTS:
            self.assertIn(fragment, english)
        for fragment in KTOR_KOREAN_FRAGMENTS:
            self.assertIn(fragment, korean)

    def test_cache_and_actuator_readmes_keep_source_equivalent_contracts(self) -> None:
        for path in ("exposed/cache/README.md", "exposed/cache/README.ko.md"):
            text = re.sub(r"[\t ]+", " ", read(path))
            for term in CACHE_TERMS:
                self.assertIn(term, text, path)
        for fragment in CACHE_ENGLISH_FRAGMENTS:
            self.assertIn(fragment, read("exposed/cache/README.md"))
        for fragment in CACHE_KOREAN_FRAGMENTS:
            self.assertIn(fragment, read("exposed/cache/README.ko.md"))

        for path in ("spring-boot/jdbc/README.md", "spring-boot/r2dbc/README.md"):
            text = re.sub(r"[\t ]+", " ", read(path))
            for term in ACTUATOR_TERMS:
                self.assertIn(term, text, path)
            for fragment in ACTUATOR_ENGLISH_FRAGMENTS:
                self.assertIn(fragment, text, path)
        for path in ("spring-boot/jdbc/README.ko.md", "spring-boot/r2dbc/README.ko.md"):
            text = re.sub(r"[\t ]+", " ", read(path))
            for term in ACTUATOR_TERMS:
                self.assertIn(term, text, path)
            for fragment in ACTUATOR_KOREAN_FRAGMENTS:
                self.assertIn(fragment, text, path)


if __name__ == "__main__":
    unittest.main()

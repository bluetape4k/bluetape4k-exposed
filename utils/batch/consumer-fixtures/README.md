# Batch consumer fixtures

These fixtures pin the published-coordinate contract for the compatibility
aggregator and the three selective artifacts. Every build imports the
`bluetape4k-dependencies` and `bluetape4k-exposed-bom` platforms, then declares
the batch coordinate without an individual module version. The Maven fixture
uses the same BOM pair.

Run the isolated validation from the repository root:

```bash
scripts/batch/validate_consumer_fixtures.sh
```

The validator publishes the current checkout to a task-local Maven repository,
records the exact `sourceHead`, and runs the five Gradle fixtures plus the Maven
fixture with `--offline`. `legacy-binary-runtime` first compiles a Java consumer
against the published 1.12.1 aggregator and then executes that unchanged class
with the current aggregator, proving the deprecated JVM descriptor bridge at
runtime. `core-custom-json` also asserts that Jackson, Exposed, JDBC, and R2DBC
classes are absent from its runtime classpath. A fixture fails when its
provenance is missing or does not match the publication checkout. Set
`ISSUE731_MAVEN_LOCAL_REPO` to retain a specific repository for inspection;
otherwise the temporary repository is removed after the run.

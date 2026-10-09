package consumer;

import io.bluetape4k.exposed.r2dbc.tests.R2dbcExposedTestBase;

/** Compiled against the released 2.0.0 test-support artifact. */
public final class R2dbc200Consumer extends R2dbcExposedTestBase {

    public String dialectMethodName() {
        return R2dbcExposedTestBase.ENABLE_DIALECTS_METHOD;
    }

    public Object faker() {
        return R2dbcExposedTestBase.getFaker();
    }

    public Object enabledDialects() {
        return R2dbcExposedTestBase.enableDialects();
    }

    public Object schema() {
        return prepareSchemaForTest("legacy_consumer_schema");
    }

    public String addIfNotExists() {
        return addIfNotExistsIfSupported();
    }
}

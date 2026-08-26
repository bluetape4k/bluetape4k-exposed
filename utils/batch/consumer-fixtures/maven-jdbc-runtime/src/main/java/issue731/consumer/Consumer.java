package issue731.consumer;

import io.bluetape4k.batch.CheckpointJson;
import io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepository;

public final class Consumer {
    private Consumer() {}

    public static Class<?>[] compileProbe() {
        return new Class<?>[] {CheckpointJson.class, ExposedJdbcBatchJobRepository.class};
    }

    public static void main(String[] args) {
        if (compileProbe().length != 2) {
            throw new IllegalStateException("JDBC runtime probe did not load both public types");
        }
    }
}

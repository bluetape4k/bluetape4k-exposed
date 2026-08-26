package issue731.consumer;

/**
 * 1.12.1 aggregator를 기준으로 컴파일한 뒤 현재 호환성 artifact에서
 * 실행한다. 새 package로 다시 컴파일하지 않고 소비자 bytecode에 옛 JVM
 * descriptor를 유지한다.
 */
public final class LegacyBinaryConsumer {
    private LegacyBinaryConsumer() {
    }

    public static void main(String[] args) {
        io.bluetape4k.batch.internal.CheckpointJson checkpointJson =
            io.bluetape4k.batch.internal.CheckpointJson.Companion.jackson3();
        String encoded = checkpointJson.write("legacy-binary");
        Object decoded = checkpointJson.read(encoded);
        if (!"legacy-binary".equals(decoded)) {
            throw new IllegalStateException("legacy CheckpointJson binary bridge failed");
        }
    }
}

package issue731.consumer

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsumerTest {
    @Test
    fun loadsJdbcRuntimeSurface() {
        assertEquals(2, compileProbe().size)
    }
}

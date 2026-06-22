package io.bluetape4k.exposed.jdbc.caffeine.repository

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedDeque

internal class RecordingLogbackAppender(
    loggerName: String = Logger.ROOT_LOGGER_NAME,
): AppenderBase<ILoggingEvent>(), AutoCloseable {

    private val logger = LoggerFactory.getLogger(loggerName) as Logger
    private val capturedEvents = ConcurrentLinkedDeque<ILoggingEvent>()

    val events: List<ILoggingEvent>
        get() = capturedEvents.toList()

    init {
        start()
        logger.addAppender(this)
    }

    override fun append(eventObject: ILoggingEvent?) {
        eventObject?.let { capturedEvents.addLast(it) }
    }

    fun hasWarnContaining(message: String): Boolean =
        events.any { it.level == Level.WARN && it.formattedMessage.contains(message) }

    override fun close() {
        logger.detachAppender(this)
        capturedEvents.clear()
        stop()
    }
}

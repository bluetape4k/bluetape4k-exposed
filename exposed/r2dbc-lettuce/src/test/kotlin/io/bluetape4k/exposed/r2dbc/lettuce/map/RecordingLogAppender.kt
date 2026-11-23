package io.bluetape4k.exposed.r2dbc.lettuce.map

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedDeque

internal class RecordingLogAppender: AppenderBase<ILoggingEvent>(), AutoCloseable {
    private val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val capturedEvents = ConcurrentLinkedDeque<ILoggingEvent>()

    val events: List<ILoggingEvent>
        get() = capturedEvents.toList()

    val rendered: String
        get() = events.joinToString("\n") { event ->
            listOfNotNull(event.formattedMessage, event.throwableProxy?.message).joinToString(" ")
        }

    init {
        start()
        logger.addAppender(this)
    }

    override fun append(eventObject: ILoggingEvent?) {
        eventObject?.let(capturedEvents::addLast)
    }

    override fun close() {
        logger.detachAppender(this)
        capturedEvents.clear()
        stop()
    }
}

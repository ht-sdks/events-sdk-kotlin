package com.hightouch.analytics.kotlin.core.platform.plugins.logger

class ConsoleLogger: Logger {
    override fun parseLog(log: LogMessage) {
        println("[Segment ${log.kind.toString()} ${log.message}")
    }

}
package com.hightouch.analytics.kotlin.core.retry

import kotlin.random.Random

class RetryStateMachine(
    private val config: RetryConfig,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val random: Random = Random.Default
) {
    private val isLegacyMode: Boolean
        get() = !config.rateLimitConfig.enabled && !config.backoffConfig.enabled

    fun handleResponse(
        state: RetryState,
        response: ResponseInfo
    ): RetryState {
        // Legacy mode: both configs disabled
        if (isLegacyMode) {
            return when {
                response.statusCode in 200..299 -> state.removeBatch(response.batchFile)
                response.statusCode == 408 || response.statusCode == 429 || response.statusCode in 500..599 -> state  // Keep
                else -> state.removeBatch(response.batchFile)  // Drop on 4xx
            }
        }

        val currentTime = response.currentTime
        val behavior = resolveStatusCodeBehavior(response.statusCode)

        return when {
            response.statusCode in 200..299 -> {
                state.copy(
                    pipelineState = PipelineState.READY,
                    waitUntilTime = null,
                    globalRetryCount = 0,
                    batchMetadata = state.batchMetadata - response.batchFile
                )
            }

            response.statusCode == 429 -> {
                if (config.rateLimitConfig.enabled) {
                    handleRateLimitResponse(state, response, currentTime)
                } else {
                    // Rate limit handling disabled: drop (don't fall through to backoff)
                    state.removeBatch(response.batchFile)
                }
            }

            behavior == RetryBehavior.RETRY && config.backoffConfig.enabled -> {
                handleRetryableError(state, response, currentTime)
            }

            // Drop non-retryable errors, or retryable errors when backoff is disabled
            else -> {
                state.removeBatch(response.batchFile)
            }
        }
    }

    private fun handleRateLimitResponse(
        state: RetryState,
        response: ResponseInfo,
        currentTime: Long
    ): RetryState {
        val exhausted = state.globalRetryCount + 1 >= config.rateLimitConfig.maxRetryCount
        val retryAfter = if (exhausted) null else response.retryAfterSeconds

        return state.copy(
            pipelineState = PipelineState.RATE_LIMITED,
            waitUntilTime = calculateWaitUntilTimeMs(retryAfter, currentTime),
            globalRetryCount = state.globalRetryCount + 1
        )
    }

    private fun calculateWaitUntilTimeMs(retryAfterSeconds: Int?, currentTime: Long): Long {
        val seconds = retryAfterSeconds?.coerceAtLeast(0) ?: config.rateLimitConfig.maxRetryInterval
        val clampedSeconds = minOf(seconds, config.rateLimitConfig.maxRetryInterval)
        return currentTime + (clampedSeconds * 1000L)
    }

    private fun handleRetryableError(
        state: RetryState,
        response: ResponseInfo,
        currentTime: Long
    ): RetryState {
        val existingMetadata = state.batchMetadata[response.batchFile]
        val newFailureCount = (existingMetadata?.failureCount ?: 0) + 1
        val firstFailureTime = existingMetadata?.firstFailureTime ?: currentTime
        val exhausted = newFailureCount >= config.backoffConfig.maxRetryCount ||
            currentTime - firstFailureTime >= config.backoffConfig.maxTotalBackoffDuration * 1000
        val delay = if (exhausted) config.backoffConfig.maxBackoffInterval * 1000L else calculateBackoffMs(newFailureCount)
        val nextRetryTime = currentTime + delay

        val newMetadata = BatchMetadata(
            failureCount = newFailureCount,
            nextRetryTime = nextRetryTime,
            firstFailureTime = firstFailureTime,
            deferAfterExhaustion = response.statusCode == 408 || response.statusCode in 500..599
        )

        return state.copy(
            batchMetadata = state.batchMetadata + (response.batchFile to newMetadata)
        )
    }

    private fun calculateBackoffMs(failureCount: Int): Long {
        val base = config.backoffConfig.baseBackoffInterval * 1000
        val max = config.backoffConfig.maxBackoffInterval * 1000L

        val exponentialBackoff = base * Math.pow(2.0, (failureCount - 1).toDouble())
        val cappedBackoff = minOf(exponentialBackoff, max.toDouble())

        val jitterAmount = cappedBackoff * (config.backoffConfig.jitterPercent / 100.0)
        val jitter = (random.nextDouble() * jitterAmount).toLong()

        return minOf(cappedBackoff + jitter, max.toDouble()).toLong()
    }

    fun shouldUploadBatch(
        state: RetryState,
        batchFile: String
    ): Pair<UploadDecision, RetryState> {
        // Legacy mode: skip all smart retry logic
        if (isLegacyMode) {
            return UploadDecision.Proceed to state
        }

        val currentTime = timeProvider.currentTimeMillis()

        // Check 1: Global rate limiting
        if (state.isRateLimited(currentTime)) {
            return UploadDecision.SkipAllBatches to state
        }

        // Clear stale rate limit state if it has expired
        val clearedState = if (state.pipelineState == PipelineState.RATE_LIMITED &&
                               state.waitUntilTime != null &&
                               currentTime >= state.waitUntilTime) {
            state.copy(
                pipelineState = PipelineState.READY,
                waitUntilTime = null
            )
        } else {
            state
        }

        val metadata = clearedState.batchMetadata[batchFile]
        if (metadata != null) {
            val enforceLimits = config.backoffConfig.enabled && !metadata.deferAfterExhaustion
            // Check retry count limit (must be checked before duration per spec)
            if (enforceLimits &&
                metadata.failureCount >= config.backoffConfig.maxRetryCount) {
                return UploadDecision.DropBatch(DropReason.MAX_RETRIES_EXCEEDED) to
                       clearedState.removeBatch(batchFile)
            }

            // Check duration limit
            if (enforceLimits &&
                metadata.exceedsMaxDuration(currentTime, config.backoffConfig.maxTotalBackoffDuration * 1000)) {
                return UploadDecision.DropBatch(DropReason.MAX_DURATION_EXCEEDED) to
                       clearedState.removeBatch(batchFile)
            }

            // Check if backoff time has passed
            if (config.backoffConfig.enabled && !metadata.shouldRetry(currentTime)) {
                return UploadDecision.SkipThisBatch to clearedState
            }
        }

        return UploadDecision.Proceed to clearedState
    }

    fun getRetryCount(state: RetryState, batchFile: String): Int {
        val batchRetryCount = state.batchMetadata[batchFile]?.failureCount ?: 0
        return maxOf(batchRetryCount, state.globalRetryCount)
    }

    /**
     * Returns true if an error response should result in deleting the batch file.
     * Smart retries retain the file only when the configured handler can retry it.
     */
    fun shouldDeleteBatch(statusCode: Int): Boolean {
        if (isLegacyMode) {
            return statusCode in 400..499 && statusCode != 408 && statusCode != 429
        }
        if (statusCode in 200..299) return true // Success: file can be removed
        // 429 with rate limit handling disabled: delete
        if (statusCode == 429) return !config.rateLimitConfig.enabled
        val behavior = resolveStatusCodeBehavior(statusCode)
        // Retryable error with backoff disabled: delete
        if (behavior == RetryBehavior.RETRY && !config.backoffConfig.enabled) return true
        return behavior == RetryBehavior.DROP
    }

    private fun resolveStatusCodeBehavior(code: Int): RetryBehavior {
        config.backoffConfig.statusCodeOverrides[code]?.let { return it }

        return when (code) {
            in 400..499 -> config.backoffConfig.default4xxBehavior
            in 500..599 -> config.backoffConfig.default5xxBehavior
            else -> config.backoffConfig.unknownCodeBehavior
        }
    }
}

private fun RetryState.removeBatch(batchFile: String): RetryState {
    return copy(batchMetadata = batchMetadata - batchFile)
}

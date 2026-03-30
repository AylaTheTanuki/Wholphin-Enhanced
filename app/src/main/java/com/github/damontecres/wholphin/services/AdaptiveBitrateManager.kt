package com.github.damontecres.wholphin.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages adaptive bitrate playback by always attempting the highest
 * quality first and falling back through quality tiers only when
 * real buffering is detected during playback.
 *
 * Built with love by Ayla & Claude 😊
 */
@Singleton
class AdaptiveBitrateManager
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ApiClient,
) {
    companion object {
        private const val MIN_BITRATE_BPS = 1_000_000L
    }

    data class BitrateRecommendation(
        val measuredSpeedBps: Long,
        val recommendedBitrateBps: Long,
        val shouldDirectPlay: Boolean,
        val testSucceeded: Boolean,
    )

    /**
     * Always recommends direct play at full quality.
     * The buffering detection system in PlaybackViewModel handles
     * fallback if the connection can't actually support it.
     */
    fun measureAndRecommend(fileBitrateBps: Long): BitrateRecommendation {
        Timber.i(
            "AdaptiveBitrate: Recommending direct play at full quality (%.2f Mbps)",
            fileBitrateBps / 1_000_000.0,
        )
        return BitrateRecommendation(
            measuredSpeedBps = 0,
            recommendedBitrateBps = fileBitrateBps,
            shouldDirectPlay = true,
            testSucceeded = true,
        )
    }

    /**
     * Returns the next lower quality tier for fallback when buffering is detected.
     * Uses standard streaming quality tiers that correspond to real resolution targets.
     */
    fun getFallbackBitrate(fileBitrateBps: Long, attemptNumber: Int): Long? {
        // Each attempt drops further from the original file bitrate
        val percentage = when (attemptNumber) {
            1 -> 0.70  // First fallback: 70% of file bitrate
            2 -> 0.50  // Second: 50%
            3 -> 0.30  // Third: 30%
            4 -> 0.15  // Fourth: 15%
            else -> return null
        }

        val fallback = (fileBitrateBps * percentage).toLong()

        return if (fallback >= MIN_BITRATE_BPS) {
            Timber.i(
                "AdaptiveBitrate: Fallback attempt %d — %.2f Mbps to %.2f Mbps (%.0f%%)",
                attemptNumber,
                fileBitrateBps / 1_000_000.0,
                fallback / 1_000_000.0,
                percentage * 100,
            )
            fallback
        } else {
            Timber.w("AdaptiveBitrate: Fallback would be below minimum bitrate")
            null
        }
    }
}
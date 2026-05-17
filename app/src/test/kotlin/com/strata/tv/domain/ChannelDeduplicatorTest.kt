package com.strata.tv.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parity tests for [ChannelDeduplicator].
 *
 * Mirrors v1's `test/services/channel_deduplicator_test.dart` — same
 * inputs, same expected dedup behaviour.  A test data class
 * [TestChannel] stands in for v1's `ChannelWithContent` so the
 * deduplicator's generic API exercises cleanly.
 */
class ChannelDeduplicatorTest {

    private data class TestChannel(val displayName: String, val tvgId: String = "")

    private fun dedupe(channels: List<TestChannel>): List<TestChannel> =
        ChannelDeduplicator.dedupe(
            channels = channels,
            displayName = TestChannel::displayName,
            tvgId = TestChannel::tvgId,
            withTvgId = { ch, id -> ch.copy(tvgId = id) },
        )

    @Test
    fun `single channel passes through`() {
        val out = dedupe(listOf(TestChannel("UK: BBC 1", "bbc1")))
        assertThat(out).hasSize(1)
        assertThat(out[0].tvgId).isEqualTo("bbc1")
    }

    @Test
    fun `duplicates collapse to one row`() {
        val out = dedupe(
            listOf(
                TestChannel("UK: BBC 1", "bbc1"),
                TestChannel("UKHD BBC 1", "bbc1"),
                TestChannel("HEVC FHD BBC 1", ""),
            ),
        )
        assertThat(out).hasSize(1)
    }

    @Test
    fun `highest quality wins`() {
        val out = dedupe(
            listOf(
                TestChannel("UK: BBC 1", "bbc1"),
                TestChannel("HEVC FHD BBC 1", ""),
            ),
        )
        // HEVC > Standard, so HEVC wins.  Original tvgId was empty,
        // but should inherit "bbc1" from the lower-quality variant.
        assertThat(out).hasSize(1)
        assertThat(out[0].displayName).isEqualTo("HEVC FHD BBC 1")
        assertThat(out[0].tvgId).isEqualTo("bbc1")
    }

    @Test
    fun `BBC One and BBC1 are recognised as the same channel`() {
        val out = dedupe(
            listOf(
                TestChannel("UK: BBC One", "bbc.one"),
                TestChannel("UK: BBC1", "bbc1"),
            ),
        )
        assertThat(out).hasSize(1)
    }

    @Test
    fun `regional variants collapse to one row`() {
        val out = dedupe(
            listOf(
                TestChannel("UK: BBC 1 London"),
                TestChannel("UK: BBC 1 Scotland"),
                TestChannel("UK: BBC 1 Wales"),
            ),
        )
        assertThat(out).hasSize(1)
    }

    @Test
    fun `timeshift +1 channels stay distinct from base channel`() {
        val out = dedupe(
            listOf(
                TestChannel("UK: ITV 2"),
                TestChannel("UK: ITV 2 +1"),
            ),
        )
        assertThat(out).hasSize(2)
    }

    @Test
    fun `London Live channels are hidden`() {
        val out = dedupe(
            listOf(
                TestChannel("UK: BBC 1"),
                TestChannel("UK: London Live"),
                TestChannel("UKHD London Live"),
            ),
        )
        assertThat(out).hasSize(1)
        assertThat(out[0].displayName).isEqualTo("UK: BBC 1")
    }

    @Test
    fun `cleanChannelName strips quality prefixes and tags`() {
        assertThat(ChannelDeduplicator.cleanChannelName("HEVC FHD BBC 1"))
            .isEqualTo("BBC 1")
        assertThat(ChannelDeduplicator.cleanChannelName("UKHD Sky Sports HD"))
            .isEqualTo("Sky Sports")
        assertThat(ChannelDeduplicator.cleanChannelName("Channel 4 [VIP]"))
            .isEqualTo("Channel 4")
        assertThat(ChannelDeduplicator.cleanChannelName("Channel 4 60FPS"))
            .isEqualTo("Channel 4")
    }
}

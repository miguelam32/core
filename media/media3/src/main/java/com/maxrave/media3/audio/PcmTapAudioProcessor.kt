package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.media3.extension.PcmUdpExporter
import java.nio.ByteBuffer

@UnstableApi
class PcmTapAudioProcessor(
    private val enabled: () -> Boolean,
) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0
    private var lastSentSampleRate = -1
    private var lastSentChannelCount = -1
    private var wasEnabled = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        lastSentSampleRate = -1
        lastSentChannelCount = -1
        return inputAudioFormat
    }

    override fun onFlush() {
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        lastSentSampleRate = -1
        lastSentChannelCount = -1
        wasEnabled = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val isEnabledNow = enabled() && sampleRate > 0 && channelCount > 0
        if (isEnabledNow) {
            val justEnabled = !wasEnabled
            val formatChanged = sampleRate != lastSentSampleRate || channelCount != lastSentChannelCount
            if (justEnabled || formatChanged) {
                PcmUdpExporter.sendFormat(sampleRate, channelCount)
                lastSentSampleRate = sampleRate
                lastSentChannelCount = channelCount
            }
            val snapshot = ByteArray(remaining)
            inputBuffer.duplicate().get(snapshot)
            PcmUdpExporter.sendAudio(snapshot)
        }
        wasEnabled = isEnabledNow

        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }
}

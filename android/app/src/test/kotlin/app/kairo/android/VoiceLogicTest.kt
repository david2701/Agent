package app.kairo.android

import app.kairo.core.voice.AudioSpec
import app.kairo.core.voice.EndOfSpeech
import app.kairo.android.voice.WakeWordDetector
import app.kairo.core.voice.levelOf
import app.kairo.core.voice.pcmToWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceLogicTest {
    private val fps = AudioSpec.SAMPLE_RATE / AudioSpec.FRAME

    @Test
    fun `fin de frase tras silencio`() {
        val eos = EndOfSpeech(noiseFloor = 150.0)
        repeat(10) { assertEquals(EndOfSpeech.Verdict.Continue, eos.push(120.0)) } // ruido de fondo
        repeat(fps) { assertEquals(EndOfSpeech.Verdict.Continue, eos.push(3000.0)) } // 1 s de voz
        assertTrue(eos.heardSpeech)
        var verdict = EndOfSpeech.Verdict.Continue
        var frames = 0
        while (verdict == EndOfSpeech.Verdict.Continue && frames < fps * 3) { verdict = eos.push(100.0); frames++ }
        assertEquals(EndOfSpeech.Verdict.Done, verdict)
        assertTrue("debe cortar cerca de 0,9 s de silencio", frames in (fps * 0.8).toInt()..(fps * 1.1).toInt())
    }

    @Test
    fun `sin voz se cancela`() {
        val eos = EndOfSpeech()
        var verdict = EndOfSpeech.Verdict.Continue
        var frames = 0
        while (verdict == EndOfSpeech.Verdict.Continue) { verdict = eos.push(90.0); frames++ }
        assertEquals(EndOfSpeech.Verdict.NoSpeech, verdict)
        assertTrue(frames <= fps * 6 + 1)
    }

    @Test
    fun `cabecera wav valida`() {
        val pcm = ShortArray(16000) { (it % 100).toShort() }
        val wav = pcmToWav(pcm, pcm.size)
        assertEquals(44 + 32000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, bb.getInt(24))
        assertEquals(32000, bb.getInt(40))
    }

    @Test
    fun `variantes de la frase de activacion`() {
        val v = WakeWordDetector.variants("Oye Kairo")
        assertTrue("oye cairo" in v)
        assertTrue("hey cairo" in v)
    }

    @Test
    fun `nivel de audio acotado`() {
        assertEquals(0f, levelOf(10.0))
        assertTrue(levelOf(30000.0) in 0.9f..1f)
    }
}

package app.kairo.android

import app.kairo.core.voice.AudioSpec
import app.kairo.core.voice.EndOfSpeech
import app.kairo.android.voice.WakeLanguage
import app.kairo.android.voice.WakeMatcher
import app.kairo.core.voice.levelOf
import app.kairo.core.voice.pcmToWav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class VoiceLogicTest {
    private val fps = AudioSpec.SAMPLE_RATE / AudioSpec.FRAME

    @Test
    fun `end of speech after silence`() {
        val eos = EndOfSpeech(noiseFloor = 150.0)
        repeat(10) { assertEquals(EndOfSpeech.Verdict.Continue, eos.push(120.0)) } // background noise
        repeat(fps) { assertEquals(EndOfSpeech.Verdict.Continue, eos.push(3000.0)) } // 1 s of speech
        assertTrue(eos.heardSpeech)
        var verdict = EndOfSpeech.Verdict.Continue
        var frames = 0
        while (verdict == EndOfSpeech.Verdict.Continue && frames < fps * 3) { verdict = eos.push(100.0); frames++ }
        assertEquals(EndOfSpeech.Verdict.Done, verdict)
        assertTrue("must cut near 0.9 s of silence", frames in (fps * 0.8).toInt()..(fps * 1.1).toInt())
    }

    @Test
    fun `no speech is cancelled`() {
        val eos = EndOfSpeech()
        var verdict = EndOfSpeech.Verdict.Continue
        var frames = 0
        while (verdict == EndOfSpeech.Verdict.Continue) { verdict = eos.push(90.0); frames++ }
        assertEquals(EndOfSpeech.Verdict.NoSpeech, verdict)
        assertTrue(frames <= fps * 6 + 1)
    }

    @Test
    fun `valid wav header`() {
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
    fun `wake phrase variants include both languages`() {
        for (phrase in listOf("Hey Kairo", "Oye Kairo", "hey kairo", "oye kairo")) {
            val v = WakeMatcher.variants(phrase)
            assertTrue("hey cairo" in v)
            assertTrue("oye cairo" in v)
        }
    }

    @Test
    fun `wake matcher accepts hey kairo and oye kairo`() {
        // Whatever the configured phrase is, both greetings wake Kairo
        for (phrase in listOf("hey kairo", "oye kairo")) {
            assertTrue(WakeMatcher.matches("hey cairo", phrase))
            assertTrue(WakeMatcher.matches("oye cairo", phrase))
            assertTrue(WakeMatcher.matches("ey cairo", phrase))
            assertTrue(WakeMatcher.matches("Hey Kairo", phrase))
            assertTrue(WakeMatcher.matches("[unk] oye cairo", phrase))
        }
    }

    @Test
    fun `wake matcher accepts the bare name when the model drops the greeting`() {
        // The English model has no word "oye": a closed grammar may return just "cairo"
        assertTrue(WakeMatcher.matches("cairo", "hey kairo"))
        assertTrue(WakeMatcher.matches("cairo", "oye kairo"))
    }

    @Test
    fun `wake matcher rejects other speech`() {
        assertFalse(WakeMatcher.matches("", "hey kairo"))
        assertFalse(WakeMatcher.matches("[unk]", "hey kairo"))
        assertFalse(WakeMatcher.matches("hey google", "hey kairo"))
        assertFalse(WakeMatcher.matches("[unk] cairo", "hey kairo"))
        assertFalse(WakeMatcher.matches("visit cairo in spring", "oye kairo"))
    }

    @Test
    fun `wake model follows the app language`() {
        assertEquals(WakeLanguage.Spanish, WakeLanguage.forLocale(Locale.forLanguageTag("es-CO")))
        assertEquals(WakeLanguage.Spanish, WakeLanguage.forLocale(Locale.forLanguageTag("es-ES")))
        assertEquals(WakeLanguage.English, WakeLanguage.forLocale(Locale.US))
        assertEquals(WakeLanguage.English, WakeLanguage.forLocale(Locale.FRANCE))
        // The Spanish folder keeps its old name so an already downloaded model keeps working
        assertEquals("vosk-es", WakeLanguage.Spanish.dirName)
        assertTrue(WakeLanguage.English.modelUrl.endsWith("vosk-model-small-en-us-0.15.zip"))
    }

    @Test
    fun `audio level is bounded`() {
        assertEquals(0f, levelOf(10.0))
        assertTrue(levelOf(30000.0) in 0.9f..1f)
    }
}

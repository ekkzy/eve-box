package io.github.ekkzy.evebox.ui;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class UiSoundsTest {
    @Test public void threeCuesHaveBoundedMonoPcmHeaders() {
        verifyHeader(UiSounds.synthesize(UiSounds.Cue.MOVE), 1536);
        verifyHeader(UiSounds.synthesize(UiSounds.Cue.SELECT), 3072);
        verifyHeader(UiSounds.synthesize(UiSounds.Cue.BACK), 2304);
    }

    @Test public void everyCueHasSilentEndpointsAndNoClipping() {
        for (UiSounds.Cue cue : UiSounds.Cue.values()) {
            short[] pcm = samples(UiSounds.synthesize(cue));
            assertEquals(0, pcm[0]); assertEquals(0, pcm[pcm.length - 1]);
            assertTrue("Attack stays quiet", rms(pcm, 0, 48) < rms(pcm, pcm.length / 3, pcm.length * 2 / 3) * .1);
            assertTrue("Release stays quiet", rms(pcm, pcm.length - 48, pcm.length) < rms(pcm, pcm.length / 3, pcm.length * 2 / 3) * .1);
            assertTrue("A defined cue contains real signal", rms(pcm, 0, pcm.length) > 3000);
            long mean = 0;
            for (short sample : pcm) {
                assertTrue("PCM headroom", Math.abs((int) sample) <= 14418);
                mean += sample;
            }
            assertTrue("No DC click", Math.abs(mean / (double) pcm.length) < 20);
        }
    }

    @Test public void selectRisesAndBackFallsInPitch() {
        short[] select = samples(UiSounds.synthesize(UiSounds.Cue.SELECT));
        short[] back = samples(UiSounds.synthesize(UiSounds.Cue.BACK));
        assertTrue("Selection is an ascending chirp", crossings(select, select.length / 2, select.length - 8)
                >= crossings(select, 8, select.length / 2) + 4);
        assertTrue("Back has a descending contour", crossings(back, 8, back.length / 2)
                >= crossings(back, back.length / 2, back.length - 8) + 2);
    }

    @Test public void cueGenerationIsDeterministicAndRejectsMissingCue() {
        for (UiSounds.Cue cue : UiSounds.Cue.values()) assertArrayEquals(UiSounds.synthesize(cue), UiSounds.synthesize(cue));
        assertThrows(IllegalArgumentException.class, () -> UiSounds.synthesize(null));
    }

    @Test public void interruptedPreparationPublishesNoPartialWav() {
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class, () -> UiSounds.synthesize(UiSounds.Cue.SELECT)); }
        finally { Thread.interrupted(); }
    }

    private static void verifyHeader(byte[] wav, int count) {
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(44 + count * 2, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(wav.length - 8, data.getInt(4));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(16, data.getInt(16)); assertEquals(1, data.getShort(20)); assertEquals(1, data.getShort(22));
        assertEquals(48000, data.getInt(24)); assertEquals(96000, data.getInt(28));
        assertEquals(2, data.getShort(32)); assertEquals(16, data.getShort(34));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII)); assertEquals(count * 2, data.getInt(40));
    }
    private static short[] samples(byte[] wav) {
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN); data.position(44);
        short[] result = new short[(wav.length - 44) / 2];
        for (int i = 0; i < result.length; i++) result[i] = data.getShort();
        return result;
    }
    private static double rms(short[] samples, int start, int end) {
        double sum = 0; for (int i = start; i < end; i++) sum += samples[i] * (double) samples[i];
        return Math.sqrt(sum / (end - start));
    }
    private static int crossings(short[] samples, int start, int end) {
        int count = 0;
        for (int i = start + 1; i < end; i++) if (samples[i - 1] <= 0 && samples[i] > 0) count++;
        return count;
    }
}

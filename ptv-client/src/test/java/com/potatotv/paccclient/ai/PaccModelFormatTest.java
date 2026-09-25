package com.potatotv.paccclient.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaccModelFormatTest {

    @Test
    void roundTrip() throws Exception {
        float[] w = {1.5f, -2.25f, 0.0f, 3.125f};
        PaccModelFormat.ModelHeader h = new PaccModelFormat.ModelHeader(
                PaccModelFormat.FORMAT_VERSION, PaccModelFormat.TYPE_AUTOENCODER, 4, w.length * 4);
        byte[] raw = PaccModelFormat.encode(h, w);
        assertEquals(PaccModelFormat.HEADER_LEN + w.length * 4 + PaccModelFormat.CHECKSUM_LEN, raw.length);

        PaccModelFormat.ReadModel rm = PaccModelFormat.read(new ByteArrayInputStream(raw));
        assertEquals(h, rm.header());
        assertArrayEquals(w, rm.weights(), 0f);
        assertArrayEquals(PaccModelFormat.toBytes(w), rm.rawWeights());
        assertEquals(PaccModelFormat.sha256Hex(PaccModelFormat.toBytes(w)),
                PaccModelFormat.sha256Hex(rm.rawWeights()));
    }

    @Test
    void corruptedChecksumRejected() {
        byte[] raw = sample();
        raw[raw.length - 1] ^= 0xFF;
        assertThrows(ModelFormatException.class, () -> PaccModelFormat.read(raw));
    }

    @Test
    void badMagicRejected() {
        byte[] raw = sample();
        raw[0] = 'X';
        assertThrows(ModelFormatException.class, () -> PaccModelFormat.read(raw));
    }

    @Test
    void truncatedStreamRejected() {
        byte[] raw = sample();
        byte[] cut = Arrays.copyOf(raw, raw.length - 10);
        assertThrows(ModelFormatException.class, () -> PaccModelFormat.read(cut));
        byte[] tiny = Arrays.copyOf(raw, 8);
        assertThrows(ModelFormatException.class, () -> PaccModelFormat.peekHeader(tiny));
    }

    @Test
    void hugeWeightLengthRejected() {
        byte[] bad = buildHeader(PaccModelFormat.FORMAT_VERSION, PaccModelFormat.TYPE_XGBOOST, 128, 0x7FFFFFFF);
        assertThrows(ModelFormatException.class, () -> PaccModelFormat.peekHeader(bad));
        assertThrows(ModelFormatException.class, () -> PaccModelFormat.read(bad));
    }

    private static byte[] sample() {
        float[] w = {1.5f, -2.25f, 0.0f, 3.125f};
        return PaccModelFormat.encode(new PaccModelFormat.ModelHeader(
                PaccModelFormat.FORMAT_VERSION, PaccModelFormat.TYPE_XGBOOST, 4, w.length * 4), w);
    }

    /** 手工拼一个声明超大 weightLength 的头部 + 校验位占位。 */
    private static byte[] buildHeader(int version, int type, int dim, int weightLen) {
        ByteBuffer bb = ByteBuffer.allocate(PaccModelFormat.HEADER_LEN + PaccModelFormat.CHECKSUM_LEN)
                .order(ByteOrder.BIG_ENDIAN);
        bb.put(new byte[]{'P', 'A', 'M', '1'});
        bb.putShort((short) version);
        bb.put((byte) type);
        bb.putInt(dim);
        bb.putInt(weightLen);
        return bb.array();
    }
}
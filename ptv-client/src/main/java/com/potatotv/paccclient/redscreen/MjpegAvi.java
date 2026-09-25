package com.potatotv.paccclient.redscreen;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 极简 MJPEG-AVI 封装（文档 §7.3 查端回放的产物容器）。
 *
 * <p><b>为什么不是 H.264 MP4</b>：H.264 需要编码器，纯 JDK 没有；引入 JCodec 之类的库与客户端
 * 「零第三方依赖」的约定冲突。MJPEG（每帧独立 JPEG）用 {@code ImageIO} 就能编码，
 * AVI 容器本身只是 RIFF 分块，几十行就能写完，而且 VLC / ffmpeg / 系统播放器都能直接播放——
 * 管理员查端时想看的是「红屏前后发生了什么」，这一点 MJPEG 完全够用。</p>
 *
 * <p>结构：{@code RIFF/AVI → LIST hdrl(avih + LIST strl(strh + strf)) → LIST movi(00dc 逐帧) → idx1}，
 * 全部小端序；{@code idx1} 的偏移按规范相对 {@code movi} 数据区起点。</p>
 */
final class MjpegAvi {

    private static final int AVIF_HASINDEX = 0x00000010;
    private static final int AVIIF_KEYFRAME = 0x00000010;
    /** avih（MainAVIHeader）字节数。 */
    private static final int AVIH_LEN = 56;
    /** strh（AVIStreamHeader）字节数。 */
    private static final int STRH_LEN = 56;
    /** strf（BITMAPINFOHEADER）字节数。 */
    private static final int STRF_LEN = 40;

    private MjpegAvi() {
    }

    /**
     * 把一组 JPEG 帧封装为 AVI。
     *
     * @param frames JPEG 帧（每帧尺寸一致）
     * @param fps    帧率
     * @param width  宽
     * @param height 高
     */
    static byte[] encode(List<byte[]> frames, int fps, int width, int height) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("没有可封装的帧");
        int frameCount = frames.size();
        long moviDataSize = 0;
        for (byte[] f : frames) moviDataSize += 8L + f.length + (f.length & 1);
        long moviListSize = 4 + moviDataSize;                 // "movi" + 数据
        long idx1Size = 8L + 16L * frameCount;
        long totalData = 4 + (8 + AVIH_LEN)                            // AVI + avih 块
                + (12 + 4 + (8 + STRH_LEN) + (8 + STRF_LEN))           // LIST strl
                + (8 + moviListSize)                                    // LIST movi
                + idx1Size;

        int hint = 12 + (int) Math.min(Integer.MAX_VALUE - 64L, totalData);
        ByteArrayOutputStream out = new ByteArrayOutputStream(hint);
        riff(out, "RIFF", totalData);
        fourcc(out, "AVI ");

        // ---- LIST hdrl ----
        fourcc(out, "LIST");
        le32(out, 4 + (8 + AVIH_LEN) + (12 + 4 + (8 + STRH_LEN) + (8 + STRF_LEN)));
        fourcc(out, "hdrl");
        chunk(out, "avih", avih(fps, frameCount, width, height));

        fourcc(out, "LIST");
        le32(out, 4 + (8 + STRH_LEN) + (8 + STRF_LEN));
        fourcc(out, "strl");
        chunk(out, "strh", strh(fps, frameCount, width, height));
        chunk(out, "strf", strf(width, height));

        // ---- LIST movi：逐帧 00dc 块，同时记录 idx1 偏移 ----
        fourcc(out, "LIST");
        le32(out, moviListSize);
        fourcc(out, "movi");
        long[] offsets = new long[frameCount];
        long[] sizes = new long[frameCount];
        long position = 0;                                    // 相对 movi 数据区起点
        for (int i = 0; i < frameCount; i++) {
            byte[] frame = frames.get(i);
            offsets[i] = position;
            sizes[i] = frame.length;
            fourcc(out, "00dc");
            le32(out, frame.length);
            out.write(frame, 0, frame.length);
            position += 8L + frame.length;
            if ((frame.length & 1) == 1) {
                out.write(0);
                position++;
            }
        }

        // ---- idx1 ----
        fourcc(out, "idx1");
        le32(out, 16L * frameCount);
        for (int i = 0; i < frameCount; i++) {
            fourcc(out, "00dc");
            le32(out, AVIIF_KEYFRAME);
            le32(out, offsets[i]);
            le32(out, sizes[i]);
        }
        return out.toByteArray();
    }

    // ------------------------------ 头部块 ------------------------------

    private static byte[] avih(int fps, int frameCount, int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(AVIH_LEN).order(ByteOrder.LITTLE_ENDIAN);
        int usecPerFrame = fps <= 0 ? 200_000 : 1_000_000 / fps;
        int bufferHint = bufferHint(width, height);
        b.putInt(usecPerFrame);
        b.putInt(bufferHint);        // 最大每秒字节数（播放器仅作提示）
        b.putInt(0);                 // 填充
        b.putInt(AVIF_HASINDEX);
        b.putInt(frameCount);
        b.putInt(0);                 // 初始帧
        b.putInt(1);                 // 流数
        b.putInt(bufferHint);        // 建议缓冲
        b.putInt(width);
        b.putInt(height);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        return b.array();
    }

    private static byte[] strh(int fps, int frameCount, int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(STRH_LEN).order(ByteOrder.LITTLE_ENDIAN);
        b.put("vids".getBytes(StandardCharsets.US_ASCII));   // fccType
        b.put("MJPG".getBytes(StandardCharsets.US_ASCII));   // fccHandler
        b.putInt(0);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putInt(0);
        b.putInt(1);                                          // 时间尺度
        b.putInt(Math.max(1, fps));                           // rate/scale = fps
        b.putInt(0);
        b.putInt(frameCount);
        b.putInt(bufferHint(width, height));
        b.putInt(-1);
        b.putInt(0);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) width);
        b.putShort((short) height);
        return b.array();
    }

    private static byte[] strf(int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(STRF_LEN).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(STRF_LEN);
        b.putInt(width);
        b.putInt(height);
        b.putShort((short) 1);                                // 平面数
        b.putShort((short) 24);                               // 位深（MJPEG 播放器按此解码）
        b.putInt(0);                                          // BI_RGB
        b.putInt(width * height * 3);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        return b.array();
    }

    /** 播放器提示用的「每秒最大字节数」：按 720p JPEG 的保守上限估算，不参与解码。 */
    private static int bufferHint(int width, int height) {
        return Math.max(1, width * height / 4);
    }

    // ------------------------------ RIFF 写入工具 ------------------------------

    private static void riff(ByteArrayOutputStream out, String id, long size) {
        fourcc(out, id);
        le32(out, size);
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] body) {
        fourcc(out, id);
        le32(out, body.length);
        out.write(body, 0, body.length);
        if ((body.length & 1) == 1) out.write(0);
    }

    private static void fourcc(ByteArrayOutputStream out, String id) {
        byte[] b = id.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, Math.min(4, b.length));
        for (int i = b.length; i < 4; i++) out.write(' ');
    }

    private static void le32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }
}
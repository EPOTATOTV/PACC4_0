package com.potatotv.paccclient.detection.vision;

import com.potatotv.paccclient.detection.analysis.Stats;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯 Java 图像分析：从单帧画面提取 HUD 作弊元素与画面运动量（文档 §3.2 / §3.3.2）。
 *
 * <p>零第三方依赖、无反射、纯 JDK。相比文档原方案的 OpenCV，这里少了形态学、霍夫变换与
 * 模板匹配，只能做基础检测：Sobel 梯度 + 自适应阈值出边缘图，再用「行/列游程」拼装矩形、
 * 长竖线、环状边缘与密集文字块的粗略计数。阈值不是学出来的，是按合成图与常见 HUD 尺寸
 * 定的经验值（均值 + 2×标准差做自适应，其余为固定物理量阈值），误报靠上层多信号加权兜住。</p>
 *
 * <p><b>隐私边界</b>：本类只在内存里分析，输出全是数字；不保存、不上传任何原始画面。</p>
 *
 * <p>本类有状态：保留上一帧的灰度缩略（32×18 块均值）用于帧间运动差，因此同一实例需连续调用
 * {@link #analyze(BufferedImage)}。</p>
 */
public final class HudDetector {

    /** 分析前把灰度图缩放到该宽度，控制 Sobel 成本。 */
    private static final int MAX_ANALYZE_WIDTH = 640;
    /** 自适应阈值下限：全平画面 mean+2std 会趋近 0，给个地板避免把噪声当边缘。 */
    private static final double SOBEL_MIN_THRESHOLD = 8.0;
    /** 水平边缘段长度范围（像素）。 */
    private static final int HUD_MIN_LEN = 20;
    private static final int HUD_MAX_LEN = 400;
    /** 矩形侧边最短长度。 */
    private static final int HUD_VERT_MIN = 12;
    /** 端点匹配容差（行/列，像素）。 */
    private static final int HUD_END_TOLERANCE = 6;
    private static final int HUD_COL_TOLERANCE = 4;
    /** 候选水平段上限，避免逐像素 O(n²)。 */
    private static final int HUD_MAX_CANDIDATES = 64;

    /** 透视长竖线最小长度。 */
    private static final int ESP_MIN_LEN = 80;

    /** 杀戮光环：中心向 36 个方向射线找首个强边缘。 */
    private static final int KILLAURA_RAYS = 36;
    private static final double KILLAURA_RADIUS_MIN = 50.0;
    private static final double KILLAURA_RADIUS_MAX = 300.0;
    private static final double KILLAURA_VARIANCE_RATIO = 0.15;

    /** 作弊菜单文字：边缘密集的小方窗。 */
    private static final int CHEAT_BLOCK = 16;
    private static final double CHEAT_DENSITY = 0.35;
    /** 左右边缘带宽度（x&lt;50 或 x&gt;w-200）。 */
    private static final int CHEAT_LEFT_BAND = 50;
    private static final int CHEAT_RIGHT_BAND = 200;

    /** 全图灰度标准差低于该值视为黑屏 / 纯色遮挡。 */
    private static final double ANOMALY_STD = 2.0;

    /** 运动缩略网格。 */
    private static final int MOTION_GRID_W = 32;
    private static final int MOTION_GRID_H = 18;
    /** 运动不匹配阈值（文档 §3.3.2）。 */
    private static final double MOTION_MISMATCH_THRESHOLD = 2.0;
    private static final double MOTION_MISMATCH_SPEED = 10.0;

    private double[] previousThumbnail;

    /**
     * 一帧分析结果。
     *
     * @param hudBoxCount        自瞄方框数
     * @param espLines           透视长竖线数
     * @param killauraCircle     是否有杀戮光环圆圈
     * @param cheatMenuText      作弊菜单文字区域数
     * @param screenshotAnomaly  截屏异常（黑屏 / 被遮挡）
     * @param motionMagnitude    帧间运动量（0-255 尺度，块均值平均绝对差）
     */
    public record VisionAnalysis(int hudBoxCount, int espLines, boolean killauraCircle,
                                 int cheatMenuText, boolean screenshotAnomaly, double motionMagnitude) {
    }

    /**
     * 分析一帧。
     *
     * @param frame 抓屏帧（null / 空尺寸时返回全零 + 异常标记）
     */
    public VisionAnalysis analyze(BufferedImage frame) {
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return new VisionAnalysis(0, 0, false, 0, true, 0.0);
        }
        GrayImage gray = toGray(frame);
        int w = gray.width();
        int h = gray.height();
        int[] px = gray.pixels();

        boolean anomaly = grayStd(px) < ANOMALY_STD;

        double[] mag = sobel(px, w, h);
        double threshold = Math.max(SOBEL_MIN_THRESHOLD, Stats.mean(mag) + 2.0 * Stats.std(mag));
        boolean[] edge = new boolean[w * h];
        for (int i = 0; i < edge.length; i++) {
            edge[i] = mag[i] > threshold;
        }

        List<HRun> hRuns = horizontalRuns(edge, w, h);
        List<VRun> vRuns = verticalRuns(edge, w, h);

        int hudBoxCount = detectHudBoxes(hRuns, vRuns);
        int espLines = countEspLines(vRuns);
        boolean killaura = killauraCircle(edge, w, h);
        int cheatMenuText = cheatMenuText(edge, w, h);

        double motion = motionMagnitude(px, w, h);

        return new VisionAnalysis(hudBoxCount, espLines, killaura, cheatMenuText, anomaly, motion);
    }

    /**
     * 画面运动与网络速度是否不匹配（文档 §3.3.2）：画面几乎不动但网络层报出高速移动。
     *
     * @param motionMagnitude 本帧运动量
     * @param networkSpeed    网络层给出的速度（格/s）
     * @return 运动 &lt; 2.0 且速度 &gt; 10.0 才算
     */
    public boolean motionMismatch(double motionMagnitude, double networkSpeed) {
        return motionMagnitude < MOTION_MISMATCH_THRESHOLD && networkSpeed > MOTION_MISMATCH_SPEED;
    }

    // ------------------------------ 预处理 ------------------------------

    private record GrayImage(int width, int height, int[] pixels) {
    }

    /** 灰度化 + 缩放到宽度上限（块平均，避免细边缘被最近邻抹掉）。 */
    private static GrayImage toGray(BufferedImage src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int w = sw;
        int h = sh;
        if (sw > MAX_ANALYZE_WIDTH) {
            w = MAX_ANALYZE_WIDTH;
            h = Math.max(1, (int) Math.round(sh * (double) MAX_ANALYZE_WIDTH / sw));
        }
        int[] argb = src.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] gray = new int[w * h];
        for (int ty = 0; ty < h; ty++) {
            int y0 = (int) ((long) ty * sh / h);
            int y1 = (int) ((long) (ty + 1) * sh / h);
            if (y1 <= y0) y1 = y0 + 1;
            for (int tx = 0; tx < w; tx++) {
                int x0 = (int) ((long) tx * sw / w);
                int x1 = (int) ((long) (tx + 1) * sw / w);
                if (x1 <= x0) x1 = x0 + 1;
                long sum = 0;
                int n = 0;
                for (int sy = y0; sy < y1 && sy < sh; sy++) {
                    for (int sx = x0; sx < x1 && sx < sw; sx++) {
                        int p = argb[sy * sw + sx];
                        int r = (p >> 16) & 0xFF;
                        int g = (p >> 8) & 0xFF;
                        int b = p & 0xFF;
                        sum += (77 * r + 150 * g + 29 * b) >> 8;
                        n++;
                    }
                }
                gray[ty * w + tx] = n == 0 ? 0 : (int) (sum / n);
            }
        }
        return new GrayImage(w, h, gray);
    }

    private static double grayStd(int[] px) {
        double mean = 0;
        for (int v : px) mean += v;
        mean /= px.length;
        double var = 0;
        for (int v : px) var += (v - mean) * (v - mean);
        var /= px.length;
        return Math.sqrt(var);
    }

    /** Sobel 梯度幅值；边界像素记 0。 */
    private static double[] sobel(int[] px, int w, int h) {
        double[] mag = new double[w * h];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                int p00 = px[i - w - 1];
                int p01 = px[i - w];
                int p02 = px[i - w + 1];
                int p10 = px[i - 1];
                int p12 = px[i + 1];
                int p20 = px[i + w - 1];
                int p21 = px[i + w];
                int p22 = px[i + w + 1];
                double gx = (p02 + 2 * p12 + p22) - (p00 + 2 * p10 + p20);
                double gy = (p20 + 2 * p21 + p22) - (p00 + 2 * p01 + p02);
                mag[i] = Math.hypot(gx, gy);
            }
        }
        return mag;
    }

    // ------------------------------ 游程提取 ------------------------------

    private record HRun(int y, int x1, int x2) {
        int len() {
            return x2 - x1 + 1;
        }
    }

    private record VRun(int x, int y1, int y2) {
        int len() {
            return y2 - y1 + 1;
        }
    }

    /** 逐行游程提取水平边缘段，长度限定在 [HUD_MIN_LEN, HUD_MAX_LEN]，候选数封顶。 */
    private static List<HRun> horizontalRuns(boolean[] edge, int w, int h) {
        List<HRun> runs = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            int base = y * w;
            int x = 0;
            while (x < w) {
                if (!edge[base + x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < w && edge[base + x]) x++;
                int len = x - start;
                if (len >= HUD_MIN_LEN && len <= HUD_MAX_LEN) {
                    runs.add(new HRun(y, start, x - 1));
                    if (runs.size() >= HUD_MAX_CANDIDATES) return runs;
                }
            }
        }
        return runs;
    }

    /** 逐列游程提取垂直边缘段（长度 ≥ HUD_VERT_MIN）。 */
    private static List<VRun> verticalRuns(boolean[] edge, int w, int h) {
        List<VRun> runs = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            int y = 0;
            while (y < h) {
                if (!edge[y * w + x]) {
                    y++;
                    continue;
                }
                int start = y;
                while (y < h && edge[y * w + x]) y++;
                int len = y - start;
                if (len >= HUD_VERT_MIN) {
                    runs.add(new VRun(x, start, y - 1));
                }
            }
        }
        return runs;
    }

    // ------------------------------ HUD 元素 ------------------------------

    /**
     * 自瞄方框：找「横向长边 + 左右两段垂直长边 + 对边平行长段」的中空矩形。
     *
     * <p>用行游程 + 列游程拼装，避免逐像素 O(n²)：候选水平段 ≤ {@value #HUD_MAX_CANDIDATES}，
     * 每个候选只在列游程里做两次近邻查找。只从矩形上沿计数并用签名去重，避免上下沿与描边厚度重复计数。</p>
     */
    private static int detectHudBoxes(List<HRun> hRuns, List<VRun> vRuns) {
        Set<Long> seen = new HashSet<>();
        int count = 0;
        for (HRun top : hRuns) {
            VRun left = findVertical(vRuns, top.x1(), top.y());
            VRun right = findVertical(vRuns, top.x2(), top.y());
            if (left == null || right == null) {
                continue;
            }
            int lo = Math.max(left.y1(), right.y1());
            int hi = Math.min(left.y2(), right.y2());
            if (hi - lo < HUD_VERT_MIN) {
                continue;
            }
            // 当前行须靠近矩形上沿，否则视为下沿，跳过以避免重复计数
            if (top.y() - lo > HUD_END_TOLERANCE) {
                continue;
            }
            if (!hasParallelRun(hRuns, hi, top.x1(), top.x2())) {
                continue;
            }
            long sig = (((long) (top.x1() / 4) & 0xFFFF) << 48)
                    | (((long) (top.x2() / 4) & 0xFFFF) << 32)
                    | (((long) (lo / 4) & 0xFFFF) << 16)
                    | ((long) (hi / 4) & 0xFFFF);
            if (seen.add(sig)) {
                count++;
            }
        }
        return count;
    }

    private static VRun findVertical(List<VRun> vRuns, int col, int row) {
        VRun best = null;
        for (VRun r : vRuns) {
            if (Math.abs(r.x() - col) > HUD_COL_TOLERANCE) {
                continue;
            }
            if (row < r.y1() - HUD_END_TOLERANCE || row > r.y2() + HUD_END_TOLERANCE) {
                continue;
            }
            if (best == null || r.len() > best.len()) {
                best = r;
            }
        }
        return best;
    }

    private static boolean hasParallelRun(List<HRun> hRuns, int row, int x1, int x2) {
        int need = Math.max(HUD_MIN_LEN, (x2 - x1 + 1) / 2);
        for (HRun r : hRuns) {
            if (Math.abs(r.y() - row) > HUD_END_TOLERANCE) {
                continue;
            }
            int overlap = Math.min(r.x2(), x2) - Math.max(r.x1(), x1) + 1;
            if (overlap >= need) {
                return true;
            }
        }
        return false;
    }

    /**
     * 透视 ESP 长竖线数：长度 ≥ {@value #ESP_MIN_LEN} 的垂直游程，
     * 相邻列（≤2px、纵向重叠）视为同一条线合并。纯 Java 不做角度估计，列游程天然是垂直的。
     */
    private static int countEspLines(List<VRun> vRuns) {
        int count = 0;
        int lastX = Integer.MIN_VALUE;
        int lastY2 = 0;
        for (VRun r : vRuns) {
            if (r.len() < ESP_MIN_LEN) {
                continue;
            }
            if (count > 0 && r.x() - lastX <= 2 && r.y1() <= lastY2 + HUD_END_TOLERANCE) {
                lastY2 = Math.max(lastY2, r.y2());
                continue;
            }
            count++;
            lastX = r.x();
            lastY2 = r.y2();
        }
        return count;
    }

    /** 杀戮光环：36 方向射线找首个强边缘半径，半径方差 < 均值×0.15 且均值落在 50~300px → 是圆圈。 */
    private static boolean killauraCircle(boolean[] edge, int w, int h) {
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        double maxR = Math.min(KILLAURA_RADIUS_MAX, Math.max(w, h) / 2.0);
        double[] radii = new double[KILLAURA_RAYS];
        for (int k = 0; k < KILLAURA_RAYS; k++) {
            double ang = 2.0 * Math.PI * k / KILLAURA_RAYS;
            double dx = Math.cos(ang);
            double dy = Math.sin(ang);
            double found = -1;
            for (double rr = 5; rr <= maxR; rr += 1.0) {
                int x = (int) Math.round(cx + dx * rr);
                int y = (int) Math.round(cy + dy * rr);
                if (x < 0 || y < 0 || x >= w || y >= h) {
                    break;
                }
                if (edge[y * w + x]) {
                    found = rr;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            radii[k] = found;
        }
        double mean = Stats.mean(radii);
        double std = Stats.std(radii);
        return mean >= KILLAURA_RADIUS_MIN && mean <= KILLAURA_RADIUS_MAX
                && std < mean * KILLAURA_VARIANCE_RATIO;
    }

    /** 作弊菜单文字：左右边缘带内边缘密度 > 0.35 的 16×16 小窗计数。 */
    private static int cheatMenuText(boolean[] edge, int w, int h) {
        int count = 0;
        for (int y = 0; y + CHEAT_BLOCK <= h; y += CHEAT_BLOCK) {
            for (int x = 0; x + CHEAT_BLOCK <= w; x += CHEAT_BLOCK) {
                if (!(x < CHEAT_LEFT_BAND || x > w - CHEAT_RIGHT_BAND)) {
                    continue;
                }
                int hits = 0;
                for (int yy = y; yy < y + CHEAT_BLOCK; yy++) {
                    int base = yy * w;
                    for (int xx = x; xx < x + CHEAT_BLOCK; xx++) {
                        if (edge[base + xx]) hits++;
                    }
                }
                if ((double) hits / (CHEAT_BLOCK * CHEAT_BLOCK) > CHEAT_DENSITY) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------ 运动 ------------------------------

    /** 帧间运动量：32×18 块灰度均值之间的平均绝对差，并保存本帧缩略供下帧比较。 */
    private double motionMagnitude(int[] px, int w, int h) {
        double[] thumb = thumbnail(px, w, h);
        double motion = 0.0;
        if (previousThumbnail != null && previousThumbnail.length == thumb.length) {
            double sum = 0;
            for (int i = 0; i < thumb.length; i++) {
                sum += Math.abs(thumb[i] - previousThumbnail[i]);
            }
            motion = sum / thumb.length;
        }
        previousThumbnail = thumb;
        return motion;
    }

    private static double[] thumbnail(int[] px, int w, int h) {
        double[] out = new double[MOTION_GRID_W * MOTION_GRID_H];
        for (int gy = 0; gy < MOTION_GRID_H; gy++) {
            int y0 = (int) ((long) gy * h / MOTION_GRID_H);
            int y1 = (int) ((long) (gy + 1) * h / MOTION_GRID_H);
            if (y1 <= y0) y1 = y0 + 1;
            for (int gx = 0; gx < MOTION_GRID_W; gx++) {
                int x0 = (int) ((long) gx * w / MOTION_GRID_W);
                int x1 = (int) ((long) (gx + 1) * w / MOTION_GRID_W);
                if (x1 <= x0) x1 = x0 + 1;
                long sum = 0;
                int n = 0;
                for (int y = y0; y < y1 && y < h; y++) {
                    int base = y * w;
                    for (int x = x0; x < x1 && x < w; x++) {
                        sum += px[base + x];
                        n++;
                    }
                }
                out[gy * MOTION_GRID_W + gx] = n == 0 ? 0 : (double) sum / n;
            }
        }
        return out;
    }
}
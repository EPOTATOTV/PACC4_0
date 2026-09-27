package com.potatotv.paccclient.detection.analysis;

import com.potatotv.paccclient.detection.samples.Point2D;

import java.util.List;

/**
 * 鼠标轨迹分析（文档 §3.1.2）：人类轨迹是平滑曲线，自瞄/微自瞄表现为直线瞬移或强目标吸引。
 *
 * <p>对轨迹做三次贝塞尔最小二乘拟合（弦长参数化 + 正规方程闭式解），输出拟合 RMSE、
 * 目标吸引余弦、瞬移占比、平均曲率与平滑度。</p>
 */
public final class TrajectoryAnalyzer {

    /** 相邻点位移超过该像素值即视为「瞬移」。 */
    public static final double SNAP_PIXELS = 5.0;

    /**
     * 轨迹分析结果。
     *
     * @param rmse        轨迹与三次贝塞尔曲线的 RMSE（像素）
     * @param attraction  轨迹方向与目标方向的余弦相似度（[-1,1]，无目标为 0）
     * @param snapRatio   相邻点位移 &gt;5px 的步数占比
     * @param curvature   平均离散曲率（1/像素）
     * @param smoothness  平滑度 1/(1+rmse)，越大越平滑
     */
    public record TrajectoryAnalysis(double rmse, double attraction, double snapRatio,
                                     double curvature, double smoothness) {

        /** 轨迹点不足时的空结果。 */
        public static TrajectoryAnalysis empty() {
            return new TrajectoryAnalysis(0, 0, 0, 0, 1);
        }
    }

    /**
     * 分析一段鼠标轨迹。
     *
     * @param trajectory 轨迹点（时间顺序）
     * @param target     当前瞄准目标屏幕坐标，可为 {@code null}
     */
    public TrajectoryAnalysis analyze(List<Point2D> trajectory, Point2D target) {
        if (trajectory == null || trajectory.size() < 2) return TrajectoryAnalysis.empty();
        int n = trajectory.size();

        double[] t = chordLengthParams(trajectory);
        double[] ctrl = fitCubicBezier(trajectory, t);

        double se = 0;
        double curvature = 0;
        int curvCount = 0;
        for (int i = 0; i < n; i++) {
            double[] p = bezier(ctrl, t[i]);
            double dx = p[0] - trajectory.get(i).x();
            double dy = p[1] - trajectory.get(i).y();
            se += dx * dx + dy * dy;
            if (i >= 2) {
                curvature += discreteCurvature(trajectory.get(i - 2), trajectory.get(i - 1), trajectory.get(i));
                curvCount++;
            }
        }
        double rmse = Math.sqrt(se / n);
        double avgCurvature = curvCount == 0 ? 0.0 : curvature / curvCount;

        int snap = 0;
        for (int i = 1; i < n; i++) {
            if (trajectory.get(i).distanceTo(trajectory.get(i - 1)) > SNAP_PIXELS) snap++;
        }
        double snapRatio = (double) snap / (n - 1);

        double attraction = 0;
        if (target != null) {
            Point2D first = trajectory.get(0);
            Point2D last = trajectory.get(n - 1);
            attraction = Stats.cosine(last.x() - first.x(), last.y() - first.y(),
                    target.x() - first.x(), target.y() - first.y());
            if (Double.isNaN(attraction)) attraction = 0;
        }

        return new TrajectoryAnalysis(rmse, attraction, snapRatio, avgCurvature, 1.0 / (1.0 + rmse));
    }

    /** 弦长参数化：t_i = 累计弦长/总弦长，首点 0、末点 1。 */
    private static double[] chordLengthParams(List<Point2D> pts) {
        int n = pts.size();
        double[] t = new double[n];
        double total = 0;
        for (int i = 1; i < n; i++) total += pts.get(i).distanceTo(pts.get(i - 1));
        if (total < 1e-9) {
            for (int i = 0; i < n; i++) t[i] = n == 1 ? 0 : (double) i / (n - 1);
            return t;
        }
        double acc = 0;
        for (int i = 1; i < n; i++) {
            acc += pts.get(i).distanceTo(pts.get(i - 1));
            t[i] = acc / total;
        }
        return t;
    }

    /**
     * 三次贝塞尔最小二乘拟合：端点固定为轨迹首末点，未知量 P1/P2 由正规方程闭式求解。
     * B(t) = (1-t)³P0 + 3(1-t)²t·P1 + 3(1-t)t²·P2 + t³P3。
     *
     * @return 控制点数组 {x0,y0,x1,y1,x2,y2,x3,y3}
     */
    private static double[] fitCubicBezier(List<Point2D> pts, double[] t) {
        int n = pts.size();
        Point2D p0 = pts.get(0);
        Point2D p3 = pts.get(n - 1);

        double saa = 0, sab = 0, sbb = 0, sarx = 0, sary = 0, sbrx = 0, sbry = 0;
        for (int i = 0; i < n; i++) {
            double ti = t[i];
            double omt = 1 - ti;
            double a = 3 * omt * omt * ti;
            double b = 3 * omt * ti * ti;
            double baseX = omt * omt * omt * p0.x() + ti * ti * ti * p3.x();
            double baseY = omt * omt * omt * p0.y() + ti * ti * ti * p3.y();
            double rx = pts.get(i).x() - baseX;
            double ry = pts.get(i).y() - baseY;
            saa += a * a;
            sab += a * b;
            sbb += b * b;
            sarx += a * rx;
            sary += a * ry;
            sbrx += b * rx;
            sbry += b * ry;
        }
        double det = saa * sbb - sab * sab;
        double x1, y1, x2, y2;
        if (Math.abs(det) < 1e-12) {
            // 退化（共线/重复点）→ 回退为均匀三等分直线控制点
            x1 = p0.x() + (p3.x() - p0.x()) / 3.0;
            y1 = p0.y() + (p3.y() - p0.y()) / 3.0;
            x2 = p0.x() + 2.0 * (p3.x() - p0.x()) / 3.0;
            y2 = p0.y() + 2.0 * (p3.y() - p0.y()) / 3.0;
        } else {
            x1 = (sbb * sarx - sab * sbrx) / det;
            y1 = (sbb * sary - sab * sbry) / det;
            x2 = (saa * sbrx - sab * sarx) / det;
            y2 = (saa * sbry - sab * sary) / det;
        }
        return new double[]{p0.x(), p0.y(), x1, y1, x2, y2, p3.x(), p3.y()};
    }

    private static double[] bezier(double[] c, double t) {
        double omt = 1 - t;
        double b0 = omt * omt * omt;
        double b1 = 3 * omt * omt * t;
        double b2 = 3 * omt * t * t;
        double b3 = t * t * t;
        return new double[]{
                b0 * c[0] + b1 * c[2] + b2 * c[4] + b3 * c[6],
                b0 * c[1] + b1 * c[3] + b2 * c[5] + b3 * c[7]};
    }

    /** 离散曲率 κ = 2|cross| / (|v1|·|v2|·|v1+v2|)，共线时趋于 0。 */
    private static double discreteCurvature(Point2D a, Point2D b, Point2D c) {
        double v1x = b.x() - a.x();
        double v1y = b.y() - a.y();
        double v2x = c.x() - b.x();
        double v2y = c.y() - b.y();
        double cross = Math.abs(v1x * v2y - v1y * v2x);
        double n1 = Math.hypot(v1x, v1y);
        double n2 = Math.hypot(v2x, v2y);
        double n3 = Math.hypot(v1x + v2x, v1y + v2y);
        double denom = n1 * n2 * n3;
        return denom < 1e-9 ? 0.0 : 2.0 * cross / denom;
    }
}
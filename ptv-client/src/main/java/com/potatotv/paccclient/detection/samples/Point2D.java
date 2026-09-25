package com.potatotv.paccclient.detection.samples;

/** 二维点（鼠标屏幕坐标，单位像素）。 */
public record Point2D(double x, double y) {

    public double distanceTo(Point2D o) {
        double dx = x - o.x;
        double dy = y - o.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 到原点的模长。 */
    public double norm() {
        return Math.sqrt(x * x + y * y);
    }
}
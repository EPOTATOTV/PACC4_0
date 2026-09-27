package com.potatotv.paccclient.detection.samples;

/** 三维向量（方块坐标 / 速度，单位分别为格与格每秒）。 */
public record Vec3(double x, double y, double z) {

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    /** 三维欧氏距离。 */
    public double distanceTo(Vec3 o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 水平（XZ 平面）距离。 */
    public double horizontalDistanceTo(Vec3 o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 水平模长。 */
    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    /** 三维模长。 */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
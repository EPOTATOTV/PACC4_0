package com.potatotv.paccclient.redscreen;

/**
 * 屏幕输入防护（红屏时强制键盘/鼠标输入暂停）。
 * <p>说明：演示提供接口与阻塞提示；Windows 真机通过内核钩子（platform/kernel）
 * 在红屏期间拦截输入，Java 端仅展示状态。</p>
 */
public final class KeyboardLock {

    private static volatile boolean locked = false;

    public static boolean isLocked() {
        return locked;
    }

    public static void lock() {
        locked = true;
        System.out.println("[PTV-Client] 输入已暂停（红屏期间禁用键盘/鼠标，管理员可远程查端）");
    }

    public static void unlock() {
        locked = false;
        System.out.println("[PTV-Client] 输入已恢复");
    }
}
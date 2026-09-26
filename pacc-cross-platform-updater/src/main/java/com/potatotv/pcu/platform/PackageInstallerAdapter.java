package com.potatotv.pcu.platform;

import com.potatotv.pcu.PlatformAdapter;

import java.nio.file.Path;

/**
 * 「制品必须交给系统包安装器」的平台（Android APK / HarmonyOS HAP）。
 *
 * <p>这类平台上更新不是「替换安装目录里的文件」，而是把整包交给系统安装器，
 * 由系统决定替换与重启时机——Android 上的应用也改不了自己的 APK 文件。
 * PCU 核心通过 {@code instanceof} 识别这个能力，把安装动作转交出去，
 * 不做无意义的文件替换与回滚备份。</p>
 */
public interface PackageInstallerAdapter extends PlatformAdapter {

    /** 把已校验过的制品交给系统包安装器。 */
    void installPackage(Path artifact);
}
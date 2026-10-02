package com.potatotv.pob;

/**
 * 注入到产物里的运行时类的固定名字与占位串。
 *
 * <p>这两个类不参与 Renamer 的短名分配（有固定名），但占用的简单名会被预留，
 * 避免短名池恰好分配出同名类。</p>
 */
final class VaultNames {

    /** 运行时类的源码包（POB 自己编译进 jar 的位置）。 */
    static final String RUNTIME_PACKAGE = "com/potatotv/pob/runtime";

    static final String STRING_VAULT_RESOURCE = "StringVault.class";
    static final String STRING_VAULT_SIMPLE = "PobVault";
    static final String GET_NAME = "get";
    static final String GET_DESCRIPTOR = "(I)Ljava/lang/String;";
    static final String STRING_VAULT_PLACEHOLDER = "POBSTRINGVAULTBLOB";

    static final String INTEGRITY_GUARD_RESOURCE = "IntegrityGuard.class";
    static final String INTEGRITY_GUARD_SIMPLE = "PobGuard";
    static final String CHECK_NAME = "check";
    static final String CHECK_DESCRIPTOR = "()V";
    static final String INTEGRITY_GUARD_PLACEHOLDER = "POBINTEGRITYGUARDBLOB";

    /** BLOB 作为单个 UTF-8 常量，受 u2 长度上限 65535 约束，留出余量。 */
    static final int BLOB_LIMIT = 60000;

    private VaultNames() {
    }
}
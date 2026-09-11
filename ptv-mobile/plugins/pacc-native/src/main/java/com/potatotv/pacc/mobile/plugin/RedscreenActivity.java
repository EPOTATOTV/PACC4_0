package com.potatotv.pacc.mobile.plugin;

import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Color;

/**
 * 全屏红屏警告页（移动端）。
 * 对应设计文档 §6.2.1：遮罩后仍可被部分 ROM 拦截退出，移动端红屏以强警示 + 记录为准，
 * 实际游戏内限制依赖游戏服务器侧配合（PACC 不获取游戏服务器数据）。
 */
public final class RedscreenActivity extends AppCompatActivity {

    private static RedscreenActivity active;

    /** 供插件 dismiss 关闭当前红屏页。 */
    public static void finishActive() {
        RedscreenActivity a = active;
        if (a != null) a.finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        active = this;
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        int level = getIntent().getIntExtra("level", 2);
        String reason = getIntent().getStringExtra("reason");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFFD32F2F);

        TextView title = new TextView(this);
        title.setText("PACC 安全警告");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);

        TextView body = new TextView(this);
        body.setText("风险等级 L" + level + (reason != null && !reason.isEmpty() ? "\n" + reason : "\n检测到作弊行为，已暂停活动"));
        body.setTextColor(Color.WHITE);
        body.setTextSize(16);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 24, 0, 0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(32, 0, 32, 0);
        body.setLayoutParams(lp);

        root.addView(title);
        root.addView(body);
        setContentView(root);
    }

    /** 阻断返回键：用户无法自行退出红屏页（交由管理员远程解除）。 */
    @Override
    public void onBackPressed() {
        // 有意留空：仅桌面端/管理员可解除。
    }
}
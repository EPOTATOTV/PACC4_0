package asia.potatotv.pacc.mobile;

import com.getcapacitor.BridgeActivity;
import com.potatotv.pacc.mobile.plugin.PaccAccessibilityMonitorPlugin;
import com.potatotv.pacc.mobile.plugin.PaccAppScannerPlugin;
import com.potatotv.pacc.mobile.plugin.PaccAutoStartPlugin;
import com.potatotv.pacc.mobile.plugin.PaccBiometricPlugin;
import com.potatotv.pacc.mobile.plugin.PaccDetectionPlugin;
import com.potatotv.pacc.mobile.plugin.PaccNetworkPlugin;
import com.potatotv.pacc.mobile.plugin.PaccPushPlugin;
import com.potatotv.pacc.mobile.plugin.PaccRedScreenPlugin;
import com.potatotv.pacc.mobile.plugin.PaccUsbMonitorPlugin;
import com.potatotv.pacc.mobile.plugin.PaccUsageStatsPlugin;

/**
 * PACC 移动端入口。
 * 本地原生插件（pacc-native 模块）不在 npm 包内，无法被 `cap sync`
 * 自动写入 capacitor.plugins.json，必须在 BridgeActivity 中手动注册。
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(PaccDetectionPlugin.class);
        registerPlugin(PaccRedScreenPlugin.class);
        registerPlugin(PaccBiometricPlugin.class);
        registerPlugin(PaccPushPlugin.class);
        registerPlugin(PaccAccessibilityMonitorPlugin.class);
        registerPlugin(PaccUsageStatsPlugin.class);
        registerPlugin(PaccAppScannerPlugin.class);
        registerPlugin(PaccUsbMonitorPlugin.class);
        registerPlugin(PaccNetworkPlugin.class);
        registerPlugin(PaccAutoStartPlugin.class);
        super.onCreate(savedInstanceState);
    }
}

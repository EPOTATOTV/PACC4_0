package com.potatotv.paccclient.detection.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.InputSource;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.detection.samples.ActionSample;
import com.potatotv.paccclient.detection.samples.AttackSample;
import com.potatotv.paccclient.detection.samples.BlockActionSample;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.samples.Point2D;
import com.potatotv.paccclient.detection.samples.PositionSample;
import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.MemoryScanResult;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.NetworkSnapshot;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.PathHit;
import com.potatotv.paccclient.probe.PathPattern;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.RegistryHit;
import com.potatotv.paccclient.probe.RegistryPattern;
import com.potatotv.paccclient.probe.ServiceSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.probe.UsbDevice;
import com.potatotv.paccclient.probe.WindowInfo;
import com.potatotv.paccclient.spi.DetectContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link InputTimingScanner} 测试：自建 InputSource / SystemProbe 桩，不碰真实系统与目录。 */
class InputTimingScannerTest {

    @Test
    void 开关关闭时空返回() {
        PerfToggles.set(PerfToggles.VISION, false);
        try {
            DetectContext ctx = context(fixedClicks());
            InputTimingScanner scanner = new InputTimingScanner(new MacroFileTracer(List.of()));

            assertTrue(scanner.detect(ctx).isEmpty());
        } finally {
            PerfToggles.set(PerfToggles.VISION, false);
        }
    }

    @Test
    void 开启且喂入固定间隔点击时写入特征() {
        PerfToggles.set(PerfToggles.VISION, true);
        try {
            DetectContext ctx = context(fixedClicks());
            InputTimingScanner scanner = new InputTimingScanner(new MacroFileTracer(List.of()));

            scanner.detect(ctx);

            assertEquals(1.0, ctx.features().get("ext_input_fixed_interval"), 1e-9);
            assertTrue(ctx.features().get("ext_input_timing_score") > 0,
                    "固定间隔应得正分，实际=" + ctx.features().get("ext_input_timing_score"));
        } finally {
            PerfToggles.set(PerfToggles.VISION, false);
        }
    }

    private static DetectContext context(List<InputEvent> events) {
        return new DetectContext(new MinimalProbe(), new FeatureVector(), new FakeInputSource(events));
    }

    private static List<InputEvent> fixedClicks() {
        List<InputEvent> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            events.add(InputEvent.click(1_000L + i * 100L));
        }
        return events;
    }

    /** 最小假数据源：只提供输入事件，其余为空。 */
    private static final class FakeInputSource implements InputSource {
        private final List<InputEvent> events;

        FakeInputSource(List<InputEvent> events) {
            this.events = events;
        }

        @Override
        public List<InputEvent> inputEvents() {
            return events;
        }

        @Override
        public List<Point2D> mouseTrajectory() {
            return List.of();
        }

        @Override
        public List<PositionSample> positionSamples() {
            return List.of();
        }

        @Override
        public List<AttackSample> attackSamples() {
            return List.of();
        }

        @Override
        public List<BlockActionSample> blockActions() {
            return List.of();
        }

        @Override
        public List<ActionSample> actionSamples() {
            return List.of();
        }
    }

    /** 最小探针：所有能力不支持（USB=false），快照为空。 */
    private static final class MinimalProbe implements SystemProbe {

        @Override
        public boolean isSupported(Capability capability) {
            return false;
        }

        @Override
        public OsInfo osInfo() {
            return new OsInfo("Test", "1.0", "amd64");
        }

        @Override
        public ProcessSnapshot snapshotProcesses() {
            return ProcessSnapshot.empty();
        }

        @Override
        public ProcessSnapshot.ProcessInfo currentProcess() {
            return new ProcessSnapshot.ProcessInfo(0, "", null, null);
        }

        @Override
        public ModuleSnapshot snapshotModules(String processName) {
            return ModuleSnapshot.empty(processName);
        }

        @Override
        public MemoryScanResult scanMemory(String processName, byte[] pattern, byte[] mask) {
            return MemoryScanResult.unsupported(null, "test");
        }

        @Override
        public DriverSnapshot snapshotDrivers() {
            return DriverSnapshot.empty();
        }

        @Override
        public ServiceSnapshot snapshotServices() {
            return ServiceSnapshot.empty();
        }

        @Override
        public List<PathHit> scanPaths(List<PathPattern> patterns, List<Path> roots) {
            return List.of();
        }

        @Override
        public List<RegistryHit> scanRegistry(List<RegistryPattern> patterns) {
            return List.of();
        }

        @Override
        public NetworkSnapshot snapshotNetwork() {
            return NetworkSnapshot.empty();
        }

        @Override
        public List<UsbDevice> enumerateUsb() {
            return List.of();
        }

        @Override
        public List<WindowInfo> enumerateWindows() {
            return List.of();
        }
    }
}
package dev.chaya.api.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ResourceMeasurements: worker-reported figures are stored only when they are possible; nothing is clamped or filled in. */
class ResourceMeasurementsTest {

    static Map<String, Object> gpuReport(double gpuSeconds) {
        Map<String, Object> r = new HashMap<>();
        r.put("schema", 1);
        r.put("resourceClass", "gpu-a10g");
        r.put("host", Map.of("cpuCount", 8, "platform", "Linux x86_64"));
        r.put("cpu", Map.of("processSeconds", 12.5, "childrenSeconds", 30.0, "childrenMeasured", true, "method", "os.times"));
        r.put("gpu", Map.of("seconds", gpuSeconds, "basis", "STAGE_WALL_TIME_WITH_CUDA_ALLOCATIONS",
            "devices", List.of(Map.of("index", 0, "name", "NVIDIA A10G", "totalMemoryBytes", 24L << 30, "peakAllocatedBytes", 9_000_000_000L))));
        return r;
    }

    @Test
    void aCompleteReportIsStoredAsMeasured() {
        ResourceMeasurements m = ResourceMeasurements.fromWorker(gpuReport(60.0), 60.0);
        assertThat(m.resourceClass()).isEqualTo("gpu-a10g");
        assertThat(m.cpuSeconds()).isEqualTo(42.5);
        assertThat(m.cpuBasis()).isEqualTo("WORKER_PROCESS_AND_CHILDREN");
        assertThat(m.gpuSeconds()).isEqualTo(60.0);
        assertThat(m.gpuModel()).isEqualTo("NVIDIA A10G");
        assertThat(m.gpuPeakMemoryBytes()).isEqualTo(9_000_000_000L);
        assertThat(m.gpuUnmeasuredReason()).isNull();
        assertThat((List<?>) m.stored().get("rejected")).isEmpty();
    }

    @Test
    void anOldWorkerWithoutMeasurementsLeavesEverythingButTimeUnknown() {
        ResourceMeasurements m = ResourceMeasurements.fromWorker(null, 30);
        assertThat(m.resourceClass()).isNull();
        assertThat(m.cpuSeconds()).isNull();
        assertThat(m.gpuSeconds()).isNull();
        assertThat(m.gpuUnmeasuredReason()).isEqualTo(ResourceMeasurements.NOT_REPORTED);
    }

    @Test
    void missingGpuMetricsAreUnknownWithTheWorkersReasonNeverZero() {
        Map<String, Object> r = gpuReport(1);
        r.put("gpu", null);
        r.put("gpuUnmeasuredReason", "torch is not loaded in this worker process");
        ResourceMeasurements m = ResourceMeasurements.fromWorker(r, 10);
        assertThat(m.gpuSeconds()).isNull();
        assertThat(m.gpuModel()).isNull();
        assertThat(m.gpuUnmeasuredReason()).isEqualTo("torch is not loaded in this worker process");
        assertThat(m.cpuSeconds()).isEqualTo(42.5);
    }

    @Test
    void negativeNonFiniteAndImpossibleValuesAreRejectedByName() {
        Map<String, Object> r = gpuReport(500.0); // GPU held longer than the attempt lasted
        r.put("cpu", Map.of("processSeconds", -3.0, "childrenMeasured", false));
        r.put("resourceClass", "gpu a10g; DROP TABLE");
        ResourceMeasurements m = ResourceMeasurements.fromWorker(r, 60.0);
        assertThat(m.gpuSeconds()).isNull();
        assertThat(m.gpuUnmeasuredReason()).contains("invalid");
        assertThat(m.cpuSeconds()).isNull();
        assertThat(m.resourceClass()).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rejected = (List<Map<String, Object>>) m.stored().get("rejected");
        assertThat(rejected).extracting(x -> x.get("field")).contains("gpu.seconds", "cpu.processSeconds", "resourceClass");

        Map<String, Object> nan = gpuReport(1);
        nan.put("cpu", Map.of("processSeconds", Double.NaN, "childrenMeasured", false));
        assertThat(ResourceMeasurements.fromWorker(nan, 10).cpuSeconds()).isNull();
    }

    @Test
    void cpuTimeAboveWallTimeTimesTheHostsCpusIsImpossible() {
        Map<String, Object> r = gpuReport(1);
        r.put("cpu", Map.of("processSeconds", 100.0, "childrenSeconds", 0.0, "childrenMeasured", true)); // 8 CPUs x 10 s = 80 s
        ResourceMeasurements m = ResourceMeasurements.fromWorker(r, 10);
        assertThat(m.cpuSeconds()).isNull();
    }

    @Test
    void cpuWithoutChildTimesSaysSo() {
        Map<String, Object> r = gpuReport(1);
        r.put("cpu", Map.of("processSeconds", 4.0, "childrenMeasured", false));
        ResourceMeasurements m = ResourceMeasurements.fromWorker(r, 10);
        assertThat(m.cpuSeconds()).isEqualTo(4.0);
        assertThat(m.cpuBasis()).isEqualTo("WORKER_PROCESS_ONLY");
    }

    @Test
    void anAttemptTheControlPlaneEndedHasNoWorkerFigures() {
        ResourceMeasurements m = ResourceMeasurements.controlPlane("WORKER_LOST");
        assertThat(m.cpuSeconds()).isNull();
        assertThat(m.gpuSeconds()).isNull();
        assertThat(m.gpuUnmeasuredReason()).contains("without a worker report");
        assertThat(m.stored()).containsEntry("source", "CONTROL_PLANE");
    }
}

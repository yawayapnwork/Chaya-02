package dev.chaya.api.cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a worker reported about an attempt's resources (the stage report's `resources`, chaya_worker.resources), checked
 * before anything is stored. Pure: unit-tested in ResourceMeasurementsTest.
 *
 * <p>A value that is not a finite, non-negative number, or that is physically impossible for the attempt (CPU time above
 * wall time times the host's CPU count, GPU time above wall time), is dropped and listed under `rejected` with the reason.
 * The stage report itself is still applied: a bad measurement must not fail a stage that succeeded. Nothing is clamped
 * or filled in, so a dropped value is unknown, never zero.
 */
public record ResourceMeasurements(String resourceClass, Double cpuSeconds, String cpuBasis, Double gpuSeconds, String gpuModel,
                                   Long gpuPeakMemoryBytes, String gpuUnmeasuredReason, Map<String, Object> stored) {

    static final int SCHEMA = 1;
    static final double MAX_SECONDS = 1e8; // about three years; anything larger is a unit or clock error
    static final long MAX_GPU_MEMORY_BYTES = 1L << 46; // 64 TiB per device
    /** CPU time may exceed wall time only by this factor of the host's CPU count (scheduler accounting granularity). */
    static final double CPU_SLACK = 1.05;
    static final double GPU_SLACK_SECONDS = 1.0;

    public static final String NOT_REPORTED = "the worker reported no resource measurements (a worker older than resource "
        + "tracking, or one that could not measure)";

    /** For an attempt the control plane ended itself: the worker sent nothing, so only the time is known. */
    public static ResourceMeasurements controlPlane(String why) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("schema", SCHEMA);
        stored.put("source", "CONTROL_PLANE");
        stored.put("note", why);
        return new ResourceMeasurements(null, null, null, null, null, null,
            "the attempt ended without a worker report (" + why + "); nothing about its GPU use is known", stored);
    }

    /** Checks `resources` (may be null) for an attempt of `wallSeconds`. */
    @SuppressWarnings("unchecked")
    public static ResourceMeasurements fromWorker(Map<String, Object> resources, double wallSeconds) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("schema", SCHEMA);
        stored.put("source", "WORKER_REPORT");
        List<Map<String, Object>> rejected = new ArrayList<>();
        stored.put("rejected", rejected);
        if (resources == null || resources.isEmpty()) {
            stored.put("note", NOT_REPORTED);
            return new ResourceMeasurements(null, null, null, null, null, null, NOT_REPORTED, stored);
        }
        stored.put("reportedSchema", resources.get("schema"));

        String resourceClass = null;
        Object rc = resources.get("resourceClass");
        if (rc instanceof String s && RateCard.CLASS.matcher(s).matches()) {
            resourceClass = s;
        } else if (rc != null) {
            reject(rejected, "resourceClass", rc, "must be 1-100 of A-Z a-z 0-9 . _ : -");
        }
        stored.put("resourceClass", resourceClass);

        Map<String, Object> host = resources.get("host") instanceof Map<?, ?> h ? (Map<String, Object>) h : Map.of();
        Integer cpuCount = null;
        Object cc = host.get("cpuCount");
        if (cc instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.intValue() >= 1 && n.intValue() <= 4096) {
            cpuCount = n.intValue();
        } else if (cc != null) {
            reject(rejected, "host.cpuCount", cc, "must be an integer from 1 to 4096");
        }
        Map<String, Object> hostStored = new LinkedHashMap<>();
        hostStored.put("cpuCount", cpuCount);
        hostStored.put("platform", text(host.get("platform"), 100));
        stored.put("host", hostStored);

        // CPU: the worker process plus, where the platform reports them, the external tools it ran (children)
        Double cpu = null;
        String cpuBasis = null;
        Map<String, Object> cpuIn = resources.get("cpu") instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
        if (cpuIn != null) {
            double bound = cpuCount == null ? MAX_SECONDS : wallSeconds * cpuCount * CPU_SLACK + 1;
            Double process = seconds(rejected, "cpu.processSeconds", cpuIn.get("processSeconds"), bound);
            Double children = cpuIn.get("childrenSeconds") == null ? null
                : seconds(rejected, "cpu.childrenSeconds", cpuIn.get("childrenSeconds"), bound);
            boolean childrenMeasured = Boolean.TRUE.equals(cpuIn.get("childrenMeasured"));
            if (process != null && (children != null || !childrenMeasured)) {
                double total = process + (children == null ? 0 : children);
                if (total <= bound) {
                    cpu = total;
                    cpuBasis = children != null ? "WORKER_PROCESS_AND_CHILDREN" : "WORKER_PROCESS_ONLY";
                } else {
                    reject(rejected, "cpu", total, "exceeds wall time x CPU count");
                }
            }
            Map<String, Object> cpuStored = new LinkedHashMap<>();
            cpuStored.put("processSeconds", process);
            cpuStored.put("childrenSeconds", children);
            cpuStored.put("childrenMeasured", childrenMeasured && children != null);
            cpuStored.put("method", text(cpuIn.get("method"), 100));
            stored.put("cpu", cpuStored);
        }

        // GPU: only what the worker observed
        Double gpu = null;
        String model = null;
        Long peak = null;
        String unmeasured;
        Object gpuIn = resources.get("gpu");
        if (gpuIn instanceof Map<?, ?> g) {
            Map<String, Object> gm = (Map<String, Object>) g;
            gpu = seconds(rejected, "gpu.seconds", gm.get("seconds"), wallSeconds + GPU_SLACK_SECONDS);
            Set<String> names = new LinkedHashSet<>();
            List<Map<String, Object>> devices = new ArrayList<>();
            if (gm.get("devices") instanceof List<?> l) {
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?> d) || devices.size() >= 16) {
                        continue;
                    }
                    String name = text(d.get("name"), 120);
                    Long p = bytes(rejected, "gpu.devices.peakAllocatedBytes", d.get("peakAllocatedBytes"));
                    Long total = bytes(rejected, "gpu.devices.totalMemoryBytes", d.get("totalMemoryBytes"));
                    if (name != null) {
                        names.add(name);
                    }
                    if (p != null) {
                        peak = peak == null ? p : Math.max(peak, p);
                    }
                    Map<String, Object> dev = new LinkedHashMap<>();
                    dev.put("index", d.get("index") instanceof Number n ? n.intValue() : null);
                    dev.put("name", name);
                    dev.put("totalMemoryBytes", total);
                    dev.put("peakAllocatedBytes", p);
                    devices.add(dev);
                }
            }
            model = names.isEmpty() ? null : text(String.join(", ", names), 200);
            Map<String, Object> gpuStored = new LinkedHashMap<>();
            gpuStored.put("seconds", gpu);
            gpuStored.put("basis", text(gm.get("basis"), 100));
            gpuStored.put("peakMemory", text(gm.get("peakMemory"), 200));
            gpuStored.put("devices", devices);
            stored.put("gpu", gpuStored);
            unmeasured = gpu == null ? "the reported GPU time was invalid and was not recorded" : null;
        } else {
            String why = text(resources.get("gpuUnmeasuredReason"), 500);
            unmeasured = why != null ? why : "the worker reported no GPU measurement";
            stored.put("gpu", null);
        }
        stored.put("gpuUnmeasuredReason", unmeasured);
        return new ResourceMeasurements(resourceClass, cpu, cpuBasis, gpu, model, peak, unmeasured, stored);
    }

    private static Double seconds(List<Map<String, Object>> rejected, String field, Object v, double bound) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0) {
            reject(rejected, field, v, "must be a finite number of seconds, not negative");
            return null;
        }
        double d = n.doubleValue();
        if (d > Math.min(bound, MAX_SECONDS)) {
            reject(rejected, field, v, "exceeds what the attempt's wall time allows (" + Math.min(bound, MAX_SECONDS) + " s)");
            return null;
        }
        return d;
    }

    private static Long bytes(List<Map<String, Object>> rejected, String field, Object v) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() > MAX_GPU_MEMORY_BYTES) {
            reject(rejected, field, v, "must be a whole number of bytes from 0 to 2^46");
            return null;
        }
        return n.longValue();
    }

    private static String text(Object v, int max) {
        if (!(v instanceof String s) || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static void reject(List<Map<String, Object>> rejected, String field, Object value, String why) {
        String shown = String.valueOf(value);
        rejected.add(Map.of("field", field, "value", shown.length() > 80 ? shown.substring(0, 80) : shown, "reason", why));
    }
}

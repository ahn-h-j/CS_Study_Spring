package com.cos.cs_study_spring.latency;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P99 지연시간 측정기
 * - 측정값을 기록하고 P50, P99, P999 등 백분위수 계산
 */
@Slf4j
public class LatencyRecorder {

    private final List<Long> latencies = new CopyOnWriteArrayList<>();
    private final String name;

    public LatencyRecorder(String name) {
        this.name = name;
    }

    /**
     * 지연시간 기록 (ms)
     */
    public void record(long latencyMs) {
        latencies.add(latencyMs);
    }

    /**
     * 백분위수 계산
     * @param percentile 0~100 사이 값 (예: 99 = P99)
     */
    public double getPercentile(double percentile) {
        if (latencies.isEmpty()) {
            return 0;
        }

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    public double getP50() {
        return getPercentile(50);
    }

    public double getP99() {
        return getPercentile(99);
    }

    public double getP999() {
        return getPercentile(99.9);
    }

    public double getMin() {
        return latencies.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    public double getMax() {
        return latencies.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    public double getAvg() {
        return latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    public int getCount() {
        return latencies.size();
    }

    public void printStats() {
        log.info("============================================================");
        log.info("[{}] Latency Statistics", name);
        log.info("============================================================");
        log.info("Total Requests: {}", getCount());
        log.info("Min:  {} ms", getMin());
        log.info("Max:  {} ms", getMax());
        log.info("Avg:  {:.2f} ms", getAvg());
        log.info("P50:  {} ms", getP50());
        log.info("P99:  {} ms", getP99());
        log.info("P999: {} ms", getP999());
        log.info("============================================================");
    }

    public void reset() {
        latencies.clear();
    }
}

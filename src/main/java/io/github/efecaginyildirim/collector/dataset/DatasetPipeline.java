package io.github.efecaginyildirim.collector.dataset;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.persistence.DatasetQuery;
import io.github.efecaginyildirim.collector.persistence.DatasetRow;
import io.github.efecaginyildirim.collector.persistence.MotorDataReader;
import io.github.efecaginyildirim.collector.persistence.SampleExclusion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Produces a dataset by streaming rows through the steps of a {@link DatasetSpec}.
 *
 * <p>Rows are read from the database in monitor and time order and pass through the steps one by
 * one; at no point is the whole selection held in memory, so exports of any size are possible.
 * Each step counts what it removed, so the user can see the effect of every filter.
 */
public final class DatasetPipeline {

    /**
     * How many rows each step let through or removed.
     *
     * @param read                rows read from the database
     * @param excludedManually    removed by manual exclusions
     * @param excludedByRules     removed by exclusion rules
     * @param afterSampling       rows after hourly / daily averaging
     * @param droppedIncomplete   removed for a missing value
     * @param droppedUnchanged    removed by the change filter
     * @param written             rows in the dataset
     */
    public record Stats(long read, long excludedManually, long excludedByRules, long afterSampling,
                        long droppedIncomplete, long droppedUnchanged, long written) { }

    private DatasetPipeline() { }

    /**
     * Runs a dataset.
     *
     * @param reader     source of rows
     * @param spec       what to produce
     * @param exclusions manual exclusions of the selected monitors (ignored unless the spec applies them)
     * @param sink       receives each output row; values follow {@link DatasetSpec#features()}
     * @return step counts
     */
    public static Stats run(MotorDataReader reader, DatasetSpec spec, List<SampleExclusion> exclusions,
                            Consumer<DatasetRow> sink) {
        Run run = new Run(spec, spec.applyManualExclusions() ? List.copyOf(exclusions) : List.of(), sink);
        reader.query(new DatasetQuery(spec.monitorIds(), spec.window(), spec.columnsToRead(), OptionalInt.empty()), run::accept);
        run.finish();
        return run.stats();
    }

    /** The mutable state of one execution; rows arrive ordered by monitor, then time. */
    private static final class Run {
        private final DatasetSpec spec;
        private final List<SampleExclusion> exclusions;
        private final Consumer<DatasetRow> sink;
        private final int exported;
        private final int[] ruleColumns;

        private long read;
        private long excludedManually;
        private long excludedByRules;
        private long afterSampling;
        private long droppedIncomplete;
        private long droppedUnchanged;
        private long written;

        // sampling state
        private Integer bucketMonitor;
        private Instant bucketStart;
        private double[] sums;
        private int[] counts;

        // change-filter state
        private Integer lastMonitor;
        private List<Double> lastKept;
        private DatasetRow pendingLast;

        Run(DatasetSpec spec, List<SampleExclusion> exclusions, Consumer<DatasetRow> sink) {
            this.spec = spec;
            this.exclusions = exclusions;
            this.sink = sink;
            this.exported = spec.features().size();
            List<ComputedFeature> columns = spec.columnsToRead();
            this.ruleColumns = spec.rules().stream().mapToInt(r -> columns.indexOf(r.feature())).toArray();
        }

        void accept(DatasetRow row) {
            read++;
            for (SampleExclusion e : exclusions) {
                if (e.covers(row.monitorId(), row.time())) {
                    excludedManually++;
                    return;
                }
            }
            for (int i = 0; i < ruleColumns.length; i++) {
                if (spec.rules().get(i).excludes(row.values().get(ruleColumns[i]))) {
                    excludedByRules++;
                    return;
                }
            }
            DatasetRow projected = new DatasetRow(row.monitorId(), row.time(), row.values().subList(0, exported));
            if (spec.sampling() == Sampling.RAW) {
                afterSampling++;
                completeness(projected);
            } else {
                sample(projected);
            }
        }

        private void sample(DatasetRow row) {
            Instant bucket = spec.sampling().bucket(row.time());
            if (bucketStart != null && (row.monitorId() != bucketMonitor || !bucket.equals(bucketStart))) {
                flushBucket();
            }
            if (bucketStart == null) {
                bucketMonitor = row.monitorId();
                bucketStart = bucket;
                sums = new double[exported];
                counts = new int[exported];
            }
            for (int i = 0; i < exported; i++) {
                Double v = row.values().get(i);
                if (v != null) {
                    sums[i] += v;
                    counts[i]++;
                }
            }
        }

        private void flushBucket() {
            if (bucketStart == null) {
                return;
            }
            List<Double> means = new ArrayList<>(exported);
            for (int i = 0; i < exported; i++) {
                means.add(counts[i] == 0 ? null : sums[i] / counts[i]);
            }
            DatasetRow row = new DatasetRow(bucketMonitor, bucketStart, means);
            bucketStart = null;
            afterSampling++;
            completeness(row);
        }

        private void completeness(DatasetRow row) {
            if (spec.dropIncomplete() && row.values().contains(null)) {
                droppedIncomplete++;
                return;
            }
            change(row);
        }

        private void change(DatasetRow row) {
            if (spec.changeFilter().isEmpty()) {
                emit(row);
                return;
            }
            if (lastMonitor == null || row.monitorId() != lastMonitor) {
                flushPendingLast();
                lastMonitor = row.monitorId();
                lastKept = row.values();
                emit(row);
                return;
            }
            if (spec.changeFilter().get().changed(lastKept, row.values())) {
                if (pendingLast != null) {
                    droppedUnchanged++;
                    pendingLast = null;
                }
                lastKept = row.values();
                emit(row);
            } else {
                if (pendingLast != null) {
                    droppedUnchanged++;
                }
                pendingLast = row; // kept only if it turns out to be the monitor's last row
            }
        }

        private void flushPendingLast() {
            if (pendingLast != null) {
                emit(pendingLast);
                pendingLast = null;
            }
        }

        private void emit(DatasetRow row) {
            written++;
            sink.accept(row);
        }

        void finish() {
            flushBucket();
            flushPendingLast();
        }

        Stats stats() {
            return new Stats(read, excludedManually, excludedByRules, afterSampling, droppedIncomplete, droppedUnchanged, written);
        }
    }
}

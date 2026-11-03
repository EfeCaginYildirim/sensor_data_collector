package io.github.efecaginyildirim.collector.dataset;

import io.github.efecaginyildirim.collector.model.ComputedFeature;
import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A complete, reproducible description of a dataset: which samples and columns to take and how to
 * filter and reduce them. Saved as a preset, it re-creates the same dataset later; the raw data
 * is never modified.
 *
 * <p>Steps are applied in this fixed order, per monitor:
 * <ol>
 *   <li>manual exclusions, if {@link #applyManualExclusions()};</li>
 *   <li>{@link #rules() exclusion rules} on the raw samples;</li>
 *   <li>{@link #sampling() sampling}: raw, hourly or daily means;</li>
 *   <li>{@link #dropIncomplete() dropping rows} with a missing value in an exported column;</li>
 *   <li>the {@link #changeFilter() change filter}.</li>
 * </ol>
 *
 * @param monitorIds            monitors, at least one, no duplicates
 * @param from                  first UTC day, inclusive; empty for "from the beginning"
 * @param to                    last UTC day, inclusive; empty for "until now"
 * @param features              exported columns in order, at least one, no duplicates
 * @param rules                 exclusion rules, at most 20
 * @param dropIncomplete        drop rows with a missing value in any exported column
 * @param sampling              time resolution
 * @param changeFilter          keep only changed rows, if present
 * @param applyManualExclusions skip ranges the user excluded by hand
 */
public record DatasetSpec(List<Integer> monitorIds, Optional<LocalDate> from, Optional<LocalDate> to,
                          List<ComputedFeature> features, List<ExcludeRule> rules, boolean dropIncomplete,
                          Sampling sampling, Optional<ChangeFilter> changeFilter, boolean applyManualExclusions) {

    /** Upper bound used when no end day is given. */
    static final Instant FAR_FUTURE = Instant.parse("2200-01-01T00:00:00Z");

    public DatasetSpec {
        monitorIds = List.copyOf(monitorIds);
        features = List.copyOf(features);
        rules = List.copyOf(rules);
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(sampling, "sampling");
        Objects.requireNonNull(changeFilter, "changeFilter");
        if (monitorIds.isEmpty()) {
            throw new IllegalArgumentException("select at least one monitor");
        }
        if (monitorIds.stream().distinct().count() != monitorIds.size()) {
            throw new IllegalArgumentException("a monitor is selected twice");
        }
        if (features.isEmpty()) {
            throw new IllegalArgumentException("select at least one column");
        }
        if (EnumSet.copyOf(features).size() != features.size()) {
            throw new IllegalArgumentException("a column is selected twice");
        }
        if (rules.size() > 20) {
            throw new IllegalArgumentException("at most 20 rules");
        }
        if (from.isPresent() && to.isPresent() && from.get().isAfter(to.get())) {
            throw new IllegalArgumentException("the first day is after the last day");
        }
    }

    /** @return the half-open UTC window covered by {@link #from()} and {@link #to()} */
    public TimeWindow window() {
        Instant start = from.map(d -> d.atStartOfDay(ZoneOffset.UTC).toInstant()).orElse(Instant.EPOCH);
        Instant end = to.map(d -> d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).orElse(FAR_FUTURE);
        return new TimeWindow(start, end);
    }

    /**
     * Columns that must be read: the exported ones followed by any column that only a rule uses.
     *
     * @return exported columns first, then rule-only columns
     */
    public List<ComputedFeature> columnsToRead() {
        List<ComputedFeature> read = new ArrayList<>(features);
        for (ExcludeRule r : rules) {
            if (!read.contains(r.feature())) {
                read.add(r.feature());
            }
        }
        return List.copyOf(read);
    }
}

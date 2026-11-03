package io.github.efecaginyildirim.collector.persistence;

import io.github.efecaginyildirim.collector.time.TimeWindow;

import java.time.Instant;
import java.util.List;

/** Durable storage of {@link SampleExclusion manual exclusions}. */
public interface ExclusionStore {

    /**
     * Stores an exclusion.
     *
     * @param monitorId monitor id; must already be known to the store
     * @param window    excluded range
     * @param reason    why, 1 to 500 characters
     * @param now       creation time
     * @return the stored exclusion with its id
     * @throws IllegalArgumentException if the reason is blank or too long, or the monitor is unknown
     * @throws PersistenceException     on storage errors
     */
    SampleExclusion addExclusion(int monitorId, TimeWindow window, String reason, Instant now);

    /**
     * @param id exclusion id
     * @return whether an exclusion was removed
     * @throws PersistenceException on storage errors
     */
    boolean removeExclusion(long id);

    /**
     * @param monitorIds monitors to list exclusions for; empty for all monitors
     * @return exclusions ordered by monitor and start time
     * @throws PersistenceException on storage errors
     */
    List<SampleExclusion> exclusions(List<Integer> monitorIds);
}

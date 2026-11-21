/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.git.document.store;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * The schedule of the periodic fetch of a {@link StoreWriter} on the caller's executor (GDR-STORE-9), returned by
 * {@link StoreWriter#fetchPeriodically}.
 * <p>
 * It wraps the {@link ScheduledFuture} that {@code ScheduledExecutorService.scheduleWithFixedDelay} returns (Java SE 21
 * API), so the store exposes no wildcard type, and offers the two operations a caller needs: ending the schedule and
 * telling whether it ended.
 *
 * @since 0.1
 */
public final class FetchSchedule {

    private final ScheduledFuture<?> future;

    /**
     * Wraps the future of a schedule.
     *
     * @param future the future of the schedule
     * @throws NullPointerException if {@code future} is {@code null}
     */
    FetchSchedule(ScheduledFuture<?> future) {
        this.future = Objects.requireNonNull(future, "future");
    }

    /**
     * Ends the schedule: no further fetch starts; a fetch that is running is not interrupted and completes.
     *
     * @return {@code false} when the schedule had ended already, {@code true} otherwise
     */
    public boolean cancel() {
        return future.cancel(false);
    }

    /**
     * Tells whether the schedule ended, by {@link #cancel()} or by its executor, which for example cancels it on
     * shutdown.
     *
     * @return whether no further fetch will start
     */
    public boolean isDone() {
        return future.isDone();
    }

    /**
     * Renders whether the schedule ended.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "FetchSchedule[done=" + isDone() + "]";
    }
}

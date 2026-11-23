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
 * The schedule of the periodic anchoring due check of a {@link StoreWriter} on the caller's executor (GDR-STORE-16,
 * GDR-STORE-17), returned by {@link StoreWriter#anchorPeriodically}: every {@code anchor.retry-interval} it runs one due
 * check, which creates a due checkpoint and retries every pending subject.
 * <p>
 * It wraps the {@link ScheduledFuture} that {@code ScheduledExecutorService.scheduleWithFixedDelay} returns (Java SE 21
 * API), as {@link FetchSchedule} does, and offers ending the schedule and telling whether it ended. Ending it also ends
 * the due checks the writer hands to the executor after a save, a release or a fetch.
 *
 * @since 0.1
 */
public final class AnchorSchedule {

    private final ScheduledFuture<?> future;
    private final Runnable onCancel;

    /**
     * Wraps the future of a schedule.
     *
     * @param future   the future of the schedule
     * @param onCancel what {@link #cancel()} runs besides cancelling the future: the end of the due checks after a
     *                 save, a release or a fetch
     * @throws NullPointerException if an argument is {@code null}
     */
    AnchorSchedule(ScheduledFuture<?> future, Runnable onCancel) {
        this.future = Objects.requireNonNull(future, "future");
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
    }

    /**
     * Ends the schedule: no further due check starts; a due check that is running is not interrupted and completes.
     *
     * @return {@code false} when the schedule had ended already, {@code true} otherwise
     */
    public boolean cancel() {
        onCancel.run();
        return future.cancel(false);
    }

    /**
     * Tells whether the schedule ended, by {@link #cancel()} or by its executor, which for example cancels it on
     * shutdown.
     *
     * @return whether no further periodic due check will start
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
        return "AnchorSchedule[done=" + isDone() + "]";
    }
}

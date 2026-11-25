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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The draft locks of {@link RepositoryGuard} (GDR-STORE-3): a draft lock exists only while a save holds or waits for
 * it, so the map shrinks back to empty after the saves of many distinct drafts, and a save that registered the lock of
 * a draft while another save of that draft releases it still meets the same lock as every later save, so two saves of
 * one draft never run unserialised. The question whether a thread is inside a callback (GDR-STORE-16) answers from
 * that thread's own holds and never waits. The draft reference names are generated.
 */
@EnableGeneratorController
@DisplayName("RepositoryGuard draft locks (GDR-STORE-3)")
class RepositoryGuardTest {

    private static final long TIMEOUT_SECONDS = 10;

    @TempDir
    Path temp;

    private static String draftRef() {
        return StoreReferences.DRAFTS + Generators.strings("abcdefghijklmnopqrstuvwxyz0123456789", 6, 16).next();
    }

    private RepositoryGuard guard() {
        return RepositoryGuard.of(temp.resolve(Generators.strings("abcdefghijklmnopqrstuvwxyz", 6, 12).next()));
    }

    @Test
    @DisplayName("the draft-lock map holds a lock only while a save runs and shrinks back after many distinct drafts")
    void mapShrinksBack() {
        var guard = guard();
        int drafts = Generators.integers(50, 200).next();
        var seenDuringSave = new AtomicInteger(-1);

        for (int i = 0; i < drafts; i++) {
            guard.draft(draftRef() + "-" + i, () -> {
                seenDuringSave.set(guard.draftLocks());
                return null;
            });
        }

        assertAll(
                () -> assertEquals(1, seenDuringSave.get(), "a running save holds exactly its own lock"),
                () -> assertEquals(0, guard.draftLocks(), "no lock is left after the saves of " + drafts + " drafts"));
    }

    @Test
    @DisplayName("GDR-STORE-16: a thread is inside a callback while it holds the shared side, the transport or the anchoring lock")
    void insideCallbackPerLock() {
        var guard = guard();

        boolean outside = guard.insideCallback();
        boolean shared = guard.shared(guard::insideCallback);
        boolean transport = guard.transport(guard::insideCallback);
        boolean anchoring = guard.anchoring(guard::insideCallback);
        boolean draftOnly = guard.draft(draftRef(), guard::insideCallback);
        boolean repeated = guard.shared(() -> guard.insideCallback() && guard.insideCallback());
        boolean after = guard.insideCallback();
        var exclusive = guard.exclusive(() -> StoreResult.ok(Boolean.TRUE));

        assertAll(
                () -> assertFalse(outside, "a thread holding no lock is outside every callback"),
                () -> assertTrue(shared, "the shared side of the maintenance lock"),
                () -> assertTrue(transport, "the transport lock"),
                () -> assertTrue(anchoring, "the anchoring lock"),
                () -> assertFalse(draftOnly, "a draft lock alone is taken only inside the shared side"),
                () -> assertTrue(repeated, "asking twice changes nothing"),
                () -> assertFalse(after, "no lock is left held by the question"),
                () -> assertTrue(exclusive.isOk(), "the exclusive side is free again, so no shared hold was left"));
    }

    @Test
    @DisplayName("GDR-STORE-16: the question never waits for a lock another thread holds")
    void insideCallbackNeverWaits() throws Exception {
        var guard = guard();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = new FutureTask<>(() -> guard.anchoring(() -> guard.transport(() -> guard.shared(() -> {
            held.countDown();
            try {
                return release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Boolean.FALSE;
            }
        }))));
        var thread = new Thread(holder, "guard-holder");
        thread.start();
        try {
            assertTrue(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the other thread holds every lock");
            var asked = new FutureTask<>(guard::insideCallback);
            var asking = new Thread(asked, "guard-asker");
            asking.start();

            assertFalse(asked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "a thread holding no lock is outside, whatever another thread holds, and gets its answer at once");
        } finally {
            release.countDown();
            assertTrue(holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the holder ended");
        }
    }

    @Test
    @DisplayName("a lock is removed also when the save throws")
    void removedOnFailure() {
        var guard = guard();

        try {
            guard.draft(draftRef(), () -> {
                throw new IllegalStateException("scripted");
            });
        } catch (IllegalStateException expected) {
            // the save failed; its lock must be gone
        }

        assertEquals(0, guard.draftLocks(), "the failed save left no lock");
    }

    @Test
    @DisplayName("the guard of one clone path is shared")
    void oneGuardPerClone() {
        var path = temp.resolve(Generators.strings("abcdefghijklmnopqrstuvwxyz", 6, 12).next());

        assertSame(RepositoryGuard.of(path), RepositoryGuard.of(path.resolve("x").getParent()),
                "two spellings of one path share the guard");
    }

    @Test
    @DisplayName("a save that registered the lock while another releases it never runs unserialised with a later save")
    void registrationRacingRelease() throws Exception {
        var guard = guard();
        var ref = draftRef();
        var registered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var holding = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var inside = new AtomicInteger();
        var overlapped = new AtomicBoolean();
        var laterRan = new AtomicBoolean();

        // the workers run as futures, so a failure inside one is rethrown on this thread after the joins
        var firstSave = new FutureTask<>(() -> guard.draft(ref, () -> {
            registered.countDown();
            await(resume);
        }, () -> {
            enter(inside, overlapped);
            holding.countDown();
            await(releaseHolder);
            inside.decrementAndGet();
            return null;
        }));
        var laterSave = new FutureTask<>(() -> guard.draft(ref, () -> {
            enter(inside, overlapped);
            laterRan.set(true);
            inside.decrementAndGet();
            return null;
        }));
        Thread first;
        Thread later;
        boolean ranWhileHeld;
        try {
            // A registers the lock and pauses before taking it
            first = Thread.ofPlatform().start(firstSave);
            assertTrue(registered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first save registered its lock");

            // B takes and releases the lock while A holds a registration but not the lock
            guard.draft(ref, () -> {
                enter(inside, overlapped);
                inside.decrementAndGet();
                return null;
            });
            assertEquals(1, guard.draftLocks(), "the lock A registered survives B's release");

            resume.countDown();
            assertTrue(holding.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first save holds the lock");

            // C must wait for A: it meets the same lock A holds, not a new one
            later = Thread.ofPlatform().start(laterSave);
            ThreadStates.awaitParkedOrEnded(later, TIMEOUT_SECONDS);
            ranWhileHeld = laterRan.get();
        } finally {
            // a failed assertion above leaves no worker waiting for its latch until the timeout
            resume.countDown();
            releaseHolder.countDown();
        }
        firstSave.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        laterSave.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        first.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        later.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        assertAll(
                () -> assertFalse(ranWhileHeld, "the later save waited for the save holding the lock"),
                () -> assertTrue(laterRan.get(), "the later save ran after the release"),
                () -> assertFalse(overlapped.get(), "no two saves of the draft ran at once"),
                () -> assertFalse(first.isAlive() || later.isAlive(), "both saves ended"),
                () -> assertEquals(0, guard.draftLocks(), "no lock is left"));
    }

    private static void enter(AtomicInteger inside, AtomicBoolean overlapped) {
        if (inside.incrementAndGet() > 1) {
            overlapped.set(true);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the latch opens");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

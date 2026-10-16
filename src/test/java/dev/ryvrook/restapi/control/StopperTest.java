package dev.ryvrook.restapi.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StopperTest {
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final List<Integer> heard = new CopyOnWriteArrayList<>();
    private final CountDownLatch fired = new CountDownLatch(1);
    private final List<Stopper.Plan> fires = new CopyOnWriteArrayList<>();

    private final Stopper stopper = new Stopper(scheduler, System::currentTimeMillis, new Stopper.Hooks() {
        @Override
        public void announce(Stopper.Plan plan, int secondsLeft) {
            heard.add(secondsLeft);
        }

        @Override
        public void fire(Stopper.Plan plan) {
            fires.add(plan);
            fired.countDown();
        }
    });

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void marksFallInsideTheCountdown() {
        assertEquals(List.of(4, 3, 2, 1), Stopper.marks(5));
        assertEquals(List.of(30, 10, 5, 4, 3, 2, 1), Stopper.marks(60));
        assertEquals(List.of(), Stopper.marks(1));
        assertEquals(List.of(), Stopper.marks(0));
        assertEquals(3600, Stopper.marks(7200).get(0));
    }

    @Test
    void aShortCountdownAnnouncesEverySecondThenFires() throws Exception {
        Stopper.Plan plan = stopper.schedule(2, 8, true, "Update", true, "ops", "127.0.0.1");
        assertNotNull(plan);
        assertNull(stopper.schedule(1, 0, false, null, true, "ops", "127.0.0.1"), "one stop at a time");
        assertTrue(fired.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(2, 1, 0), heard);
        assertEquals(1, fires.size());
        assertEquals(8, fires.get(0).exitCode());
        assertNull(stopper.cancel(), "too late to cancel");
    }

    @Test
    void aCancelStopsEverything() throws Exception {
        Stopper.Plan plan = stopper.schedule(1, 0, false, null, true, "ops", "127.0.0.1");
        Stopper.Plan cancelled = stopper.cancel();
        assertEquals(plan, cancelled);
        assertNull(stopper.pending());
        assertFalse(fired.await(1500, TimeUnit.MILLISECONDS));
        assertNull(stopper.cancel());
        assertNotNull(stopper.schedule(0, 0, false, null, false, "ops", "127.0.0.1"), "a new stop after a cancel");
        assertTrue(fired.await(5, TimeUnit.SECONDS));
        assertTrue(heard.stream().allMatch(s -> s == 1), "announce false says nothing more: " + heard);
    }

    @Test
    void thePlanSaysWhatIsLeft() {
        Stopper.Plan p = new Stopper.Plan("stop-1", 10_000, 10, 0, false, null, true, "ops", "c");
        assertEquals(10, p.json(0).get("secondsLeft").getAsInt());
        assertEquals(1, p.json(9_500).get("secondsLeft").getAsInt());
        assertEquals(0, p.json(20_000).get("secondsLeft").getAsInt());
    }
}

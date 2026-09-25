package com.marukitano.caminoguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class CaminoPebbleRoutePublisherTest {

    private static List<CaminoTimetableStop> stops() {
        return Arrays.asList(
                new CaminoTimetableStop("A", 0.0, 480),
                new CaminoTimetableStop("B", 1000.0, 600),
                new CaminoTimetableStop("C", 2000.0, 720)
        );
    }

    @Test
    public void selectedStopProgressRunsFromPreviousStopToSelectedStop() {
        assertEquals(
                50,
                CaminoPebbleRoutePublisher.stopProgressPercent(
                        stops(),
                        1,
                        500.0
                )
        );
    }

    @Test
    public void progressDotIsVisibleOnlyForCurrentStopScreen() {
        assertEquals(
                50,
                CaminoPebbleRoutePublisher.visibleStopProgressPercent(
                        stops(),
                        1,
                        1,
                        500.0
                )
        );

        assertEquals(
                -1,
                CaminoPebbleRoutePublisher.visibleStopProgressPercent(
                        stops(),
                        2,
                        1,
                        500.0
                )
        );

        assertEquals(
                -1,
                CaminoPebbleRoutePublisher.visibleStopProgressPercent(
                        stops(),
                        0,
                        1,
                        500.0
                )
        );
    }

    @Test
    public void futureSegmentStaysAtZeroUntilItsStart() {
        assertEquals(
                0,
                CaminoPebbleRoutePublisher.stopProgressPercent(
                        stops(),
                        2,
                        500.0
                )
        );
    }

    @Test
    public void passedSelectedStopIsClampedToOneHundred() {
        assertEquals(
                100,
                CaminoPebbleRoutePublisher.stopProgressPercent(
                        stops(),
                        1,
                        1500.0
                )
        );
    }

    @Test
    public void invalidProgressInputIsUnknown() {
        assertEquals(
                -1,
                CaminoPebbleRoutePublisher.stopProgressPercent(
                        stops(),
                        99,
                        500.0
                )
        );
    }
}

package com.marukitano.caminoguard;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure height-profile sample preparation and lookup.
 *
 * Android View state, touch-mode state, reveal animation and Canvas drawing
 * deliberately stay in CaminoHeightProfileView.
 */
final class CaminoHeightProfileModel {

    private final int maxDrawSamples;

    CaminoHeightProfileModel(
            int maxDrawSamples
    ) {
        if (maxDrawSamples < 2) {
            throw new IllegalArgumentException(
                    "maxDrawSamples must be >= 2"
            );
        }

        this.maxDrawSamples =
                maxDrawSamples;
    }

    List<CaminoHeightProfileView.Sample> reduceSamples(
            List<CaminoHeightProfileView.Sample> input
    ) {
        if (input == null
                || input.isEmpty()) {
            return new ArrayList<>();
        }

        if (input.size()
                <= maxDrawSamples) {

            return new ArrayList<>(
                    input
            );
        }

        int stride =
                (int)
                        Math.ceil(
                                input.size()
                                        / (double)
                                        maxDrawSamples
                        );

        List<CaminoHeightProfileView.Sample> reduced =
                new ArrayList<>(
                        maxDrawSamples
                                + 32
                );

        for (int index = 0;
                index < input.size();
                index++) {

            CaminoHeightProfileView.Sample sample =
                    input.get(
                            index
                    );

            if (index == 0
                    || index == input.size() - 1
                    || sample.breakBefore
                    || index % stride == 0) {

                reduced.add(
                        sample
                );
            }
        }

        return reduced;
    }

    int findNearestSample(
            List<CaminoHeightProfileView.Sample> samples,
            float touchY,
            int viewHeight
    ) {
        if (samples == null
                || samples.isEmpty()
                || viewHeight <= 0) {
            return -1;
        }

        int bestIndex =
                -1;

        float bestDistance =
                Float.POSITIVE_INFINITY;

        for (int index = 0;
                index < samples.size();
                index++) {

            CaminoHeightProfileView.Sample sample =
                    samples.get(
                            index
                    );

            float sampleY =
                    sample.screenYFraction
                            * viewHeight;

            float distance =
                    Math.abs(
                            sampleY
                                    - touchY
                    );

            if (distance
                    < bestDistance) {

                bestDistance =
                        distance;

                bestIndex =
                        index;
            }
        }

        return bestIndex;
    }
}

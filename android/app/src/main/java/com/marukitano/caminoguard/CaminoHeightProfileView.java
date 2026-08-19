package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * CAMINO_EDGE_PROJECTED_HEIGHT_PROFILE_V2
 *
 * A side-on terrain silhouette projected onto the right edge of the map.
 *
 * The controller supplies only route points that are currently visible on the
 * map. Each sample keeps the route point's current screen-Y position. Elevation
 * changes only the X offset from the right edge. The result is deliberately not
 * a conventional chart: it is a 90-degree fold-out of the visible Camino.
 */
final class CaminoHeightProfileView extends View {

    static final class Sample {
        final float screenYFraction;
        final double elevationM;
        final boolean breakBefore;

        Sample(
                float screenYFraction,
                double elevationM,
                boolean breakBefore
        ) {
            this.screenYFraction = screenYFraction;
            this.elevationM = elevationM;
            this.breakBefore = breakBefore;
        }
    }

    private static final int MAX_DRAW_SAMPLES = 1000;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Sample> samples = new ArrayList<>();

    CaminoHeightProfileView(Context context) {
        super(context);

        setClickable(false);
        setFocusable(false);

        // No boxed chart background anymore. The translucent terrain grows
        // directly out of the right edge of the map.
        fillPaint.setColor(Color.argb(76, 208, 68, 50));
        fillPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(Color.argb(235, 255, 240, 200));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.8f));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        edgePaint.setColor(Color.argb(72, 255, 240, 200));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(1.0f));
    }

    void clearProfile() {
        if (samples.isEmpty() && getVisibility() == GONE) {
            return;
        }

        samples = new ArrayList<>();
        setVisibility(GONE);
        invalidate();
    }

    void setSamples(List<Sample> input) {
        if (input == null || input.size() < 2) {
            clearProfile();
            return;
        }

        samples = reduceSamples(input);

        if (samples.size() < 2) {
            clearProfile();
            return;
        }

        setVisibility(VISIBLE);
        invalidate();
    }

    private List<Sample> reduceSamples(List<Sample> input) {
        if (input.size() <= MAX_DRAW_SAMPLES) {
            return new ArrayList<>(input);
        }

        int stride = (int) Math.ceil(
                input.size() / (double) MAX_DRAW_SAMPLES
        );

        List<Sample> reduced = new ArrayList<>(MAX_DRAW_SAMPLES + 32);

        for (int index = 0; index < input.size(); index++) {
            Sample sample = input.get(index);

            if (index == 0
                    || index == input.size() - 1
                    || sample.breakBefore
                    || index % stride == 0) {
                reduced.add(sample);
            }
        }

        return reduced;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (samples.size() < 2) {
            return;
        }

        double minElevation = Double.POSITIVE_INFINITY;
        double maxElevation = Double.NEGATIVE_INFINITY;

        for (Sample sample : samples) {
            if (!Double.isFinite(sample.elevationM)) {
                continue;
            }

            minElevation = Math.min(minElevation, sample.elevationM);
            maxElevation = Math.max(maxElevation, sample.elevationM);
        }

        if (!Double.isFinite(minElevation)
                || !Double.isFinite(maxElevation)) {
            return;
        }

        // Do not turn a nearly-flat section into an enormous fake mountain.
        // At least 30 vertical metres share the available profile width.
        double rawSpan = Math.max(0.0, maxElevation - minElevation);
        double displaySpan = Math.max(30.0, rawSpan * 1.15);
        double centreElevation = (minElevation + maxElevation) / 2.0;
        double displayMin = centreElevation - displaySpan / 2.0;
        double displayMax = centreElevation + displaySpan / 2.0;
        double elevationSpan = Math.max(1.0, displayMax - displayMin);

        float right = getWidth() - dp(2.0f);
        float left = dp(8.0f);

        canvas.drawLine(
                right,
                0.0f,
                right,
                getHeight(),
                edgePaint
        );

        Path linePath = null;
        Path fillPath = null;
        float lastY = 0.0f;
        boolean fragmentOpen = false;

        for (Sample sample : samples) {
            if (!Double.isFinite(sample.elevationM)) {
                continue;
            }

            float y = Math.max(
                    0.0f,
                    Math.min(
                            getHeight(),
                            sample.screenYFraction * getHeight()
                    )
            );

            double normalisedElevation =
                    (sample.elevationM - displayMin) / elevationSpan;

            normalisedElevation = Math.max(
                    0.0,
                    Math.min(1.0, normalisedElevation)
            );

            // Low terrain hugs the screen edge. Higher terrain grows farther
            // left into the map.
            float x = (float) (
                    right
                            - normalisedElevation
                            * (right - left)
            );

            if (!fragmentOpen || sample.breakBefore) {
                if (fragmentOpen) {
                    fillPath.lineTo(right, lastY);
                    fillPath.close();

                    canvas.drawPath(fillPath, fillPaint);
                    canvas.drawPath(linePath, linePaint);
                }

                linePath = new Path();
                fillPath = new Path();

                linePath.moveTo(x, y);

                fillPath.moveTo(right, y);
                fillPath.lineTo(x, y);

                fragmentOpen = true;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }

            lastY = y;
        }

        if (fragmentOpen) {
            fillPath.lineTo(right, lastY);
            fillPath.close();

            canvas.drawPath(fillPath, fillPaint);
            canvas.drawPath(linePath, linePaint);
        }
    }

    private float dp(float value) {
        return value
                * getResources()
                .getDisplayMetrics()
                .density;
    }
}

package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Android railway-style renderer for CaminoTimetableState.
 *
 * Walking direction is bottom -> top:
 *   first visible stop = bottom
 *   destination        = top
 *
 * Distances are deliberately NOT rendered between stops. The only distance
 * row is the platform-neutral state's distanceToNextM, which a later live phase will feed
 * from live route progress.
 */
final class CaminoTimetableView extends View {

    private static final float PANEL_WIDTH_FRACTION =
            1.0f / 3.0f;

    private static final float PANEL_TOP_GAP_MM =
            10.0f;

    private static final float PANEL_BOTTOM_GAP_MM =
            30.0f;

    private static final float CONTENT_VERTICAL_PADDING_DP =
            12.0f;

    private static final float LINE_X_DP =
            43.0f;

    private static final float NAME_LEFT_DP =
            54.0f;

    private static final float TIME_GAP_DP =
            5.0f;

    private static final float PANEL_RIGHT_PADDING_DP =
            7.0f;

    private static final float DISTANCE_ROW_HEIGHT_DP =
            46.0f;

    private final Paint panelPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint linePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint stopFillPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint stopRingPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint namePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint timePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint distancePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private CaminoTimetableState state;


    CaminoTimetableView(
            Context context
    ) {
        super(
                context
        );

        setClickable(
                false
        );

        panelPaint.setColor(
                Color.argb(
                        150,
                        24,
                        27,
                        30
                )
        );

        panelPaint.setStyle(
                Paint.Style.FILL
        );

        linePaint.setColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        linePaint.setStyle(
                Paint.Style.STROKE
        );

        linePaint.setStrokeWidth(
                dp(
                        2.0f
                )
        );

        linePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        linePaint.setPathEffect(
                new DashPathEffect(
                        new float[]{
                                dp(
                                        3.0f
                                ),
                                dp(
                                        6.0f
                                )
                        },
                        0.0f
                )
        );

        stopFillPaint.setColor(
                Color.rgb(
                        24,
                        27,
                        30
                )
        );

        stopFillPaint.setStyle(
                Paint.Style.FILL
        );

        stopRingPaint.setColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        stopRingPaint.setStyle(
                Paint.Style.STROKE
        );

        stopRingPaint.setStrokeWidth(
                dp(
                        2.0f
                )
        );

        namePaint.setColor(
                Color.rgb(
                        245,
                        245,
                        245
                )
        );

        namePaint.setTextSize(
                sp(
                        11.5f
                )
        );

        namePaint.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.SANS_SERIF,
                        android.graphics.Typeface.NORMAL
                )
        );

        timePaint.setColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        timePaint.setTextSize(
                sp(
                        10.5f
                )
        );

        timePaint.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.NORMAL
                )
        );

        timePaint.setTextAlign(
                Paint.Align.RIGHT
        );

        distancePaint.setColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        distancePaint.setTextSize(
                sp(
                        11.5f
                )
        );

        distancePaint.setTypeface(
                android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD
                )
        );
    }


    void setState(
            CaminoTimetableState state
    ) {
        this.state =
                state;

        invalidate();
    }


    void clearState() {
        state =
                null;

        invalidate();
    }


    boolean hasState() {
        return state != null
                && !state.visibleStops.isEmpty();
    }


    float panelWidthPx() {
        float available =
                getWidth() > 0
                        ? getWidth()
                        : getResources()
                        .getDisplayMetrics()
                        .widthPixels;

        return available
                * PANEL_WIDTH_FRACTION;
    }


    @Override
    protected void onDraw(
            Canvas canvas
    ) {
        super.onDraw(
                canvas
        );

        if (state == null
                || state.visibleStops.isEmpty()
                || getWidth() <= 0
                || getHeight() <= 0) {

            return;
        }

        float panelWidth =
                panelWidthPx();

        float panelTop =
                mm(
                        PANEL_TOP_GAP_MM
                );

        float panelBottom =
                getHeight()
                        - mm(
                        PANEL_BOTTOM_GAP_MM
                );

        if (panelBottom <= panelTop) {
            return;
        }

        RectF panel =
                new RectF(
                        0.0f,
                        panelTop,
                        panelWidth,
                        panelBottom
                );

        float corner =
                dp(
                        18.0f
                );

        canvas.drawRoundRect(
                panel,
                corner,
                corner,
                panelPaint
        );

        /*
         * Hide the rounding on the physical left screen edge. Only the inner
         * panel edge should visibly round.
         */
        canvas.drawRect(
                0.0f,
                panelTop,
                corner,
                panelBottom,
                panelPaint
        );

        drawTimetable(
                canvas,
                panelWidth,
                panelTop,
                panelBottom
        );
    }


    private void drawTimetable(
            Canvas canvas,
            float panelWidth,
            float panelTop,
            float panelBottom
    ) {
        List<CaminoTimetableStop> stops =
                state.visibleStops;

        int count =
                stops.size();

        if (count <= 0) {
            return;
        }

        float topY =
                panelTop
                        + dp(
                        CONTENT_VERTICAL_PADDING_DP
                );

        float bottomY =
                panelBottom
                        - dp(
                        CONTENT_VERTICAL_PADDING_DP
                );

        if (state.showDistanceToNext
                && state.hasNextStop()
                && Double.isFinite(
                state.distanceToNextM
        )) {

            bottomY -=
                    dp(
                            DISTANCE_ROW_HEIGHT_DP
                    );
        }

        if (bottomY <= topY) {
            return;
        }

        float lineX =
                dp(
                        LINE_X_DP
                );

        float span =
                bottomY
                        - topY;

        float step =
                count <= 1
                        ? 0.0f
                        : span
                        / (
                        count
                                - 1
                );

        float highestStopY =
                count <= 1
                        ? bottomY
                        : topY;

        float lowestStopY =
                bottomY;

        canvas.drawLine(
                lineX,
                highestStopY,
                lineX,
                lowestStopY,
                linePaint
        );

        for (int index =
                0;
                index < count;
                index++) {

            float y =
                    bottomY
                            - index
                            * step;

            CaminoTimetableStop stop =
                    stops.get(
                            index
                    );

            drawStop(
                    canvas,
                    stop,
                    index,
                    count,
                    lineX,
                    y,
                    panelWidth
            );
        }

        if (state.showDistanceToNext
                && state.hasNextStop()
                && Double.isFinite(
                state.distanceToNextM
        )) {

            drawDistanceToNext(
                    canvas,
                    lineX,
                    bottomY,
                    panelWidth
            );
        }
    }


    private void drawStop(
            Canvas canvas,
            CaminoTimetableStop stop,
            int index,
            int count,
            float lineX,
            float y,
            float panelWidth
    ) {
        float radius =
                dp(
                        index == 0
                                || index
                                == count - 1
                                ? 5.0f
                                : 4.2f
                );

        canvas.drawCircle(
                lineX,
                y,
                radius
                        + dp(
                        1.0f
                ),
                stopFillPaint
        );

        canvas.drawCircle(
                lineX,
                y,
                radius,
                stopRingPaint
        );

        Paint.FontMetrics nameMetrics =
                namePaint.getFontMetrics();

        float baseline =
                y
                        - (
                        nameMetrics.ascent
                                + nameMetrics.descent
                )
                        / 2.0f;

        String time =
                formatClockMinutes(
                        stop.arrivalMinutesOfDay
                );

        float timeX =
                lineX
                        - dp(
                        TIME_GAP_DP
                );

        float nameX =
                dp(
                        NAME_LEFT_DP
                );

        float maxNameWidth =
                Math.max(
                        dp(
                                28.0f
                        ),
                        panelWidth
                                - dp(
                                PANEL_RIGHT_PADDING_DP
                        )
                                - nameX
                );

        String name =
                fitText(
                        stop.name,
                        maxNameWidth
                );

        canvas.drawText(
                name,
                nameX,
                baseline,
                namePaint
        );

        canvas.drawText(
                time,
                timeX,
                baseline,
                timePaint
        );
    }


    private void drawDistanceToNext(
            Canvas canvas,
            float lineX,
            float firstStopY,
            float panelWidth
    ) {
        float currentY =
                getHeight()
                        - mm(
                        PANEL_BOTTOM_GAP_MM
                )
                        - dp(
                        CONTENT_VERTICAL_PADDING_DP
                );

        canvas.drawLine(
                lineX,
                firstStopY,
                lineX,
                currentY,
                linePaint
        );

        float markerRadius =
                dp(
                        3.2f
                );

        canvas.drawCircle(
                lineX,
                currentY,
                markerRadius,
                stopRingPaint
        );

        String text =
                "noch "
                        + formatDistance(
                        state.distanceToNextM
                );

        Paint.FontMetrics metrics =
                distancePaint.getFontMetrics();

        float baseline =
                currentY
                        - (
                        metrics.ascent
                                + metrics.descent
                )
                        / 2.0f;

        float x =
                dp(
                        NAME_LEFT_DP
                );

        float maxWidth =
                Math.max(
                        0.0f,
                        panelWidth
                                - x
                                - dp(
                                PANEL_RIGHT_PADDING_DP
                        )
                );

        canvas.drawText(
                fitDistanceText(
                        text,
                        maxWidth
                ),
                x,
                baseline,
                distancePaint
        );
    }


    private String fitText(
            String text,
            float maxWidth
    ) {
        String value =
                text == null
                        ? ""
                        : text;

        if (namePaint.measureText(
                value
        ) <= maxWidth) {

            return value;
        }

        String ellipsis =
                "…";

        float ellipsisWidth =
                namePaint.measureText(
                        ellipsis
                );

        int count =
                namePaint.breakText(
                        value,
                        true,
                        Math.max(
                                0.0f,
                                maxWidth
                                        - ellipsisWidth
                        ),
                        null
                );

        if (count <= 0) {
            return ellipsis;
        }

        return value.substring(
                0,
                Math.min(
                        count,
                        value.length()
                )
        )
                .trim()
                + ellipsis;
    }


    private String fitDistanceText(
            String text,
            float maxWidth
    ) {
        if (distancePaint.measureText(
                text
        ) <= maxWidth) {

            return text;
        }

        return formatDistance(
                state.distanceToNextM
        );
    }


    private String formatDistance(
            double distanceM
    ) {
        if (!Double.isFinite(
                distanceM
        )
                || distanceM < 0.0) {

            return "—";
        }

        if (distanceM >= 1000.0) {
            return String.format(
                    Locale.GERMANY,
                    "%.1f km",
                    distanceM
                            / 1000.0
            );
        }

        return String.format(
                Locale.GERMANY,
                "%.0f m",
                distanceM
        );
    }


    private String formatClockMinutes(
            int minutes
    ) {
        int normalized =
                minutes
                        % (
                        24
                                * 60
                );

        if (normalized < 0) {
            normalized +=
                    24
                            * 60;
        }

        return String.format(
                Locale.GERMANY,
                "%02d:%02d",
                normalized
                        / 60,
                normalized
                        % 60
        );
    }


    private float mm(
            float value
    ) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_MM,
                value,
                getResources()
                        .getDisplayMetrics()
        );
    }


    private float dp(
            float value
    ) {
        return value
                * getResources()
                .getDisplayMetrics()
                .density;
    }


    private float sp(
            float value
    ) {
        return value
                * getResources()
                .getDisplayMetrics()
                .scaledDensity;
    }
}

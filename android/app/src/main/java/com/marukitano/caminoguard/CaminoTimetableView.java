package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * Android renderer for the compact Camino timetable window.
 *
 * Walking direction is bottom -> top:
 * - bottom row     = current/passed village OR "noch X km"
 * - next villages  = directly above
 * - destination    = top
 *
 * The 15 mm gap between the second upcoming village and the destination is
 * centered on the screen so the left-edge chevron never covers an entry.
 */
final class CaminoTimetableView extends View {

    private static final float PANEL_WIDTH_FRACTION =
            1.0f / 3.0f;

    private static final float PANEL_EXTRA_WIDTH_MM =
            5.0f;

    private static final float PANEL_MAX_WIDTH_FRACTION =
            0.50f;

    private static final float PANEL_VERTICAL_PADDING_MM =
            4.0f;

    private static final float NEAR_STOP_GAP_MM =
            5.0f;

    private static final float GOAL_GAP_MM =
            15.0f;

    private static final float COLUMN_SHIFT_MM =
            5.0f;

    private static final float PANEL_RIGHT_PADDING_DP =
            8.0f;

    private static final float LINE_X_DP =
            55.0f;

    private static final float NAME_GAP_DP =
            13.0f;

    private static final float TIME_GAP_DP =
            14.0f;

    private final Paint panelPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint solidLinePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint dashedLinePaint =
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

        setWillNotDraw(
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

        solidLinePaint.setColor(
                Color.argb(
                        230,
                        232,
                        235,
                        238
                )
        );
        solidLinePaint.setStrokeWidth(
                dp(
                        2.0f
                )
        );
        solidLinePaint.setStyle(
                Paint.Style.STROKE
        );
        solidLinePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        dashedLinePaint.set(
                solidLinePaint
        );
        dashedLinePaint.setPathEffect(
                new DashPathEffect(
                        new float[]{
                                dp(
                                        6.0f
                                ),
                                dp(
                                        6.0f
                                )
                        },
                        0.0f
                )
        );

        stopFillPaint.setColor(
                Color.argb(
                        255,
                        24,
                        27,
                        30
                )
        );
        stopFillPaint.setStyle(
                Paint.Style.FILL
        );

        stopRingPaint.setColor(
                Color.argb(
                        245,
                        236,
                        240,
                        244
                )
        );
        stopRingPaint.setStyle(
                Paint.Style.STROKE
        );
        stopRingPaint.setStrokeWidth(
                dp(
                        1.8f
                )
        );

        namePaint.setColor(
                Color.WHITE
        );
        namePaint.setTextSize(
                sp(
                        14.0f
                )
        );
        namePaint.setTextAlign(
                Paint.Align.LEFT
        );

        timePaint.setColor(
                Color.argb(
                        240,
                        236,
                        240,
                        244
                )
        );
        timePaint.setTextSize(
                sp(
                        12.5f
                )
        );
        timePaint.setTextAlign(
                Paint.Align.RIGHT
        );

        distancePaint.setColor(
                Color.argb(
                        240,
                        236,
                        240,
                        244
                )
        );
        distancePaint.setTextSize(
                sp(
                        13.0f
                )
        );
        distancePaint.setTextAlign(
                Paint.Align.LEFT
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
        this.state =
                null;

        invalidate();
    }


    boolean hasState() {
        return state != null
                && !state.visibleStops.isEmpty();
    }


    float panelWidthPx() {
        if (getWidth() <= 0) {
            return 0.0f;
        }

        float maximumWidth =
                getWidth()
                        * PANEL_MAX_WIDTH_FRACTION;

        float baseWidth =
                Math.min(
                        maximumWidth,
                        getWidth()
                                * PANEL_WIDTH_FRACTION
                                + mm(
                                PANEL_EXTRA_WIDTH_MM
                        )
                );

        if (state == null
                || state.visibleStops.isEmpty()) {

            return baseWidth;
        }

        return Math.min(
                maximumWidth,
                Math.max(
                        baseWidth,
                        requiredPanelWidthPx()
                )
        );
    }


    private float requiredPanelWidthPx() {
        float lineX =
                dp(
                        LINE_X_DP
                )
                        + mm(
                        COLUMN_SHIFT_MM
                );

        float nameX =
                lineX
                        + dp(
                        NAME_GAP_DP
                );

        float required =
                0.0f;

        boolean showDistanceRow =
                state.showDistanceToNext
                        && state.hasNextStop()
                        && Double.isFinite(
                        state.distanceToNextM
                );

        int count =
                state.visibleStops.size();

        for (int index = 0;
                index < count;
                index++) {

            CaminoTimetableStop stop =
                    state.visibleStops.get(
                            index
                    );

            if (stop == null) {
                continue;
            }

            boolean emphasised =
                    (!showDistanceRow
                            && index == 0)
                            || index == count - 1;

            boolean oldFakeBold =
                    namePaint.isFakeBoldText();

            namePaint.setFakeBoldText(
                    emphasised
            );

            String name =
                    stop.name == null
                            ? ""
                            : stop.name;

            required =
                    Math.max(
                            required,
                            nameX
                                    + namePaint.measureText(
                                    normalizeName(
                                            name
                                    )
                            )
                                    + dp(
                                    PANEL_RIGHT_PADDING_DP
                            )
                    );

            namePaint.setFakeBoldText(
                    oldFakeBold
            );
        }

        if (showDistanceRow) {
            String distance =
                    distanceRowText();

            required =
                    Math.max(
                            required,
                            nameX
                                    + distancePaint.measureText(
                                    distance
                            )
                                    + dp(
                                    PANEL_RIGHT_PADDING_DP
                            )
                    );
        }

        return required;
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

        boolean showDistanceRow =
                state.showDistanceToNext
                        && state.hasNextStop()
                        && Double.isFinite(
                        state.distanceToNextM
                );

        int stopCount =
                state.visibleStops.size();

        float centerY =
                getHeight()
                        / 2.0f;

        float[] yPositions =
                buildStopYPositions(
                        stopCount,
                        showDistanceRow,
                        centerY
                );

        float topContentY =
                yPositions[
                        yPositions.length - 1
                        ];

        float bottomContentY =
                showDistanceRow
                        ? distanceAnchorY(
                                stopCount,
                                centerY
                        )
                        : yPositions[0];

        float panelTop =
                topContentY
                        - mm(
                        PANEL_VERTICAL_PADDING_MM
                );

        float panelBottom =
                bottomContentY
                        + mm(
                        PANEL_VERTICAL_PADDING_MM
                );

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

        drawTimetable(
                canvas,
                panelWidth
        );
    }


    private void drawTimetable(
            Canvas canvas,
            float panelWidth
    ) {
        List<CaminoTimetableStop> stops =
                state.visibleStops;

        int count =
                stops.size();

        if (count <= 0) {
            return;
        }

        boolean showDistanceRow =
                state.showDistanceToNext
                        && state.hasNextStop()
                        && Double.isFinite(
                        state.distanceToNextM
                );

        float centerY =
                getHeight()
                        / 2.0f;

        float lineX =
                dp(
                        LINE_X_DP
                )
                        + mm(
                        COLUMN_SHIFT_MM
                );

        float[] yPositions =
                buildStopYPositions(
                        count,
                        showDistanceRow,
                        centerY
                );

        drawStopSegments(
                canvas,
                lineX,
                yPositions,
                state.hasHiddenStopsBeforeGoal,
                showDistanceRow
        );

        for (int index =
                0;
                index < count;
                index++) {

            boolean emphasised =
                    (!showDistanceRow
                            && index == 0)
                            || index == count - 1;

            drawStop(
                    canvas,
                    stops.get(
                            index
                    ),
                    lineX,
                    yPositions[index],
                    panelWidth,
                    emphasised,
                    index == count - 1
            );
        }

        if (showDistanceRow) {
            drawDistanceToNext(
                    canvas,
                    lineX,
                    yPositions[0],
                    distanceAnchorY(
                            count,
                            centerY
                    ),
                    panelWidth
            );
        }
    }


    private void drawStopSegments(
            Canvas canvas,
            float lineX,
            float[] yPositions,
            boolean dashedBeforeGoal,
            boolean showDistanceRow
    ) {
        if (yPositions.length < 2) {
            return;
        }

        for (int index =
                0;
                index < yPositions.length - 1;
                index++) {

            Paint paint =
                    dashedBeforeGoal
                            && index == yPositions.length - 2
                            ? dashedLinePaint
                            : solidLinePaint;

            float firstRadius =
                    stopRadiusForIndex(
                            index,
                            yPositions.length,
                            showDistanceRow
                    );

            float secondRadius =
                    stopRadiusForIndex(
                            index + 1,
                            yPositions.length,
                            showDistanceRow
                    );

            /*
             * visibleStops run bottom -> top, therefore screen Y decreases.
             * Stop at the circumference of both rings instead of drawing
             * centre-to-centre.
             */
            float fromY =
                    yPositions[index]
                            - firstRadius;

            float toY =
                    yPositions[index + 1]
                            + secondRadius;

            if (fromY > toY) {
                canvas.drawLine(
                        lineX,
                        fromY,
                        lineX,
                        toY,
                        paint
                );
            }
        }
    }


    private float stopRadiusForIndex(
            int index,
            int count,
            boolean showDistanceRow
    ) {
        boolean emphasised =
                (!showDistanceRow
                        && index == 0)
                        || index == count - 1;

        return dp(
                emphasised
                        ? 5.0f
                        : 4.2f
        );
    }


    private float[] buildStopYPositions(
            int count,
            boolean showDistanceRow,
            float centerY
    ) {
        float goalY =
                centerY
                        - mm(
                        GOAL_GAP_MM
                                / 2.0f
                );

        float secondVillageY =
                centerY
                        + mm(
                        GOAL_GAP_MM
                                / 2.0f
                );

        float firstVillageY =
                secondVillageY
                        + mm(
                        NEAR_STOP_GAP_MM
                );

        float bottomEntryY =
                firstVillageY
                        + mm(
                        NEAR_STOP_GAP_MM
                );

        if (showDistanceRow) {
            switch (count) {
                case 1:
                    return new float[]{
                            goalY
                    };

                case 2:
                    return new float[]{
                            secondVillageY,
                            goalY
                    };

                default:
                    return new float[]{
                            firstVillageY,
                            secondVillageY,
                            goalY
                    };
            }
        }

        switch (count) {
            case 1:
                return new float[]{
                        goalY
                };

            case 2:
                return new float[]{
                        secondVillageY,
                        goalY
                };

            case 3:
                return new float[]{
                        firstVillageY,
                        secondVillageY,
                        goalY
                };

            default:
                return new float[]{
                        bottomEntryY,
                        firstVillageY,
                        secondVillageY,
                        goalY
                };
        }
    }


    private float distanceAnchorY(
            int stopCount,
            float centerY
    ) {
        float secondVillageY =
                centerY
                        + mm(
                        GOAL_GAP_MM
                                / 2.0f
                );

        if (stopCount <= 1) {
            return secondVillageY
                    + mm(
                    NEAR_STOP_GAP_MM
            );
        }

        if (stopCount == 2) {
            return secondVillageY
                    + mm(
                    NEAR_STOP_GAP_MM
            );
        }

        return secondVillageY
                + mm(
                NEAR_STOP_GAP_MM
                        * 2.0f
        );
    }


    private void drawStop(
            Canvas canvas,
            CaminoTimetableStop stop,
            float lineX,
            float y,
            float panelWidth,
            boolean emphasised,
            boolean isGoal
    ) {
        float radius =
                dp(
                        isGoal || emphasised
                                ? 5.0f
                                : 4.2f
                );

        if (emphasised) {
            Paint.Style originalStyle =
                    stopFillPaint.getStyle();

            int originalColor =
                    stopFillPaint.getColor();

            stopFillPaint.setStyle(
                    Paint.Style.FILL
            );
            stopFillPaint.setColor(
                    Color.argb(
                            245,
                            236,
                            240,
                            244
                    )
            );

            canvas.drawCircle(
                    lineX,
                    y,
                    radius,
                    stopFillPaint
            );

            stopFillPaint.setColor(
                    originalColor
            );
            stopFillPaint.setStyle(
                    originalStyle
            );
        }

        /*
         * Intermediate stops are genuinely hollow. Their centre shows the
         * translucent timetable/map background instead of an opaque black disc.
         * Lines are shortened to the ring edge in drawStopSegments(), so no
         * route line shines through the transparent centre.
         */
        canvas.drawCircle(
                lineX,
                y,
                radius,
                stopRingPaint
        );

        String time =
                formatClockMinutes(
                        stop.arrivalMinutesOfDay
                );

        String name =
                stop.name == null
                        ? ""
                        : stop.name;

        Paint.FontMetrics metrics =
                namePaint.getFontMetrics();

        float baseline =
                y - (
                        metrics.ascent
                                + metrics.descent
                ) / 2.0f;

        float timeX =
                lineX - dp(
                        TIME_GAP_DP
                );

        float nameX =
                lineX
                        + dp(
                        NAME_GAP_DP
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

        if (emphasised) {
            namePaint.setFakeBoldText(
                    true
            );
            timePaint.setFakeBoldText(
                    true
            );
        }

        canvas.drawText(
                time,
                timeX,
                baseline,
                timePaint
        );

        drawFullName(
                canvas,
                name,
                nameX,
                y,
                maxNameWidth
        );

        if (emphasised) {
            namePaint.setFakeBoldText(
                    false
            );
            timePaint.setFakeBoldText(
                    false
            );
        }
    }


    private void drawDistanceToNext(
            Canvas canvas,
            float lineX,
            float firstStopY,
            float currentY,
            float panelWidth
    ) {
        float markerRadius =
                dp(
                        3.2f
                );

        boolean firstStopIsGoal =
                state.visibleStops.size()
                        == 1;

        float firstStopRadius =
                dp(
                        firstStopIsGoal
                                ? 5.0f
                                : 4.2f
                );

        /*
         * Current marker is below the first upcoming stop. Leave both hollow
         * centres untouched and connect only circumference-to-circumference.
         */
        float fromY =
                currentY
                        - markerRadius;

        float toY =
                firstStopY
                        + firstStopRadius;

        if (fromY > toY) {
            canvas.drawLine(
                    lineX,
                    fromY,
                    lineX,
                    toY,
                    solidLinePaint
            );
        }

        canvas.drawCircle(
                lineX,
                currentY,
                markerRadius,
                stopRingPaint
        );

        String text =
                distanceRowText();

        Paint.FontMetrics metrics =
                distancePaint.getFontMetrics();

        float baseline =
                currentY - (
                        metrics.ascent
                                + metrics.descent
                ) / 2.0f;

        float x =
                lineX
                        + dp(
                        NAME_GAP_DP
                );

        float maxWidth =
                Math.max(
                        0.0f,
                        panelWidth
                                - dp(
                                PANEL_RIGHT_PADDING_DP
                        )
                                - x
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


    private void drawFullName(
            Canvas canvas,
            String text,
            float x,
            float centerY,
            float maxWidth
    ) {
        String value =
                normalizeName(
                        text
                );

        if (value.isEmpty()) {
            return;
        }

        float originalTextSize =
                namePaint.getTextSize();

        String[] lines;

        if (namePaint.measureText(
                value
        ) <= maxWidth) {

            lines =
                    new String[]{
                            value
                    };

        } else {
            lines =
                    bestTwoLineSplit(
                            value
                    );

            float widest =
                    Math.max(
                            namePaint.measureText(
                                    lines[0]
                            ),
                            namePaint.measureText(
                                    lines[1]
                            )
                    );

            if (widest > maxWidth
                    && maxWidth > 1.0f) {

                namePaint.setTextSize(
                        originalTextSize
                                * maxWidth
                                / widest
                                * 0.985f
                );
            }
        }

        Paint.FontMetrics metrics =
                namePaint.getFontMetrics();

        float centeredBaseline =
                centerY
                        - (
                        metrics.ascent
                                + metrics.descent
                ) / 2.0f;

        if (lines.length == 1) {
            canvas.drawText(
                    lines[0],
                    x,
                    centeredBaseline,
                    namePaint
            );

        } else {
            float lineAdvance =
                    (
                            metrics.descent
                                    - metrics.ascent
                    )
                            * 0.78f;

            canvas.drawText(
                    lines[0],
                    x,
                    centeredBaseline
                            - lineAdvance
                            / 2.0f,
                    namePaint
            );

            canvas.drawText(
                    lines[1],
                    x,
                    centeredBaseline
                            + lineAdvance
                            / 2.0f,
                    namePaint
            );
        }

        namePaint.setTextSize(
                originalTextSize
        );
    }


    private String[] bestTwoLineSplit(
            String text
    ) {
        String value =
                normalizeName(
                        text
                );

        String[] words =
                value.split(
                        "\\s+"
                );

        if (words.length >= 2) {
            int bestSplit =
                    1;

            float bestWidth =
                    Float.POSITIVE_INFINITY;

            for (int split = 1;
                    split < words.length;
                    split++) {

                String first =
                        joinWords(
                                words,
                                0,
                                split
                        );

                String second =
                        joinWords(
                                words,
                                split,
                                words.length
                        );

                float width =
                        Math.max(
                                namePaint.measureText(
                                        first
                                ),
                                namePaint.measureText(
                                        second
                                )
                        );

                if (width < bestWidth) {
                    bestWidth =
                            width;

                    bestSplit =
                            split;
                }
            }

            return new String[]{
                    joinWords(
                            words,
                            0,
                            bestSplit
                    ),
                    joinWords(
                            words,
                            bestSplit,
                            words.length
                    )
            };
        }

        int bestSplit =
                Math.max(
                        1,
                        value.length()
                                / 2
                );

        float bestWidth =
                Float.POSITIVE_INFINITY;

        for (int split = 1;
                split < value.length();
                split++) {

            String first =
                    value.substring(
                            0,
                            split
                    );

            String second =
                    value.substring(
                            split
                    );

            float width =
                    Math.max(
                            namePaint.measureText(
                                    first
                            ),
                            namePaint.measureText(
                                    second
                            )
                    );

            if (width < bestWidth) {
                bestWidth =
                        width;

                bestSplit =
                        split;
            }
        }

        return new String[]{
                value.substring(
                        0,
                        bestSplit
                ),
                value.substring(
                        bestSplit
                )
        };
    }


    private String joinWords(
            String[] words,
            int start,
            int end
    ) {
        StringBuilder result =
                new StringBuilder();

        for (int index = start;
                index < end;
                index++) {

            if (result.length() > 0) {
                result.append(
                        ' '
                );
            }

            result.append(
                    words[index]
            );
        }

        return result.toString();
    }


    private String normalizeName(
            String text
    ) {
        if (text == null) {
            return "";
        }

        return text.trim()
                .replaceAll(
                        "\\s+",
                        " "
                );
    }


    private String distanceRowText() {
        return "noch "
                + formatDistance(
                state.distanceToNextM
        )
                + " bis";
    }


    private String fitDistanceText(
            String text,
            float maxWidth
    ) {
        /*
         * v121 grows the panel from requiredPanelWidthPx() up to 50 %.
         * Keep the requested wording intact instead of dropping "noch"/"bis".
         */
        return text;
    }


    private String formatDistance(
            double distanceM
    ) {
        if (!Double.isFinite(
                distanceM
        ) || distanceM < 0.0) {
            return "—";
        }

        if (distanceM >= 1000.0) {
            return String.format(
                    Locale.GERMANY,
                    "%.1f km",
                    distanceM / 1000.0
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
                minutes % (
                        24 * 60
                );

        if (normalized < 0) {
            normalized += 24 * 60;
        }

        return String.format(
                Locale.GERMANY,
                "%02d:%02d",
                normalized / 60,
                normalized % 60
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


    private float mm(
            float value
    ) {
        return value
                * getResources()
                .getDisplayMetrics()
                .xdpi
                / 25.4f;
    }
}

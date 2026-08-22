package com.marukitano.caminoguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Right-edge projected elevation silhouette.
 *
 * The center-right control is a real drawn chevron, not a text >/< and not a
 * separate boxed button:
 *   right chevron = hide
 *   left chevron  = restore
 *
 * Vertical dragging inside the visible profile still controls the live cursor.
 */
final class CaminoHeightProfileView extends View {

    static final class Sample {
        final float screenXFraction;
        final float screenYFraction;
        final double elevationM;
        final boolean breakBefore;

        Sample(
                float screenXFraction,
                float screenYFraction,
                double elevationM,
                boolean breakBefore
        ) {
            this.screenXFraction =
                    screenXFraction;

            this.screenYFraction =
                    screenYFraction;

            this.elevationM =
                    elevationM;

            this.breakBefore =
                    breakBefore;
        }
    }

    private static final int MAX_DRAW_SAMPLES = 1000;

    private static final float PROFILE_WIDTH_DP = 126.0f;

    /*
     * Drawing stays 126dp wide. Only this narrow right-edge strip receives
     * height-cursor touches, so the transparent full-screen drawing View can
     * no longer steal taps from the HUD.
     */
    private static final float PROFILE_TOUCH_STRIP_DP = 38.0f;

    private static final float TOGGLE_WIDTH_DP = 34.0f;
    private static final float TOGGLE_HEIGHT_DP = 50.0f;
    private static final float TOGGLE_RIGHT_MARGIN_DP = 1.0f;

    private static final int TOUCH_NONE = 0;
    private static final int TOUCH_CURSOR = 1;
    private static final int TOUCH_TOGGLE = 2;

    private final Paint fillPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint linePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint edgePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint cursorPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint cursorDotPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint cursorLabelBackgroundPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint cursorLabelTextPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint togglePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint toggleBackgroundPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final CaminoHeightProfileModel model =
            new CaminoHeightProfileModel(
                    MAX_DRAW_SAMPLES
            );

    private List<Sample> samples =
            new ArrayList<>();

    private int cursorIndex =
            -1;

    private int touchMode =
            TOUCH_NONE;

    private float downX;
    private float downY;

    private float reveal =
            1.0f;

    private boolean profileHidden;
    private ValueAnimator revealAnimator;

    CaminoHeightProfileView(
            Context context
    ) {
        super(
                context
        );

        fillPaint.setColor(
                Color.argb(
                        76,
                        208,
                        68,
                        50
                )
        );

        fillPaint.setStyle(
                Paint.Style.FILL
        );

        linePaint.setColor(
                Color.argb(
                        235,
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
                        1.8f
                )
        );

        linePaint.setStrokeJoin(
                Paint.Join.ROUND
        );

        linePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        edgePaint.setColor(
                Color.argb(
                        72,
                        255,
                        240,
                        200
                )
        );

        edgePaint.setStyle(
                Paint.Style.STROKE
        );

        edgePaint.setStrokeWidth(
                dp(
                        1.0f
                )
        );

        cursorPaint.setColor(
                Color.argb(
                        220,
                        255,
                        240,
                        200
                )
        );

        cursorPaint.setStyle(
                Paint.Style.STROKE
        );

        cursorPaint.setStrokeWidth(
                dp(
                        1.4f
                )
        );

        cursorDotPaint.setColor(
                Color.argb(
                        245,
                        255,
                        240,
                        200
                )
        );

        cursorDotPaint.setStyle(
                Paint.Style.FILL
        );

        cursorLabelBackgroundPaint.setColor(
                Color.argb(
                        225,
                        61,
                        51,
                        44
                )
        );

        cursorLabelBackgroundPaint.setStyle(
                Paint.Style.FILL
        );

        cursorLabelTextPaint.setColor(
                Color.WHITE
        );

        cursorLabelTextPaint.setTextSize(
                sp(
                        12.0f
                )
        );

        togglePaint.setColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        togglePaint.setStyle(
                Paint.Style.STROKE
        );

        togglePaint.setStrokeWidth(
                dp(
                        2.2f
                )
        );

        togglePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        togglePaint.setStrokeJoin(
                Paint.Join.ROUND
        );

        toggleBackgroundPaint.setColor(
                Color.argb(
                        225,
                        92,
                        92,
                        92
                )
        );

        toggleBackgroundPaint.setStyle(
                Paint.Style.FILL
        );
    }

    void clearProfile() {
        if (samples.isEmpty()
                && getVisibility()
                == GONE) {
            return;
        }

        samples =
                new ArrayList<>();

        cursorIndex =
                -1;

        touchMode =
                TOUCH_NONE;

        setVisibility(
                GONE
        );

        invalidate();
    }

    void setSamples(
            List<Sample> input
    ) {
        if (input == null
                || input.size() < 2) {

            clearProfile();
            return;
        }

        samples =
                model.reduceSamples(
                        input
                );

        if (samples.size()
                < 2) {

            clearProfile();
            return;
        }

        if (cursorIndex
                >= samples.size()) {

            cursorIndex =
                    -1;
        }

        setVisibility(
                VISIBLE
        );

        invalidate();
    }


    @Override
    public boolean onTouchEvent(
            MotionEvent event
    ) {
        if (samples.size()
                < 2) {
            return false;
        }

        RectF toggle =
                toggleRect();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX =
                        event.getX();

                downY =
                        event.getY();

                if (toggle.contains(
                        downX,
                        downY
                )) {

                    touchMode =
                            TOUCH_TOGGLE;

                    return true;
                }

                if (profileHidden) {
                    return false;
                }

                /*
                 * The profile is a full-screen View only for drawing the
                 * horizontal cursor line across the map. Interaction is much
                 * smaller: only the narrow strip at the physical right edge
                 * may start a height-cursor drag.
                 */
                float touchStripLeft =
                        getWidth()
                                - dp(
                                PROFILE_TOUCH_STRIP_DP
                        );

                if (event.getX()
                        < touchStripLeft) {
                    return false;
                }

                touchMode =
                        TOUCH_CURSOR;

                cursorIndex =
                        model.findNearestSample(
                                samples,
                                event.getY(),
                                getHeight()
                        );

                getParent()
                        .requestDisallowInterceptTouchEvent(
                                true
                        );

                invalidate();

                return true;

            case MotionEvent.ACTION_MOVE:
                if (touchMode
                        == TOUCH_CURSOR) {

                    cursorIndex =
                            model.findNearestSample(
                                    samples,
                                    event.getY(),
                                    getHeight()
                            );

                    invalidate();

                    return true;
                }

                return touchMode
                        == TOUCH_TOGGLE;

            case MotionEvent.ACTION_UP:
                if (touchMode
                        == TOUCH_TOGGLE) {

                    float dx =
                            event.getX()
                                    - downX;

                    float dy =
                            event.getY()
                                    - downY;

                    if (Math.hypot(
                            dx,
                            dy
                    ) <= dp(
                            16.0f
                    )
                            && toggleRect()
                            .contains(
                                    event.getX(),
                                    event.getY()
                            )) {

                        toggleProfile();
                    }

                    touchMode =
                            TOUCH_NONE;

                    return true;
                }

                if (touchMode
                        == TOUCH_CURSOR) {

                    cursorIndex =
                            -1;

                    touchMode =
                            TOUCH_NONE;

                    getParent()
                            .requestDisallowInterceptTouchEvent(
                                    false
                            );

                    invalidate();

                    return true;
                }

                return false;

            case MotionEvent.ACTION_CANCEL:
                if (touchMode
                        == TOUCH_CURSOR) {

                    getParent()
                            .requestDisallowInterceptTouchEvent(
                                    false
                            );
                }

                cursorIndex =
                        -1;

                touchMode =
                        TOUCH_NONE;

                invalidate();

                return true;

            default:
                return touchMode
                        != TOUCH_NONE;
        }
    }

    private void toggleProfile() {
        animateReveal(
                profileHidden
                        ? 1.0f
                        : 0.0f
        );
    }

    private void animateReveal(
            float target
    ) {
        stopRevealAnimation();

        profileHidden =
                target
                        < 0.5f;

        cursorIndex =
                -1;

        revealAnimator =
                ValueAnimator.ofFloat(
                        reveal,
                        target
                );

        revealAnimator.setDuration(
                180L
        );

        revealAnimator.addUpdateListener(
                animator -> {
                    reveal =
                            (float)
                                    animator
                                            .getAnimatedValue();

                    invalidate();
                }
        );

        revealAnimator.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(
                            android.animation.Animator animation
                    ) {
                        reveal =
                                target;

                        revealAnimator =
                                null;

                        invalidate();
                    }
                }
        );

        revealAnimator.start();
    }

    private void stopRevealAnimation() {
        if (revealAnimator
                == null) {
            return;
        }

        revealAnimator.cancel();

        revealAnimator =
                null;
    }

    private RectF toggleRect() {
        float width =
                dp(
                        TOGGLE_WIDTH_DP
                );

        float height =
                dp(
                        TOGGLE_HEIGHT_DP
                );

        float right =
                getWidth()
                        - dp(
                        TOGGLE_RIGHT_MARGIN_DP
                );

        float left =
                right
                        - width;

        float top =
                (
                        getHeight()
                                - height
                ) / 2.0f;

        return new RectF(
                left,
                top,
                right,
                top + height
        );
    }


    @Override
    protected void onDraw(
            Canvas canvas
    ) {
        super.onDraw(
                canvas
        );

        if (samples.size()
                < 2) {
            return;
        }

        double minElevation =
                Double.POSITIVE_INFINITY;

        double maxElevation =
                Double.NEGATIVE_INFINITY;

        for (Sample sample
                : samples) {

            if (!Double.isFinite(
                    sample.elevationM
            )) {
                continue;
            }

            minElevation =
                    Math.min(
                            minElevation,
                            sample.elevationM
                    );

            maxElevation =
                    Math.max(
                            maxElevation,
                            sample.elevationM
                    );
        }

        if (!Double.isFinite(
                minElevation
        ) || !Double.isFinite(
                maxElevation
        )) {
            return;
        }

        double rawSpan =
                Math.max(
                        0.0,
                        maxElevation
                                - minElevation
                );

        double displaySpan =
                Math.max(
                        30.0,
                        rawSpan
                                * 1.15
                );

        double centreElevation =
                (
                        minElevation
                                + maxElevation
                ) / 2.0;

        double displayMin =
                centreElevation
                        - displaySpan / 2.0;

        double elevationSpan =
                Math.max(
                        1.0,
                        displaySpan
                );

        float slide =
                (
                        1.0f
                                - reveal
                )
                        * dp(
                        PROFILE_WIDTH_DP
                );

        float right =
                getWidth()
                        - dp(
                        2.0f
                )
                        + slide;

        float left =
                getWidth()
                        - dp(
                        PROFILE_WIDTH_DP
                )
                        + dp(
                        8.0f
                )
                        + slide;

        if (reveal
                > 0.01f) {

            canvas.drawLine(
                    right,
                    0.0f,
                    right,
                    getHeight(),
                    edgePaint
            );
        }

        Path linePath =
                null;

        Path fillPath =
                null;

        float lastY =
                0.0f;

        boolean fragmentOpen =
                false;

        for (Sample sample
                : samples) {

            if (!Double.isFinite(
                    sample.elevationM
            )) {
                continue;
            }

            float y =
                    screenY(
                            sample
                    );

            float x =
                    profileX(
                            sample,
                            left,
                            right,
                            displayMin,
                            elevationSpan
                    );

            if (!fragmentOpen
                    || sample.breakBefore) {

                if (fragmentOpen) {
                    fillPath.lineTo(
                            right,
                            lastY
                    );

                    fillPath.close();

                    canvas.drawPath(
                            fillPath,
                            fillPaint
                    );

                    canvas.drawPath(
                            linePath,
                            linePaint
                    );
                }

                linePath =
                        new Path();

                fillPath =
                        new Path();

                linePath.moveTo(
                        x,
                        y
                );

                fillPath.moveTo(
                        right,
                        y
                );

                fillPath.lineTo(
                        x,
                        y
                );

                fragmentOpen =
                        true;

            } else {
                linePath.lineTo(
                        x,
                        y
                );

                fillPath.lineTo(
                        x,
                        y
                );
            }

            lastY =
                    y;
        }

        if (fragmentOpen) {
            fillPath.lineTo(
                    right,
                    lastY
            );

            fillPath.close();

            canvas.drawPath(
                    fillPath,
                    fillPaint
            );

            canvas.drawPath(
                    linePath,
                    linePaint
            );
        }

        if (!profileHidden
                && reveal > 0.95f
                && touchMode == TOUCH_CURSOR
                && cursorIndex >= 0
                && cursorIndex < samples.size()) {

            drawCursor(
                    canvas,
                    samples.get(
                            cursorIndex
                    ),
                    left,
                    right,
                    displayMin,
                    elevationSpan
            );
        }

        drawToggle(
                canvas
        );
    }

    private void drawToggle(
            Canvas canvas
    ) {
        RectF rect =
                toggleRect();

        canvas.drawRoundRect(
                rect,
                dp(
                        10.0f
                ),
                dp(
                        10.0f
                ),
                toggleBackgroundPaint
        );

        float cx =
                rect.centerX();

        float cy =
                rect.centerY();

        float halfHeight =
                dp(
                        9.0f
                );

        float depth =
                dp(
                        5.0f
                );

        Path path =
                new Path();

        if (profileHidden) {
            /*
             * Wide/shallow left-facing chevron.
             */
            path.moveTo(
                    cx + depth,
                    cy - halfHeight
            );

            path.lineTo(
                    cx - depth,
                    cy
            );

            path.lineTo(
                    cx + depth,
                    cy + halfHeight
            );

        } else {
            path.moveTo(
                    cx - depth,
                    cy - halfHeight
            );

            path.lineTo(
                    cx + depth,
                    cy
            );

            path.lineTo(
                    cx - depth,
                    cy + halfHeight
            );
        }

        canvas.drawPath(
                path,
                togglePaint
        );
    }

    private void drawCursor(
            Canvas canvas,
            Sample sample,
            float profileLeft,
            float right,
            double displayMin,
            double elevationSpan
    ) {
        float y =
                screenY(
                        sample
                );

        float routeX =
                Math.max(
                        0.0f,
                        Math.min(
                                getWidth(),
                                sample.screenXFraction
                                        * getWidth()
                        )
                );

        float profileX =
                profileX(
                        sample,
                        profileLeft,
                        right,
                        displayMin,
                        elevationSpan
                );

        canvas.drawLine(
                Math.min(
                        routeX,
                        profileX
                ),
                y,
                right,
                y,
                cursorPaint
        );

        canvas.drawCircle(
                routeX,
                y,
                dp(
                        3.2f
                ),
                cursorDotPaint
        );

        String text =
                String.format(
                        Locale.GERMANY,
                        "%.0f m",
                        sample.elevationM
                );

        float horizontalPadding =
                dp(
                        7.0f
                );

        float verticalPadding =
                dp(
                        4.0f
                );

        float textWidth =
                cursorLabelTextPaint
                        .measureText(
                                text
                        );

        Paint.FontMetrics metrics =
                cursorLabelTextPaint
                        .getFontMetrics();

        float textHeight =
                metrics.descent
                        - metrics.ascent;

        float labelRight =
                Math.max(
                        dp(
                                4.0f
                        ),
                        getWidth()
                                - dp(
                                PROFILE_WIDTH_DP
                        )
                                - dp(
                                8.0f
                        )
                );

        float labelLeft =
                Math.max(
                        dp(
                                4.0f
                        ),
                        labelRight
                                - textWidth
                                - horizontalPadding
                                * 2.0f
                );

        float labelTop =
                y
                        - textHeight / 2.0f
                        - verticalPadding;

        labelTop =
                Math.max(
                        dp(
                                2.0f
                        ),
                        Math.min(
                                getHeight()
                                        - textHeight
                                        - verticalPadding
                                        * 2.0f
                                        - dp(
                                        2.0f
                                ),
                                labelTop
                        )
                );

        float labelBottom =
                labelTop
                        + textHeight
                        + verticalPadding
                        * 2.0f;

        canvas.drawRoundRect(
                labelLeft,
                labelTop,
                labelRight,
                labelBottom,
                dp(
                        8.0f
                ),
                dp(
                        8.0f
                ),
                cursorLabelBackgroundPaint
        );

        float baseline =
                labelTop
                        + verticalPadding
                        - metrics.ascent;

        canvas.drawText(
                text,
                labelLeft
                        + horizontalPadding,
                baseline,
                cursorLabelTextPaint
        );
    }

    private float screenY(
            Sample sample
    ) {
        return Math.max(
                0.0f,
                Math.min(
                        getHeight(),
                        sample.screenYFraction
                                * getHeight()
                )
        );
    }

    private float profileX(
            Sample sample,
            float left,
            float right,
            double displayMin,
            double elevationSpan
    ) {
        double normalisedElevation =
                (
                        sample.elevationM
                                - displayMin
                ) / elevationSpan;

        normalisedElevation =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                normalisedElevation
                        )
                );

        return (float)
                (
                        right
                                - normalisedElevation
                                * (
                                right
                                        - left
                        )
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

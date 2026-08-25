package com.marukitano.caminoguard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
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

    interface ProfileVisibilityListener {
        void onProfileVisibilityChanged(
                boolean visible
        );
    }

    static final class Sample {
        final float screenXFraction;
        final float screenYFraction;
        final double elevationM;
        final double distanceM;
        final double slopePercent;
        final boolean breakBefore;

        Sample(
                float screenXFraction,
                float screenYFraction,
                double elevationM,
                double distanceM,
                double slopePercent,
                boolean breakBefore
        ) {
            this.screenXFraction =
                    screenXFraction;

            this.screenYFraction =
                    screenYFraction;

            this.elevationM =
                    elevationM;

            this.distanceM =
                    distanceM;

            this.slopePercent =
                    slopePercent;

            this.breakBefore =
                    breakBefore;
        }
    }

    private static final int MAX_DRAW_SAMPLES = 1000;

    /*
     * Dynamic horizontal elevation scale:
     *
     *   highest visible elevation -> 1/3 screen width from the right edge
     *   lowest visible elevation  -> 3 mm from the right edge
     *
     * Absolute elevation values do not change the available profile width.
     */
    private static final float PROFILE_WIDTH_FRACTION = 1.0f / 3.0f;
    private static final float PROFILE_RIGHT_MARGIN_MM = 3.0f;

    private static final float CURSOR_LABEL_RIGHT_MARGIN_DP = 134.0f;

    /*
     * Swipe altitude label sits 10 mm further right than before.
     * Use physical millimetres so this remains visually consistent across
     * different phone densities.
     */
    private static final float CURSOR_LABEL_SHIFT_RIGHT_MM = 10.0f;

    private static final float PROFILE_END_FADE_MM = 6.0f;

    private static final double SLOPE_WINDOW_M = 100.0;
    private static final double SLOPE_NEUTRAL_PERCENT = 1.0;
    private static final double FULL_UPHILL_GRADE_PERCENT = 12.0;
    private static final double FULL_DOWNHILL_GRADE_PERCENT = 12.0;

    /*
     * The profile occupies the right third of the screen. Interaction remains
     * restricted to this narrow right-edge strip so the transparent drawing
     * View does not steal map taps.
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

    private final Paint slopeLinePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint profileFadeMaskPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint linePaint =
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

    private final Paint lockedMarkerOuterPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint lockedMarkerInnerPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint lockedMarkerLabelBackgroundPaint =
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

    private Sample lockedPositionSample;

    /*
     * Visual mode only:
     * false -> normal/marked profile stays translucent white
     * true  -> locked profile uses the stats-overlay dark fill
     */
    private boolean lockedProfileStyle;

    private int touchMode =
            TOUCH_NONE;

    private float downX;
    private float downY;

    private float reveal =
            0.0f;

    private boolean profileHidden =
            true;

    private boolean profileAvailable =
            true;

    private boolean restoreProfileWhenAvailable;

    private ValueAnimator revealAnimator;
    private ProfileVisibilityListener profileVisibilityListener;

    void setProfileVisibilityListener(
            ProfileVisibilityListener listener
    ) {
        this.profileVisibilityListener =
                listener;
    }

    boolean isProfileHidden() {
        return profileHidden;
    }

    void showProfile() {
        if (!profileAvailable
                || !profileHidden) {

            return;
        }

        /*
         * A route selection is an explicit user action, so a newly selected
         * route may reveal the profile even if the previous generic viewport
         * profile had been left closed.
         */
        restoreProfileWhenAvailable =
                false;

        animateReveal(
                1.0f
        );

        if (profileVisibilityListener
                != null) {

            profileVisibilityListener
                    .onProfileVisibilityChanged(
                            true
                    );
        }
    }

    void setProfileAvailable(
            boolean available
    ) {
        if (profileAvailable
                == available) {
            return;
        }

        profileAvailable =
                available;

        if (!available) {
            /*
             * Zoom-based hiding is temporary. Remember whether the USER had
             * the profile open so it can be restored automatically when the
             * map comes back below the lower hysteresis threshold.
             */
            restoreProfileWhenAvailable =
                    !profileHidden;

            stopRevealAnimation();

            reveal =
                    0.0f;

            profileHidden =
                    true;

            cursorIndex =
                    -1;

            touchMode =
                    TOUCH_NONE;

            setVisibility(
                    INVISIBLE
            );

        } else {
            setVisibility(
                    VISIBLE
            );

            if (restoreProfileWhenAvailable) {
                animateReveal(
                        1.0f
                );
            }
        }

        invalidate();
    }


    CaminoHeightProfileView(
            Context context
    ) {
        super(
                context
        );

        /*
         * Normal and merely-marked profiles keep the original translucent
         * white fill. Lock mode switches ONLY this filled body to the same
         * dark colour as the selection stats overlay.
         */
        fillPaint.setColor(
                Color.argb(
                        76,
                        255,
                        255,
                        255
                )
        );

        fillPaint.setStyle(
                Paint.Style.FILL
        );

        slopeLinePaint.setStyle(
                Paint.Style.STROKE
        );

        slopeLinePaint.setStrokeWidth(
                dp(
                        4.4f
                )
        );

        slopeLinePaint.setStrokeJoin(
                Paint.Join.ROUND
        );

        slopeLinePaint.setStrokeCap(
                Paint.Cap.ROUND
        );

        linePaint.setColor(
                Color.argb(
                        235,
                        255,
                        255,
                        255
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

        lockedMarkerOuterPaint.setColor(
                Color.rgb(
                        36,
                        86,
                        143
                )
        );

        lockedMarkerOuterPaint.setStyle(
                Paint.Style.FILL
        );

        lockedMarkerInnerPaint.setColor(
                Color.rgb(
                        166,
                        218,
                        248
                )
        );

        lockedMarkerInnerPaint.setStyle(
                Paint.Style.FILL
        );

        lockedMarkerLabelBackgroundPaint.setColor(
                Color.argb(
                        220,
                        31,
                        52,
                        72
                )
        );

        lockedMarkerLabelBackgroundPaint.setStyle(
                Paint.Style.FILL
        );
    }

    void setLockedProfileStyle(
            boolean locked
    ) {
        if (lockedProfileStyle
                == locked) {

            return;
        }

        lockedProfileStyle =
                locked;

        fillPaint.setColor(
                locked
                        ? Color.argb(
                        150,
                        24,
                        27,
                        30
                )
                        : Color.argb(
                        76,
                        255,
                        255,
                        255
                )
        );

        invalidate();
    }

    void setLockedPositionSample(
            Sample sample
    ) {
        lockedPositionSample =
                sample;

        invalidate();
    }

    void clearProfile() {
        samples =
                new ArrayList<>();

        cursorIndex =
                -1;

        lockedPositionSample =
                null;

        touchMode =
                TOUCH_NONE;

        setVisibility(
                VISIBLE
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
        if (!profileAvailable) {
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

                if (profileHidden
                        || samples.size()
                        < 2) {
                    return false;
                }

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
        boolean visible =
                profileHidden;

        animateReveal(
                visible
                        ? 1.0f
                        : 0.0f
        );

        if (profileVisibilityListener
                != null) {

            profileVisibilityListener
                    .onProfileVisibilityChanged(
                            visible
                    );
        }
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

            drawToggle(
                    canvas
            );

            return;
        }

        /*
         * Draw the profile with its original paint first. End fading is done
         * afterwards by one shared alpha mask over the completed diagram.
         */
        fillPaint.setShader(
                null
        );

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

        /*
         * Dynamic elevation range from the currently visible profile samples.
         * The absolute metre values are irrelevant for the horizontal width:
         * the visible minimum and maximum always use the full profile band.
         */
        double displayMin =
                minElevation;

        double displayMax =
                maxElevation;

        double elevationSpan =
                displayMax
                        - displayMin;

        if (elevationSpan
                < 0.001) {

            /*
             * Degenerate flat profile: create a tiny symmetric range so the
             * line sits in the middle of the band instead of at one edge.
             */
            displayMin =
                    minElevation
                            - 0.5;

            displayMax =
                    maxElevation
                            + 0.5;

            elevationSpan =
                    displayMax
                            - displayMin;
        }

        float profileWidth =
                getWidth()
                        * PROFILE_WIDTH_FRACTION;

        /*
         * Hide/reveal only needs to slide the right-third profile band out of
         * the screen. A little extra margin guarantees that the 3-mm inset is
         * also fully outside.
         */
        float slide =
                (
                        1.0f
                                - reveal
                )
                        * (
                        profileWidth
                                + mm(
                                PROFILE_RIGHT_MARGIN_MM
                        )
                );

        /*
         * Geometry at reveal == 1:
         *
         *   left  = highest visible elevation = exactly 1/3 screen from right
         *   right = lowest visible elevation  = exactly 3 mm from right
         */
        float left =
                getWidth()
                        - profileWidth
                        + slide;

        float right =
                getWidth()
                        - mm(
                        PROFILE_RIGHT_MARGIN_MM
                )
                        + slide;

        /*
         * The 3-mm inset belongs ONLY to the elevation contour / minimum
         * elevation position. The translucent profile body continues all the
         * way to the physical right screen edge.
         */
        float fillRight =
                getWidth()
                        + slide;

        int profileFadeLayer =
                beginProfileFadeLayer(
                        canvas
                );

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
                            fillRight,
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
                        fillRight,
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
                    fillRight,
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

        drawSlopeLine(
                canvas,
                left,
                right,
                displayMin,
                elevationSpan
        );

        finishProfileFadeLayer(
                canvas,
                profileFadeLayer
        );

        if (!profileHidden
                && reveal > 0.01f
                && lockedPositionSample != null
                && Double.isFinite(
                lockedPositionSample.elevationM
        )) {

            drawLockedPositionMarker(
                    canvas,
                    lockedPositionSample,
                    left,
                    right,
                    displayMin,
                    elevationSpan
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

    private void drawSlopeLine(
            Canvas canvas,
            float left,
            float right,
            double displayMin,
            double elevationSpan
    ) {
        if (samples.size()
                < 2
                || reveal <= 0.01f) {
            return;
        }

        for (int index = 1;
                index < samples.size();
                index++) {

            Sample previous =
                    samples.get(
                            index - 1
                    );

            Sample current =
                    samples.get(
                            index
                    );

            if (current.breakBefore
                    || !Double.isFinite(
                    previous.elevationM
            )
                    || !Double.isFinite(
                    current.elevationM
            )) {
                continue;
            }

            double slopePercent =
                    smoothedSlopePercent(
                            index
                    );

            float previousX =
                    profileX(
                            previous,
                            left,
                            right,
                            displayMin,
                            elevationSpan
                    );

            float previousY =
                    screenY(
                            previous
                    );

            float currentX =
                    profileX(
                            current,
                            left,
                            right,
                            displayMin,
                            elevationSpan
                    );

            float currentY =
                    screenY(
                            current
                    );

            slopeLinePaint.setColor(
                    slopeLineColor(
                            slopePercent
                    )
            );

            canvas.drawLine(
                    previousX,
                    previousY,
                    currentX,
                    currentY,
                    slopeLinePaint
            );
        }
    }

    private double smoothedSlopePercent(
            int centerIndex
    ) {
        if (centerIndex
                < 0
                || centerIndex
                >= samples.size()) {

            return 0.0;
        }

        Sample sample =
                samples.get(
                        centerIndex
                );

        if (!Double.isFinite(
                sample.slopePercent
        )) {
            return 0.0;
        }

        return sample.slopePercent;
    }


    private float profileEndFadePx() {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_MM,
                PROFILE_END_FADE_MM,
                getResources()
                        .getDisplayMetrics()
        );
    }



    private int beginProfileFadeLayer(
            Canvas canvas
    ) {
        if (!profileNeedsEndFade()) {
            return -1;
        }

        return canvas.saveLayer(
                0.0f,
                0.0f,
                getWidth(),
                getHeight(),
                null
        );
    }

    private boolean profileNeedsEndFade() {
        /*
         * v13 simplification:
         * always use the same beautiful fragment fade, even when a fragment
         * starts or ends directly at the physical screen edge.
         */
        return samples.size()
                >= 2
                && getHeight()
                > 0;
    }

    private void finishProfileFadeLayer(
            Canvas canvas,
            int layerSaveCount
    ) {
        if (layerSaveCount
                < 0) {
            return;
        }

        float canvasHeight =
                getHeight();

        if (canvasHeight
                <= 0.0f
                || samples.size()
                < 2) {

            canvas.restoreToCount(
                    layerSaveCount
            );

            return;
        }

        float fadePx =
                profileEndFadePx();

        int transparent =
                Color.argb(
                        0,
                        255,
                        255,
                        255
                );

        int opaque =
                Color.argb(
                        255,
                        255,
                        255,
                        255
                );

        List<Integer> maskColors =
                new ArrayList<>();

        List<Float> maskPositions =
                new ArrayList<>();

        /*
         * Outside visible profile fragments the mask stays transparent.
         */
        addFadeStop(
                maskColors,
                maskPositions,
                transparent,
                0.0f
        );

        int fragmentStart =
                0;

        for (int i = 1;
                i <= samples.size();
                i++) {

            boolean fragmentEnds =
                    i
                            == samples.size()
                            || samples.get(
                            i
                    ).breakBefore;

            if (!fragmentEnds) {
                continue;
            }

            Sample first =
                    samples.get(
                            fragmentStart
                    );

            Sample last =
                    samples.get(
                            i - 1
                    );

            float firstY =
                    screenY(
                            first
                    );

            float lastY =
                    screenY(
                            last
                    );

            if (!Float.isFinite(
                    firstY
            )
                    || !Float.isFinite(
                    lastY
            )) {

                fragmentStart =
                        i;

                continue;
            }

            float minY =
                    Math.max(
                            0.0f,
                            Math.min(
                                    firstY,
                                    lastY
                            )
                    );

            float maxY =
                    Math.min(
                            canvasHeight,
                            Math.max(
                                    firstY,
                                    lastY
                            )
                    );

            if (maxY
                    <= minY) {

                fragmentStart =
                        i;

                continue;
            }

            /*
             * v13 change:
             * ALWAYS fade both fragment ends with the same algorithm, even
             * at y=0 or y=screenHeight.
             */
            boolean fadeTop =
                    true;

            boolean fadeBottom =
                    true;

            float span =
                    maxY
                            - minY;

            float localFadePx =
                    Math.min(
                            fadePx,
                            span
                    );

            addFadeStop(
                    maskColors,
                    maskPositions,
                    transparent,
                    minY
                            / canvasHeight
            );

            if (span
                    <= localFadePx
                    * 2.0f) {

                /*
                 * Very short fragment: both fades overlap, with full NORMAL
                 * profile opacity only at the center.
                 */
                addFadeStop(
                        maskColors,
                        maskPositions,
                        opaque,
                        (
                                minY
                                        + span
                                        * 0.5f
                        )
                                / canvasHeight
                );

                addFadeStop(
                        maskColors,
                        maskPositions,
                        transparent,
                        maxY
                                / canvasHeight
                );

            } else {
                addFadeStop(
                        maskColors,
                        maskPositions,
                        opaque,
                        (
                                minY
                                        + localFadePx
                        )
                                / canvasHeight
                );

                addFadeStop(
                        maskColors,
                        maskPositions,
                        opaque,
                        (
                                maxY
                                        - localFadePx
                        )
                                / canvasHeight
                );

                addFadeStop(
                        maskColors,
                        maskPositions,
                        transparent,
                        maxY
                                / canvasHeight
                );
            }

            fragmentStart =
                    i;
        }

        addFadeStop(
                maskColors,
                maskPositions,
                transparent,
                1.0f
        );

        if (maskColors.size()
                < 2) {

            canvas.restoreToCount(
                    layerSaveCount
            );

            return;
        }

        int[] colors =
                new int[
                        maskColors.size()
                        ];

        float[] positions =
                new float[
                        maskPositions.size()
                        ];

        for (int i = 0;
                i < maskColors.size();
                i++) {

            colors[
                    i
                    ] =
                    maskColors.get(
                            i
                    );

            positions[
                    i
                    ] =
                    maskPositions.get(
                            i
                    );
        }

        profileFadeMaskPaint.setShader(
                new LinearGradient(
                        0.0f,
                        0.0f,
                        0.0f,
                        canvasHeight,
                        colors,
                        positions,
                        Shader.TileMode.CLAMP
                )
        );

        profileFadeMaskPaint.setXfermode(
                new PorterDuffXfermode(
                        PorterDuff.Mode.DST_IN
                )
        );

        /*
         * One alpha mask with one independent fade-in and fade-out for EVERY
         * visible fragment, regardless of whether that fragment touches the
         * screen border.
         */
        canvas.drawRect(
                0.0f,
                0.0f,
                getWidth(),
                canvasHeight,
                profileFadeMaskPaint
        );

        profileFadeMaskPaint.setXfermode(
                null
        );

        profileFadeMaskPaint.setShader(
                null
        );

        canvas.restoreToCount(
                layerSaveCount
        );
    }



    private void addFadeStop(
            List<Integer> colors,
            List<Float> positions,
            int color,
            float position
    ) {
        position =
                Math.max(
                        0.0f,
                        Math.min(
                                1.0f,
                                position
                        )
                );

        if (!positions.isEmpty()) {
            float previous =
                    positions.get(
                            positions.size() - 1
                    );

            /*
             * LinearGradient requires monotonically increasing positions.
             * Equal positions are useful for a hard transition at an empty
             * fragment gap, but floating point noise must never reverse them.
             */
            position =
                    Math.max(
                            previous,
                            position
                    );
        }

        colors.add(
                color
        );

        positions.add(
                position
        );
    }

    private int slopeLineColor(
            double slopePercent
    ) {
        final int neutralR = 255;
        final int neutralG = 250;
        final int neutralB = 238;

        final int uphillR = 190;
        final int uphillG = 108;
        final int uphillB = 86;

        final int downhillR = 111;
        final int downhillG = 143;
        final int downhillB = 104;

        if (!Double.isFinite(
                slopePercent
        )) {
            return Color.argb(
                    210,
                    neutralR,
                    neutralG,
                    neutralB
            );
        }

        double magnitude =
                Math.abs(
                        slopePercent
                );

        if (magnitude
                <= SLOPE_NEUTRAL_PERCENT) {

            return Color.argb(
                    210,
                    neutralR,
                    neutralG,
                    neutralB
            );
        }

        double fullScale =
                slopePercent > 0.0
                        ? FULL_UPHILL_GRADE_PERCENT
                        : FULL_DOWNHILL_GRADE_PERCENT;

        double factor =
                (
                        magnitude
                                - SLOPE_NEUTRAL_PERCENT
                )
                        / (
                        fullScale
                                - SLOPE_NEUTRAL_PERCENT
                );

        factor =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                factor
                        )
                );

        int targetR =
                slopePercent > 0.0
                        ? uphillR
                        : downhillR;

        int targetG =
                slopePercent > 0.0
                        ? uphillG
                        : downhillG;

        int targetB =
                slopePercent > 0.0
                        ? uphillB
                        : downhillB;

        int red =
                (int)
                        Math.round(
                                neutralR
                                        + (
                                        targetR
                                                - neutralR
                                )
                                        * factor
                        );

        int green =
                (int)
                        Math.round(
                                neutralG
                                        + (
                                        targetG
                                                - neutralG
                                )
                                        * factor
                        );

        int blue =
                (int)
                        Math.round(
                                neutralB
                                        + (
                                        targetB
                                                - neutralB
                                )
                                        * factor
                        );

        return Color.argb(
                210,
                red,
                green,
                blue
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

    private void drawLockedPositionMarker(
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

        float x =
                profileX(
                        sample,
                        profileLeft,
                        right,
                        displayMin,
                        elevationSpan
                );

        canvas.drawCircle(
                x,
                y,
                dp(
                        5.0f
                ),
                lockedMarkerOuterPaint
        );

        canvas.drawCircle(
                x,
                y,
                dp(
                        3.25f
                ),
                lockedMarkerInnerPaint
        );

        String text =
                String.format(
                        Locale.GERMANY,
                        "%.0f m",
                        sample.elevationM
                );

        float horizontalPadding =
                dp(
                        6.0f
                );

        float verticalPadding =
                dp(
                        3.0f
                );

        float gap =
                dp(
                        8.0f
                );

        float textWidth =
                cursorLabelTextPaint.measureText(
                        text
                );

        Paint.FontMetrics metrics =
                cursorLabelTextPaint.getFontMetrics();

        float textHeight =
                metrics.descent
                        - metrics.ascent;

        float labelRight =
                x
                        - gap;

        float labelLeft =
                labelRight
                        - textWidth
                        - horizontalPadding
                        * 2.0f;

        if (labelLeft
                < dp(
                4.0f
        )) {

            labelLeft =
                    x
                            + gap;

            labelRight =
                    labelLeft
                            + textWidth
                            + horizontalPadding
                            * 2.0f;
        }

        if (labelRight
                > getWidth()
                - dp(
                4.0f
        )) {

            labelRight =
                    getWidth()
                            - dp(
                            4.0f
                    );

            labelLeft =
                    labelRight
                            - textWidth
                            - horizontalPadding
                            * 2.0f;
        }

        float labelTop =
                y
                        - textHeight
                        / 2.0f
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
                        7.0f
                ),
                dp(
                        7.0f
                ),
                lockedMarkerLabelBackgroundPaint
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
                Math.min(
                        getWidth()
                                - dp(
                                4.0f
                        ),
                        Math.max(
                                dp(
                                        4.0f
                                ),
                                getWidth()
                                        - dp(
                                        CURSOR_LABEL_RIGHT_MARGIN_DP
                                )
                                        + mm(
                                        CURSOR_LABEL_SHIFT_RIGHT_MM
                                )
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

package com.marukitano.caminoguard;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.PopupWindow;

import java.util.Locale;

/**
 * Small non-system wheel popup for planning start time.
 *
 * Five half-hour slots are visible. The selected centre slot has the same
 * warm halo as the compact Start HH:mm control.
 */
final class CaminoStartTimePickerPopup {

    interface Listener {
        void onTimeChanged(
                int minutesOfDay
        );
    }

    private final Context context;
    private final Listener listener;
    private final WheelView wheelView;
    private final PopupWindow popupWindow;

    CaminoStartTimePickerPopup(
            Context context,
            int initialMinutes,
            Listener listener
    ) {
        this.context =
                context;

        this.listener =
                listener;

        wheelView =
                new WheelView(
                        context,
                        initialMinutes
                );

        popupWindow =
                new PopupWindow(
                        wheelView,
                        dp(
                                176
                        ),
                        dp(
                                238
                        ),
                        true
                );

        popupWindow.setBackgroundDrawable(
                new ColorDrawable(
                        Color.TRANSPARENT
                )
        );

        popupWindow.setOutsideTouchable(
                true
        );

        popupWindow.setElevation(
                dp(
                        12
                )
        );
    }

    void show(
            View anchor
    ) {
        popupWindow.showAtLocation(
                anchor,
                Gravity.CENTER,
                0,
                0
        );
    }

    void dismiss() {
        popupWindow.dismiss();
    }

    private final class WheelView
            extends View {

        private static final int STEP_MINUTES = 30;

        private final Paint backgroundPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint borderPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint tweenPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint haloPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private int minutes;
        private float lastTouchY;
        private long lastTouchEventTimeMs;
        private float lastVelocityPxPerMs;
        private float dragOffsetPx;
        private ValueAnimator snapAnimator;
        private ValueAnimator inertialAnimator;

        WheelView(
                Context context,
                int initialMinutes
        ) {
            super(
                    context
            );

            minutes =
                    normalizeMinutes(
                            initialMinutes
                    );

            setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
            );

            backgroundPaint.setColor(
                    Color.rgb(
                            245,
                            241,
                            231
                    )
            );

            backgroundPaint.setStyle(
                    Paint.Style.FILL
            );

            borderPaint.setColor(
                    Color.argb(
                            85,
                            35,
                            39,
                            43
                    )
            );

            borderPaint.setStyle(
                    Paint.Style.STROKE
            );

            borderPaint.setStrokeWidth(
                    dp(
                            1
                    )
            );

            tweenPaint.setTypeface(
                    Typeface.create(
                            Typeface.MONOSPACE,
                            Typeface.NORMAL
                    )
            );

            tweenPaint.setTextAlign(
                    Paint.Align.CENTER
            );

            tweenPaint.setStyle(
                    Paint.Style.FILL_AND_STROKE
            );

            haloPaint.setColor(
                    Color.argb(
                            145,
                            255,
                            221,
                            105
                    )
            );

            haloPaint.setStyle(
                    Paint.Style.FILL
            );

            setClickable(
                    true
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {
            super.onDraw(
                    canvas
            );

            float width =
                    getWidth();

            float height =
                    getHeight();

            RectF panel =
                    new RectF(
                            dp(
                                    4
                            ),
                            dp(
                                    4
                            ),
                            width
                                    - dp(
                                    4
                            ),
                            height
                                    - dp(
                                    4
                            )
                    );

            backgroundPaint.setShadowLayer(
                    dp(
                            10
                    ),
                    0.0f,
                    dp(
                            3
                    ),
                    Color.argb(
                            75,
                            0,
                            0,
                            0
                    )
            );

            canvas.drawRoundRect(
                    panel,
                    dp(
                            18
                    ),
                    dp(
                            18
                    ),
                    backgroundPaint
            );

            backgroundPaint.clearShadowLayer();

            canvas.drawRoundRect(
                    panel,
                    dp(
                            18
                    ),
                    dp(
                            18
                    ),
                    borderPaint
            );

            float centreY =
                    height
                            / 2.0f;

            float rowGap =
                    dp(
                            40
                    );

            RectF halo =
                    new RectF(
                            dp(
                                    24
                            ),
                            centreY
                                    - dp(
                                    22
                            ),
                            width
                                    - dp(
                                    24
                            ),
                            centreY
                                    + dp(
                                    22
                            )
                    );

            canvas.drawRoundRect(
                    halo,
                    dp(
                            14
                    ),
                    dp(
                            14
                    ),
                    haloPaint
            );

            for (int row = -2;
                    row <= 2;
                    row++) {

                int value =
                        normalizeMinutes(
                                minutes
                                        - row
                                        * STEP_MINUTES
                        );

                float rowOffsetPx =
                        row
                                * rowGap
                                - dragOffsetPx;

                float distanceRows =
                        Math.abs(
                                rowOffsetPx
                        )
                                / rowGap;

                float focus =
                        1.0f
                                - Math.min(
                                1.0f,
                                distanceRows
                        );

                float easedFocus =
                        focus
                                * focus
                                * (
                                3.0f
                                        - 2.0f
                                        * focus
                        );

                float farFactor =
                        Math.max(
                                0.0f,
                                Math.min(
                                        1.0f,
                                        distanceRows
                                                - 1.0f
                                )
                        );

                float textSizeSp =
                        lerp(
                                16.5f,
                                22.0f,
                                easedFocus
                        );

                tweenPaint.setTextSize(
                        textSizeSp
                                * getResources()
                                .getDisplayMetrics()
                                .scaledDensity
                );

                tweenPaint.setStrokeWidth(
                        lerp(
                                dpFloat(
                                        0.05f
                                ),
                                dpFloat(
                                        0.85f
                                ),
                                easedFocus
                        )
                );

                int baseColor =
                        blendColor(
                                Color.rgb(
                                        100,
                                        104,
                                        104
                                ),
                                Color.rgb(
                                        164,
                                        164,
                                        158
                                ),
                                farFactor
                        );

                tweenPaint.setColor(
                        blendColor(
                                baseColor,
                                Color.rgb(
                                        35,
                                        39,
                                        43
                                ),
                                easedFocus
                        )
                );

                Paint.FontMetrics metrics =
                        tweenPaint.getFontMetrics();

                float y =
                        centreY
                                + rowOffsetPx
                                - (
                                metrics.ascent
                                        + metrics.descent
                        )
                                / 2.0f;

                canvas.drawText(
                        formatMinutes(
                                value
                        ),
                        width
                                / 2.0f,
                        y,
                        tweenPaint
                );
            }
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {
            float rowGap =
                    dp(
                            40
                    );

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelAllAnimations();

                    lastTouchY =
                            event.getY();

                    lastTouchEventTimeMs =
                            event.getEventTime();

                    lastVelocityPxPerMs =
                            0.0f;

                    return true;

                case MotionEvent.ACTION_MOVE:
                    float y =
                            event.getY();

                    float delta =
                            lastTouchY
                                    - y;

                    long eventTimeMs =
                            event.getEventTime();

                    long dtMs =
                            Math.max(
                                    1L,
                                    eventTimeMs
                                            - lastTouchEventTimeMs
                            );

                    lastTouchY =
                            y;

                    lastTouchEventTimeMs =
                            eventTimeMs;

                    float instantVelocityPxPerMs =
                            delta
                                    / (
                                    float
                                    ) dtMs;

                    /*
                     * Low-pass filter so the release velocity feels natural
                     * instead of jittery.
                     */
                    lastVelocityPxPerMs =
                            lerp(
                                    lastVelocityPxPerMs,
                                    instantVelocityPxPerMs,
                                    0.35f
                            );

                    dragOffsetPx +=
                            delta;

                    while (dragOffsetPx >= rowGap) {
                        stepMinutes(
                                -STEP_MINUTES
                        );

                        dragOffsetPx -=
                                rowGap;
                    }

                    while (dragOffsetPx <= -rowGap) {
                        stepMinutes(
                                STEP_MINUTES
                        );

                        dragOffsetPx +=
                                rowGap;
                    }

                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (Math.abs(
                            lastVelocityPxPerMs
                    ) >= dpFloat(
                            0.10f
                    )) {

                        startInertialGlide(
                                lastVelocityPxPerMs
                        );

                    } else {
                        settleToNearestStep();
                    }

                    performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    settleToNearestStep();
                    return true;

                default:
                    return true;
            }
        }

        private void settleToNearestStep() {
            float rowGap =
                    dp(
                            40
                    );

            if (dragOffsetPx >= rowGap / 2.0f) {
                stepMinutes(
                        -STEP_MINUTES
                );

                dragOffsetPx -=
                        rowGap;

            } else if (dragOffsetPx <= -rowGap / 2.0f) {
                stepMinutes(
                        STEP_MINUTES
                );

                dragOffsetPx +=
                        rowGap;
            }

            animateSnapToCentre();
        }


        private void startInertialGlide(
                float velocityPxPerMs
        ) {
            cancelInertialAnimation();

            float rowGap =
                    dp(
                            40
                    );

            float coastDistancePx =
                    clamp(
                            velocityPxPerMs
                                    * 180.0f,
                            -rowGap * 1.75f,
                            rowGap * 1.75f
                    );

            if (Math.abs(
                    coastDistancePx
            ) < dpFloat(
                    10.0f
            )) {

                settleToNearestStep();
                return;
            }

            final float[] lastAnimatedOffset =
                    new float[] {
                            dragOffsetPx
                    };

            inertialAnimator =
                    ValueAnimator.ofFloat(
                            dragOffsetPx,
                            dragOffsetPx
                                    + coastDistancePx
                    );

            inertialAnimator.setDuration(
                    240L
            );

            inertialAnimator.setInterpolator(
                    new DecelerateInterpolator(
                            1.35f
                    )
            );

            inertialAnimator.addUpdateListener(
                    animation -> {
                        float animatedOffset =
                                (float)
                                        animation.getAnimatedValue();

                        float delta =
                                animatedOffset
                                        - lastAnimatedOffset[0];

                        lastAnimatedOffset[0] =
                                animatedOffset;

                        dragOffsetPx +=
                                delta;

                        while (dragOffsetPx >= rowGap) {
                            stepMinutes(
                                    -STEP_MINUTES
                            );

                            dragOffsetPx -=
                                    rowGap;
                        }

                        while (dragOffsetPx <= -rowGap) {
                            stepMinutes(
                                    STEP_MINUTES
                            );

                            dragOffsetPx +=
                                    rowGap;
                        }

                        invalidate();
                    }
            );

            inertialAnimator.addListener(
                    new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(
                                android.animation.Animator animation
                        ) {
                            if (inertialAnimator
                                    != null) {

                                inertialAnimator =
                                        null;
                            }

                            settleToNearestStep();
                        }


                        @Override
                        public void onAnimationCancel(
                                android.animation.Animator animation
                        ) {
                            inertialAnimator =
                                    null;
                        }
                    }
            );

            inertialAnimator.start();
        }


        private void cancelAllAnimations() {
            cancelSnapAnimation();
            cancelInertialAnimation();
        }


        private void cancelInertialAnimation() {
            if (inertialAnimator == null) {
                return;
            }

            inertialAnimator.cancel();
            inertialAnimator =
                    null;
        }


        private float clamp(
                float value,
                float min,
                float max
        ) {
            return Math.max(
                    min,
                    Math.min(
                            max,
                            value
                    )
            );
        }


        private void stepMinutes(
                int deltaMinutes
        ) {
            minutes =
                    normalizeMinutes(
                            minutes
                                    + deltaMinutes
                    );

            if (listener != null) {
                listener.onTimeChanged(
                        minutes
                );
            }
        }


        private void animateSnapToCentre() {
            cancelSnapAnimation();

            if (Math.abs(
                    dragOffsetPx
            ) < 0.5f) {

                dragOffsetPx =
                        0.0f;

                invalidate();
                return;
            }

            snapAnimator =
                    ValueAnimator.ofFloat(
                            dragOffsetPx,
                            0.0f
                    );

            snapAnimator.setDuration(
                    190L
            );

            snapAnimator.setInterpolator(
                    new OvershootInterpolator(
                            0.72f
                    )
            );

            snapAnimator.addUpdateListener(
                    animation -> {
                        dragOffsetPx =
                                (float)
                                        animation.getAnimatedValue();

                        invalidate();
                    }
            );

            snapAnimator.start();
        }


        private void cancelSnapAnimation() {
            if (snapAnimator == null) {
                return;
            }

            snapAnimator.cancel();

            snapAnimator =
                    null;
        }


        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private float lerp(
                float from,
                float to,
                float amount
        ) {
            return from
                    + (
                    to
                            - from
            )
                    * amount;
        }


        private int blendColor(
                int from,
                int to,
                float amount
        ) {
            int red =
                    Math.round(
                            lerp(
                                    Color.red(
                                            from
                                    ),
                                    Color.red(
                                            to
                                    ),
                                    amount
                            )
                    );

            int green =
                    Math.round(
                            lerp(
                                    Color.green(
                                            from
                                    ),
                                    Color.green(
                                            to
                                    ),
                                    amount
                            )
                    );

            int blue =
                    Math.round(
                            lerp(
                                    Color.blue(
                                            from
                                    ),
                                    Color.blue(
                                            to
                                    ),
                                    amount
                            )
                    );

            return Color.rgb(
                    red,
                    green,
                    blue
            );
        }


        private float dpFloat(
                float value
        ) {
            return value
                    * getResources()
                    .getDisplayMetrics()
                    .density;
        }

    }

    private String formatMinutes(
            int value
    ) {
        int normalized =
                normalizeMinutes(
                        value
                );

        return String.format(
                Locale.GERMANY,
                "%02d:%02d",
                normalized / 60,
                normalized % 60
        );
    }

    private int normalizeMinutes(
            int value
    ) {
        int result =
                value
                        % (
                        24
                                * 60
                );

        if (result < 0) {
            result +=
                    24
                            * 60;
        }

        return result;
    }

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * context
                        .getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}

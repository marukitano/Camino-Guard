package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * CAMINO_INFO_PANEL_CHEVRON_NAV_V4
 * CAMINO_HUD_POLISH_STATS_ZORDER_V11
 *
 * Bottom HUD:
 *  - centered flat chevron on the upper edge
 *  - compass in the lower-right corner
 *  - same-size navigation button immediately left of the compass
 *
 * Chevron:
 *  down = hide panel
 *  up   = restore panel
 *
 * Navigation button:
 *  arrow = enter navigation follow mode
 *  M     = return to manual map mode
 */
final class CaminoInfoPanel extends FrameLayout {

    private static final float TAB_VISIBLE_HEIGHT_DP = 31.0f;

    private final ImageView compassView;
    private final TextView titleView;
    private final TextView stageView;
    private final TextView textView;
    private final ChevronView panelToggleView;
    private final NavigationButton navigationButton;

    private Runnable navigationAction;
    private boolean hidden;

    CaminoInfoPanel(Context context) {
        super(context);

        setClickable(true);
        setMinimumWidth(dpInt(260));

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.parseColor("#E63D332C")
        );

        background.setCornerRadius(
                dp(18.0f)
        );

        setBackground(background);

        titleView =
                new TextView(context);

        titleView.setTextColor(
                Color.WHITE
        );

        titleView.setTextSize(
                16.0f
        );

        titleView.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        titleView.setGravity(
                Gravity.CENTER
        );

        FrameLayout.LayoutParams titleParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        titleParams.topMargin =
                dpInt(25);

        titleParams.leftMargin =
                dpInt(12);

        titleParams.rightMargin =
                dpInt(12);

        addView(
                titleView,
                titleParams
        );

        stageView =
                new TextView(context);

        stageView.setTextColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        stageView.setTextSize(
                14.5f
        );

        stageView.setGravity(
                Gravity.CENTER
        );

        FrameLayout.LayoutParams stageParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        stageParams.topMargin =
                dpInt(48);

        stageParams.leftMargin =
                dpInt(12);

        stageParams.rightMargin =
                dpInt(12);

        addView(
                stageView,
                stageParams
        );

        textView =
                new TextView(context);

        textView.setTextColor(
                Color.WHITE
        );

        textView.setTextSize(
                14.5f
        );

        textView.setGravity(
                Gravity.START | Gravity.TOP
        );

        /*
         * Stats begin below title/stage. Right/bottom padding keeps the
         * navigation and compass controls clear.
         */
        textView.setPadding(
                dpInt(14),
                dpInt(72),
                dpInt(108),
                dpInt(44)
        );

        addView(
                textView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.START | Gravity.TOP
                )
        );

        panelToggleView =
                new ChevronView(
                        context,
                        ChevronView.DOWN
                );

        panelToggleView.setClickable(
                true
        );

        FrameLayout.LayoutParams toggleParams =
                new FrameLayout.LayoutParams(
                        dpInt(46),
                        dpInt(27),
                        Gravity.TOP
                                | Gravity.CENTER_HORIZONTAL
                );

        toggleParams.topMargin =
                dpInt(1);

        addView(
                panelToggleView,
                toggleParams
        );

        panelToggleView.setOnClickListener(
                view -> togglePanel()
        );

        compassView =
                new ImageView(
                        context
                );

        compassView.setScaleType(
                ImageView.ScaleType.CENTER_INSIDE
        );

        FrameLayout.LayoutParams compassParams =
                new FrameLayout.LayoutParams(
                        dpInt(40),
                        dpInt(40),
                        Gravity.END
                                | Gravity.BOTTOM
                );

        compassParams.rightMargin =
                dpInt(8);

        compassParams.bottomMargin =
                dpInt(7);

        addView(
                compassView,
                compassParams
        );

        navigationButton =
                new NavigationButton(
                        context
                );

        navigationButton.setClickable(
                true
        );

        FrameLayout.LayoutParams navigationParams =
                new FrameLayout.LayoutParams(
                        dpInt(40),
                        dpInt(40),
                        Gravity.END
                                | Gravity.BOTTOM
                );

        navigationParams.rightMargin =
                dpInt(56);

        navigationParams.bottomMargin =
                dpInt(7);

        addView(
                navigationButton,
                navigationParams
        );

        navigationButton.setOnClickListener(
                view -> {
                    if (navigationAction
                            != null) {

                        navigationAction.run();
                    }
                }
        );
    }

    TextView getTextView() {
        return textView;
    }

    void setTitle(
            String title
    ) {
        String value =
                title == null
                        ? ""
                        : title.trim();

        titleView.setText(
                value
        );

        titleView.setVisibility(
                value.isEmpty()
                        ? GONE
                        : VISIBLE
        );
    }

    void setStageText(
            String text
    ) {
        String value =
                text == null
                        ? ""
                        : text.trim();

        stageView.setText(
                value
        );

        stageView.setVisibility(
                value.isEmpty()
                        ? GONE
                        : VISIBLE
        );
    }

    void setCompassDrawable(
            Drawable drawable
    ) {
        if (drawable == null) {
            compassView.setVisibility(
                    GONE
            );

            return;
        }

        Drawable copy =
                drawable;

        if (drawable.getConstantState()
                != null) {

            copy =
                    drawable.getConstantState()
                            .newDrawable()
                            .mutate();
        }

        compassView.setImageDrawable(
                copy
        );

        compassView.setVisibility(
                VISIBLE
        );
    }

    void setBearing(
            double bearing
    ) {
        compassView.setRotation(
                (float)
                        -bearing
        );
    }

    void setNavigationAction(
            Runnable action
    ) {
        navigationAction =
                action;
    }

    void setNavigationFollowEnabled(
            boolean enabled
    ) {
        navigationButton.setFollowEnabled(
                enabled
        );
    }

    boolean isHidden() {
        return hidden;
    }

    private void togglePanel() {
        if (hidden) {
            showPanel();
        } else {
            hidePanel();
        }
    }

    void hidePanel() {
        if (hidden) {
            return;
        }

        hidden =
                true;

        panelToggleView.setDirection(
                ChevronView.UP
        );

        post(
                () -> {
                    float hiddenOffset =
                            Math.max(
                                    0.0f,
                                    getHeight()
                                            - dp(
                                            TAB_VISIBLE_HEIGHT_DP
                                    )
                            );

                    animate()
                            .translationY(
                                    hiddenOffset
                            )
                            .alpha(
                                    1.0f
                            )
                            .setDuration(
                                    180L
                            )
                            .start();
                }
        );
    }

    void showPanel() {
        if (!hidden) {
            return;
        }

        hidden =
                false;

        panelToggleView.setDirection(
                ChevronView.DOWN
        );

        animate()
                .translationY(
                        0.0f
                )
                .alpha(
                        1.0f
                )
                .setDuration(
                        180L
                )
                .start();
    }

    private int dpInt(
            int value
    ) {
        return Math.round(
                dp(
                        value
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

    private static final class ChevronView
            extends View {

        static final int DOWN = 0;
        static final int UP = 1;

        private final Paint paint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private int direction;

        ChevronView(
                Context context,
                int direction
        ) {
            super(
                    context
            );

            this.direction =
                    direction;

            paint.setColor(
                    Color.rgb(
                            255,
                            240,
                            200
                    )
            );

            paint.setStyle(
                    Paint.Style.STROKE
            );

            paint.setStrokeWidth(
                    dp(
                            2.2f
                    )
            );

            paint.setStrokeCap(
                    Paint.Cap.ROUND
            );

            paint.setStrokeJoin(
                    Paint.Join.ROUND
            );
        }

        void setDirection(
                int direction
        ) {
            this.direction =
                    direction;

            invalidate();
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {
            super.onDraw(
                    canvas
            );

            float cx =
                    getWidth()
                            / 2.0f;

            float cy =
                    getHeight()
                            / 2.0f;

            /*
             * Wide, shallow chevron: visually related to a V, but deliberately
             * less pointy and lighter than a literal text character.
             */
            float halfWidth =
                    dp(
                            9.5f
                    );

            float depth =
                    dp(
                            5.0f
                    );

            Path path =
                    new Path();

            if (direction == DOWN) {
                path.moveTo(
                        cx - halfWidth,
                        cy - depth
                );

                path.lineTo(
                        cx,
                        cy + depth
                );

                path.lineTo(
                        cx + halfWidth,
                        cy - depth
                );

            } else {
                path.moveTo(
                        cx - halfWidth,
                        cy + depth
                );

                path.lineTo(
                        cx,
                        cy - depth
                );

                path.lineTo(
                        cx + halfWidth,
                        cy + depth
                );
            }

            canvas.drawPath(
                    path,
                    paint
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
    }

    private static final class NavigationButton
            extends View {

        private final Paint circlePaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint outlinePaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint iconPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private final Paint textPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        private boolean followEnabled;

        NavigationButton(
                Context context
        ) {
            super(
                    context
            );

            circlePaint.setColor(
                    Color.argb(
                            46,
                            255,
                            240,
                            200
                    )
            );

            circlePaint.setStyle(
                    Paint.Style.FILL
            );

            outlinePaint.setColor(
                    Color.argb(
                            190,
                            255,
                            240,
                            200
                    )
            );

            outlinePaint.setStyle(
                    Paint.Style.STROKE
            );

            outlinePaint.setStrokeWidth(
                    dp(
                            1.3f
                    )
            );

            iconPaint.setColor(
                    Color.rgb(
                            255,
                            240,
                            200
                    )
            );

            iconPaint.setStyle(
                    Paint.Style.FILL
            );

            textPaint.setColor(
                    Color.rgb(
                            255,
                            240,
                            200
                    )
            );

            textPaint.setTextAlign(
                    Paint.Align.CENTER
            );

            textPaint.setTextSize(
                    sp(
                            15.0f
                    )
            );

            textPaint.setFakeBoldText(
                    true
            );
        }

        void setFollowEnabled(
                boolean enabled
        ) {
            followEnabled =
                    enabled;

            invalidate();
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {
            super.onDraw(
                    canvas
            );

            float cx =
                    getWidth()
                            / 2.0f;

            float cy =
                    getHeight()
                            / 2.0f;

            float radius =
                    Math.min(
                            getWidth(),
                            getHeight()
                    ) * 0.43f;

            canvas.drawCircle(
                    cx,
                    cy,
                    radius,
                    circlePaint
            );

            canvas.drawCircle(
                    cx,
                    cy,
                    radius,
                    outlinePaint
            );

            if (followEnabled) {
                Paint.FontMetrics metrics =
                        textPaint
                                .getFontMetrics();

                float baseline =
                        cy
                                - (
                                metrics.ascent
                                        + metrics.descent
                        ) / 2.0f;

                canvas.drawText(
                        "M",
                        cx,
                        baseline,
                        textPaint
                );

                return;
            }

            /*
             * Small navigation/course arrow matching the map's directional
             * language without reusing a font glyph.
             */
            float size =
                    radius
                            * 1.10f;

            Path arrow =
                    new Path();

            arrow.moveTo(
                    cx,
                    cy - size
            );

            arrow.lineTo(
                    cx + size * 0.64f,
                    cy + size * 0.72f
            );

            arrow.lineTo(
                    cx,
                    cy + size * 0.42f
            );

            arrow.lineTo(
                    cx - size * 0.64f,
                    cy + size * 0.72f
            );

            arrow.close();

            canvas.drawPath(
                    arrow,
                    iconPaint
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
}

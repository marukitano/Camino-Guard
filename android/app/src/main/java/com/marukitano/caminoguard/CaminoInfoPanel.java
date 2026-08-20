package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * CAMINO_INFO_PANEL_DYNAMIC_FLEX_VILLAGE_V13
 *
 * Native Android equivalent of a CSS flex layout:
 *
 * title
 *   own full-width row, centered, up to two lines
 *
 * summary
 *   both values present -> 50/50 left + right
 *   only one present    -> remaining value fills row and centers itself
 *
 * stats
 *   two vertical lists: altitude left, pace/navigation right
 *
 * controls
 *   navigation + compass in reserved bottom area
 */
final class CaminoInfoPanel extends FrameLayout {

    private static final float TAB_VISIBLE_HEIGHT_DP = 31.0f;

    private final ImageView compassView;
    private final TextView titleView;
    private final TextView summaryLeftView;
    private final TextView summaryRightView;
    private final TextView statsLeftView;
    private final TextView statsRightView;
    private final ChevronView panelToggleView;
    private final NavigationButton navigationButton;

    private Runnable navigationAction;
    private boolean hidden;

    CaminoInfoPanel(Context context) {
        super(context);

        setClickable(true);
        setMinimumWidth(dpInt(286));

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.parseColor("#E63D332C")
        );

        background.setCornerRadius(
                dp(18.0f)
        );

        setBackground(background);

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
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
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

        LinearLayout contentColumn =
                new LinearLayout(context);

        contentColumn.setOrientation(
                LinearLayout.VERTICAL
        );

        /*
         * Bottom padding is a dedicated control area. The longer right stats
         * list can therefore grow vertically without colliding with nav/compass.
         */
        contentColumn.setPadding(
                dpInt(14),
                dpInt(25),
                dpInt(14),
                dpInt(54)
        );

        FrameLayout.LayoutParams contentParams =
                new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.FILL_HORIZONTAL
                );

        addView(
                contentColumn,
                contentParams
        );

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

        titleView.setMaxLines(
                2
        );

        titleView.setEllipsize(
                TextUtils.TruncateAt.END
        );

        contentColumn.addView(
                titleView,
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                )
        );

        LinearLayout summaryRow =
                new LinearLayout(context);

        summaryRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        LinearLayout.LayoutParams summaryRowParams =
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                );

        summaryRowParams.topMargin =
                dpInt(7);

        contentColumn.addView(
                summaryRow,
                summaryRowParams
        );

        summaryLeftView =
                summaryTextView(
                        context
                );

        summaryRightView =
                summaryTextView(
                        context
                );

        summaryRow.addView(
                summaryLeftView,
                summaryCellParams()
        );

        summaryRow.addView(
                summaryRightView,
                summaryCellParams()
        );

        LinearLayout statsRow =
                new LinearLayout(context);

        statsRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        LinearLayout.LayoutParams statsRowParams =
                new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                );

        statsRowParams.topMargin =
                dpInt(10);

        contentColumn.addView(
                statsRow,
                statsRowParams
        );

        statsLeftView =
                statsTextView(
                        context
                );

        statsRightView =
                statsTextView(
                        context
                );

        statsRow.addView(
                statsLeftView,
                new LinearLayout.LayoutParams(
                        0,
                        LayoutParams.WRAP_CONTENT,
                        1.0f
                )
        );

        statsRow.addView(
                statsRightView,
                new LinearLayout.LayoutParams(
                        0,
                        LayoutParams.WRAP_CONTENT,
                        1.0f
                )
        );

        compassView =
                new ImageView(context);

        compassView.setScaleType(
                ImageView.ScaleType.CENTER_INSIDE
        );

        FrameLayout.LayoutParams compassParams =
                new FrameLayout.LayoutParams(
                        dpInt(40),
                        dpInt(40),
                        Gravity.END | Gravity.BOTTOM
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
                        Gravity.END | Gravity.BOTTOM
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
                    if (navigationAction != null) {
                        navigationAction.run();
                    }
                }
        );
    }

    private TextView summaryTextView(
            Context context
    ) {
        TextView view =
                new TextView(context);

        view.setTextColor(
                Color.rgb(
                        255,
                        240,
                        200
                )
        );

        view.setTextSize(
                14.2f
        );

        view.setGravity(
                Gravity.CENTER
        );

        view.setMaxLines(
                2
        );

        return view;
    }

    private LinearLayout.LayoutParams summaryCellParams() {
        return new LinearLayout.LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1.0f
        );
    }

    private TextView statsTextView(
            Context context
    ) {
        TextView view =
                new TextView(context);

        view.setTextColor(
                Color.WHITE
        );

        view.setTextSize(
                14.1f
        );

        /*
         * Both columns are actual vertical lists aligned to their own left
         * edge. Values are not scattered by END gravity anymore.
         */
        view.setGravity(
                Gravity.START | Gravity.TOP
        );

        return view;
    }

    TextView getTextView() {
        return statsLeftView;
    }

    void setTitle(
            String title
    ) {
        String value =
                clean(
                        title
                );

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
        setSummaryTexts(
                text,
                ""
        );
    }

    void setSummaryTexts(
            String left,
            String right
    ) {
        String leftValue =
                clean(
                        left
                );

        String rightValue =
                clean(
                        right
                );

        boolean hasLeft =
                !leftValue.isEmpty();

        boolean hasRight =
                !rightValue.isEmpty();

        summaryLeftView.setText(
                leftValue
        );

        summaryRightView.setText(
                rightValue
        );

        summaryLeftView.setVisibility(
                hasLeft
                        ? VISIBLE
                        : GONE
        );

        summaryRightView.setVisibility(
                hasRight
                        ? VISIBLE
                        : GONE
        );

        if (hasLeft && hasRight) {
            summaryLeftView.setGravity(
                    Gravity.START
            );

            summaryRightView.setGravity(
                    Gravity.END
            );

        } else if (hasLeft) {
            /*
             * Right cell is GONE; left retains weight=1 and therefore expands
             * to the full row exactly like a remaining CSS flex child.
             */
            summaryLeftView.setGravity(
                    Gravity.CENTER
            );

        } else if (hasRight) {
            summaryRightView.setGravity(
                    Gravity.CENTER
            );
        }
    }

    void setStatsTexts(
            String left,
            String right
    ) {
        statsLeftView.setText(
                clean(
                        left
                )
        );

        statsRightView.setText(
                clean(
                        right
                )
        );
    }

    private static String clean(
            String text
    ) {
        return text == null
                ? ""
                : text.trim();
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
                (float) -bearing
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
                dp(value)
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
            super(context);

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
                    dp(2.2f)
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

            float halfWidth =
                    dp(9.5f);

            float depth =
                    dp(5.0f);

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
                    dp(1.3f)
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
                    sp(15.0f)
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

            float size =
                    radius * 1.10f;

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

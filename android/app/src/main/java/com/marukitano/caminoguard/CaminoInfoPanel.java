package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
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
 *   one three-state navigation control in reserved bottom area
 */
final class CaminoInfoPanel extends FrameLayout {

    private static final float TAB_VISIBLE_HEIGHT_DP = 31.0f;

    private final TextView titleView;
    private final TextView summaryLeftView;
    private final TextView summaryRightView;
    private final TextView statsLeftView;
    private final TextView statsRightView;
    private final CaminoChevronView panelToggleView;
    private final CaminoNavigationButton navigationButton;
    private final CaminoAttributionButton attributionButton;
    private final CaminoSelectionLockButton selectionLockButton;

    private Runnable navigationAction;
    private Runnable attributionAction;
    private Runnable selectionLockAction;
    private boolean hidden;

    CaminoInfoPanel(Context context) {
        super(context);

        /*
         * The old bottom information card is temporarily disabled.
         * This view now acts only as a transparent host for the two map
         * controls that remain active: navigation mode + lock + attribution.
         */
        setClickable(false);
        setMinimumWidth(dpInt(48));
        setMinimumHeight(dpInt(144));

        setBackground(
                null
        );

        panelToggleView =
                new CaminoChevronView(
                        context,
                        CaminoChevronView.DOWN
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

        panelToggleView.setVisibility(
                GONE
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

        /*
         * Presenter/state logic may continue updating these views, but the
         * information screen itself is intentionally not rendered for now.
         */
        contentColumn.setVisibility(
                GONE
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

        navigationButton =
                new CaminoNavigationButton(
                        context
                );

        navigationButton.setClickable(
                true
        );

        FrameLayout.LayoutParams navigationParams =
                new FrameLayout.LayoutParams(
                        dpInt(40),
                        dpInt(40),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                );

        navigationParams.bottomMargin =
                dpInt(100);

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

        attributionButton =
                new CaminoAttributionButton(
                        context
                );

        attributionButton.setClickable(
                true
        );

        FrameLayout.LayoutParams attributionParams =
                new FrameLayout.LayoutParams(
                        dpInt(40),
                        dpInt(40),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                );

        attributionParams.bottomMargin =
                dpInt(4);

        addView(
                attributionButton,
                attributionParams
        );

        attributionButton.setOnClickListener(
                view -> {
                    if (attributionAction != null) {
                        attributionAction.run();
                    }
                }
        );

        selectionLockButton =
                new CaminoSelectionLockButton(
                        context
                );

        selectionLockButton.setClickable(
                true
        );

        FrameLayout.LayoutParams lockParams =
                new FrameLayout.LayoutParams(
                        dpInt(40),
                        dpInt(40),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                );

        lockParams.bottomMargin =
                dpInt(52);

        addView(
                selectionLockButton,
                lockParams
        );

        selectionLockButton.setOnClickListener(
                view -> {
                    if (selectionLockAction != null) {
                        selectionLockAction.run();
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
        navigationButton.setCompassDrawable(
                drawable
        );
    }

    void setBearing(
            double bearing
    ) {
        navigationButton.setMapBearing(
                bearing
        );
    }



    void setNavigationAction(
            Runnable action
    ) {
        navigationAction =
                action;
    }

    void setAttributionAction(
            Runnable action
    ) {
        attributionAction =
                action;
    }

    void setSelectionLockAction(
            Runnable action
    ) {
        selectionLockAction =
                action;
    }

    void setSelectionLocked(
            boolean locked
    ) {
        selectionLockButton.setLocked(
                locked
        );
    }

    void setSelectionLockAvailable(
            boolean available
    ) {
        selectionLockButton.setAvailable(
                available
        );
    }

    void setNavigationMode(
            NavigationController.Mode mode,
            boolean suspended
    ) {
        navigationButton.setMode(
                mode,
                suspended
        );
    }

    boolean isHidden() {
        return hidden;
    }

    void hidePanel() {
        if (hidden) {
            return;
        }

        hidden =
                true;

        panelToggleView.setDirection(
                CaminoChevronView.UP
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
                CaminoChevronView.DOWN
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
}

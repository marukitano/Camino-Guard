package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * Compact vertical planning start-time control.
 *
 * Only HH:mm receives the warm halo so it reads as an editable value.
 */
final class CaminoStartTimeButton extends View {

    private final Paint textPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint haloPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private int minutes;

    CaminoStartTimeButton(
            Context context
    ) {
        super(
                context
        );

        float textSizePx =
                13.5f
                        * getResources()
                        .getDisplayMetrics()
                        .scaledDensity;

        textPaint.setTextSize(
                textSizePx
        );

        textPaint.setTypeface(
                Typeface.create(
                        Typeface.MONOSPACE,
                        Typeface.NORMAL
                )
        );

        textPaint.setColor(
                Color.rgb(
                        35,
                        39,
                        43
                )
        );

        haloPaint.setStyle(
                Paint.Style.FILL
        );

        haloPaint.setColor(
                Color.argb(
                        145,
                        255,
                        221,
                        105
                )
        );

        setClickable(
                true
        );
    }

    void setMainColor(
            int color
    ) {
        textPaint.setColor(
                color
        );

        invalidate();
    }

    void setMinutes(
            int value
    ) {
        int normalized =
                normalizeMinutes(
                        value
                );

        if (minutes == normalized) {
            return;
        }

        minutes =
                normalized;

        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(
            int widthMeasureSpec,
            int heightMeasureSpec
    ) {
        Paint.FontMetrics metrics =
                textPaint.getFontMetrics();

        int textHeight =
                Math.max(
                        1,
                        Math.round(
                                metrics.descent
                                        - metrics.ascent
                        )
                );

        int normalWidth =
                Math.max(
                        1,
                        Math.round(
                                textPaint.measureText(
                                        fullLabel()
                                )
                        )
                );

        setMeasuredDimension(
                resolveSize(
                        Math.max(
                                textHeight
                                        + dp(
                                        8
                                ),
                                dp(
                                        30
                                )
                        ),
                        widthMeasureSpec
                ),
                resolveSize(
                        normalWidth
                                + dp(
                                8
                        ),
                        heightMeasureSpec
                )
        );
    }

    @Override
    protected void onDraw(
            Canvas canvas
    ) {
        super.onDraw(
                canvas
        );

        Paint.FontMetrics metrics =
                textPaint.getFontMetrics();

        float textHeight =
                metrics.descent
                        - metrics.ascent;

        /*
         * The Start control deliberately has a wider touch area than
         * VerticalStatsTextView. Gravity.END aligns the view bounds,
         * so compensate for that extra width when drawing the text.
         * This puts Start HH:mm on exactly the same visual right edge
         * as all other vertical statistic values.
         */
        float baseline =
                getWidth()
                        - textHeight
                        - metrics.ascent;

        int save =
                canvas.save();

        canvas.translate(
                0.0f,
                getHeight()
        );

        canvas.rotate(
                -90.0f
        );

        String prefix =
                "Start ";

        String time =
                timeLabel();

        float prefixWidth =
                textPaint.measureText(
                        prefix
                );

        float timeWidth =
                textPaint.measureText(
                        time
                );

        canvas.drawRoundRect(
                prefixWidth
                        - dp(
                        3
                ),
                baseline
                        + metrics.ascent
                        - dp(
                        3
                ),
                prefixWidth
                        + timeWidth
                        + dp(
                        3
                ),
                baseline
                        + metrics.descent
                        + dp(
                        3
                ),
                dp(
                        7
                ),
                dp(
                        7
                ),
                haloPaint
        );

        canvas.drawText(
                prefix,
                0.0f,
                baseline,
                textPaint
        );

        canvas.drawText(
                time,
                prefixWidth,
                baseline,
                textPaint
        );

        canvas.restoreToCount(
                save
        );
    }

    private String fullLabel() {
        return "Start "
                + timeLabel();
    }

    private String timeLabel() {
        int normalized =
                normalizeMinutes(
                        minutes
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
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}

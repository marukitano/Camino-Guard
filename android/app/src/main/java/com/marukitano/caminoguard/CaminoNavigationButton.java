package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Drawn navigation/follow control used by CaminoInfoPanel. */
final class CaminoNavigationButton extends View {

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

    CaminoNavigationButton(
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

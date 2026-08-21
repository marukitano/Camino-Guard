package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Drawn chevron control used by CaminoInfoPanel. */
final class CaminoChevronView extends View {

    static final int DOWN = 0;
    static final int UP = 1;

    private final Paint paint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private int direction;

    CaminoChevronView(
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

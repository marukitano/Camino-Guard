package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Compact attribution / info control.
 *
 * The glyph is drawn as a stable vector instead of using a font so its
 * rounded italic-serif appearance stays identical across Android devices.
 */
final class CaminoAttributionButton extends View {

    private final Paint backgroundPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint borderPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private final Paint glyphPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    CaminoAttributionButton(
            Context context
    ) {
        super(
                context
        );

        backgroundPaint.setColor(
                Color.argb(
                        165,
                        35,
                        39,
                        43
                )
        );

        backgroundPaint.setStyle(
                Paint.Style.FILL
        );

        borderPaint.setColor(
                Color.argb(
                        185,
                        255,
                        255,
                        255
                )
        );

        borderPaint.setStyle(
                Paint.Style.STROKE
        );

        borderPaint.setStrokeWidth(
                dp(
                        1.0f
                )
        );

        glyphPaint.setColor(
                Color.rgb(
                        238,
                        238,
                        238
                )
        );

        glyphPaint.setStyle(
                Paint.Style.FILL
        );
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
                )
                        / 2.0f
                        - dp(
                        1.0f
                );

        canvas.drawCircle(
                cx,
                cy,
                radius,
                backgroundPaint
        );

        canvas.drawCircle(
                cx,
                cy,
                radius,
                borderPaint
        );

        /*
         * v99c:
         * - whole glyph scaled to 60% => 40% smaller
         * - optical centre corrected so the symbol sits properly
         */
        float glyphCx =
                cx;

        float glyphCy =
                cy
                        - dp(
                        2.6f
                );

        int glyphSave =
                canvas.save();

        canvas.scale(
                0.60f,
                0.60f,
                glyphCx,
                glyphCy
        );

        canvas.drawCircle(
                glyphCx
                        + dp(
                        0.6f
                ),
                glyphCy
                        - dp(
                        8.1f
                ),
                dp(
                        3.0f
                ),
                glyphPaint
        );

        Path body =
                new Path();

        body.moveTo(
                glyphCx
                        - dp(
                        5.6f
                ),
                glyphCy
                        - dp(
                        2.8f
                )
        );

        body.cubicTo(
                glyphCx
                        - dp(
                        2.6f
                ),
                glyphCy
                        - dp(
                        4.3f
                ),
                glyphCx
                        + dp(
                        2.8f
                ),
                glyphCy
                        - dp(
                        4.4f
                ),
                glyphCx
                        + dp(
                        4.0f
                ),
                glyphCy
                        - dp(
                        1.4f
                )
        );

        body.cubicTo(
                glyphCx
                        + dp(
                        5.2f
                ),
                glyphCy
                        + dp(
                        1.6f
                ),
                glyphCx
                        + dp(
                        1.0f
                ),
                glyphCy
                        + dp(
                        7.6f
                ),
                glyphCx
                        + dp(
                        0.5f
                ),
                glyphCy
                        + dp(
                        10.7f
                )
        );

        body.cubicTo(
                glyphCx
                        + dp(
                        0.1f
                ),
                glyphCy
                        + dp(
                        13.1f
                ),
                glyphCx
                        + dp(
                        2.4f
                ),
                glyphCy
                        + dp(
                        13.3f
                ),
                glyphCx
                        + dp(
                        6.1f
                ),
                glyphCy
                        + dp(
                        11.8f
                )
        );

        body.lineTo(
                glyphCx
                        + dp(
                        5.5f
                ),
                glyphCy
                        + dp(
                        15.0f
                )
        );

        body.cubicTo(
                glyphCx
                        + dp(
                        1.2f
                ),
                glyphCy
                        + dp(
                        16.8f
                ),
                glyphCx
                        - dp(
                        4.1f
                ),
                glyphCy
                        + dp(
                        16.1f
                ),
                glyphCx
                        - dp(
                        4.6f
                ),
                glyphCy
                        + dp(
                        12.2f
                )
        );

        body.cubicTo(
                glyphCx
                        - dp(
                        5.0f
                ),
                glyphCy
                        + dp(
                        9.6f
                ),
                glyphCx
                        - dp(
                        1.2f
                ),
                glyphCy
                        + dp(
                        3.4f
                ),
                glyphCx
                        - dp(
                        1.5f
                ),
                glyphCy
                        + dp(
                        0.4f
                )
        );

        body.cubicTo(
                glyphCx
                        - dp(
                        1.8f
                ),
                glyphCy
                        - dp(
                        1.5f
                ),
                glyphCx
                        - dp(
                        3.4f
                ),
                glyphCy
                        - dp(
                        1.9f
                ),
                glyphCx
                        - dp(
                        6.4f
                ),
                glyphCy
                        - dp(
                        1.0f
                )
        );

        body.close();

        canvas.drawPath(
                body,
                glyphPaint
        );

        canvas.restoreToCount(
                glyphSave
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

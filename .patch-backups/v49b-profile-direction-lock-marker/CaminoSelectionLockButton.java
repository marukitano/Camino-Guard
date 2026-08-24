package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Compact 40dp selection lock for the bottom-left control stack. */
final class CaminoSelectionLockButton extends View {

    private final Paint backgroundPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint borderPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint lockPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean locked;
    private boolean available;

    CaminoSelectionLockButton(Context context) {
        super(context);

        backgroundPaint.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.0f));

        lockPaint.setColor(Color.WHITE);
        lockPaint.setStrokeCap(Paint.Cap.ROUND);
        lockPaint.setStrokeJoin(Paint.Join.ROUND);

        setAvailable(false);
    }

    void setLocked(boolean locked) {
        if (this.locked == locked) {
            return;
        }

        this.locked = locked;
        invalidate();
    }

    void setAvailable(boolean available) {
        if (this.available == available) {
            return;
        }

        this.available = available;
        setAlpha(available ? 1.0f : 0.42f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        float radius = Math.min(getWidth(), getHeight()) / 2.0f - dp(1.0f);

        backgroundPaint.setColor(
                Color.argb(
                        locked ? 215 : 165,
                        35,
                        39,
                        43
                )
        );

        canvas.drawCircle(cx, cy, radius, backgroundPaint);

        borderPaint.setColor(
                Color.argb(
                        locked ? 235 : 185,
                        255,
                        255,
                        255
                )
        );

        canvas.drawCircle(cx, cy, radius, borderPaint);

        float bodyWidth = dp(14.0f);
        float bodyHeight = dp(11.0f);
        float bodyLeft = cx - bodyWidth / 2.0f;
        float bodyTop = cy - dp(0.5f);

        RectF body =
                new RectF(
                        bodyLeft,
                        bodyTop,
                        bodyLeft + bodyWidth,
                        bodyTop + bodyHeight
                );

        lockPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(body, dp(2.0f), dp(2.0f), lockPaint);

        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(dp(2.3f));

        RectF shackle =
                new RectF(
                        cx - dp(5.0f),
                        cy - dp(8.0f),
                        cx + dp(5.0f),
                        cy + dp(3.0f)
                );

        if (locked) {
            canvas.drawArc(shackle, 180.0f, -180.0f, false, lockPaint);
        } else {
            RectF openShackle =
                    new RectF(
                            shackle.left,
                            shackle.top - dp(2.0f),
                            shackle.right,
                            shackle.bottom - dp(2.0f)
                    );

            canvas.drawArc(openShackle, 180.0f, -135.0f, false, lockPaint);
        }

        lockPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(
                cx,
                bodyTop + bodyHeight * 0.48f,
                dp(1.25f),
                backgroundPaint
        );
    }

    private float dp(float value) {
        return value
                * getResources()
                .getDisplayMetrics()
                .density;
    }
}

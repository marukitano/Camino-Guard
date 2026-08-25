package com.marukitano.caminoguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * One map/navigation control whose icon always represents the CURRENT state:
 *
 * MANUAL    -> compass
 * NORTH_UP  -> direction arrow + N
 * COURSE_UP -> direction arrow
 */
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

    private NavigationController.Mode mode =
            NavigationController.Mode.MANUAL;

    private Drawable compassDrawable;
    private double mapBearing;

    CaminoNavigationButton(
            Context context
    ) {
        super(
                context
        );

        circlePaint.setColor(
                Color.WHITE
        );

        circlePaint.setStyle(
                Paint.Style.FILL
        );

        outlinePaint.setColor(
                Color.argb(
                        190,
                        61,
                        51,
                        44
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
                        61,
                        51,
                        44
                )
        );

        iconPaint.setStyle(
                Paint.Style.FILL
        );

        textPaint.setColor(
                Color.rgb(
                        61,
                        51,
                        44
                )
        );

        textPaint.setTextAlign(
                Paint.Align.CENTER
        );

        textPaint.setFakeBoldText(
                true
        );
    }

    void setMode(
            NavigationController.Mode mode
    ) {
        this.mode =
                mode == null
                        ? NavigationController.Mode.MANUAL
                        : mode;

        invalidate();
    }

    void setCompassDrawable(
            Drawable drawable
    ) {
        if (drawable == null) {
            compassDrawable =
                    null;

            invalidate();
            return;
        }

        Drawable copy =
                drawable;

        if (drawable.getConstantState()
                != null) {

            copy =
                    drawable
                            .getConstantState()
                            .newDrawable()
                            .mutate();
        }

        compassDrawable =
                copy;

        invalidate();
    }

    void setMapBearing(
            double bearing
    ) {
        mapBearing =
                Double.isFinite(
                        bearing
                )
                        ? bearing
                        : 0.0;

        if (mode
                == NavigationController.Mode.MANUAL) {

            invalidate();
        }
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
                        * 0.43f;

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

        if (mode
                == NavigationController.Mode.MANUAL) {

            drawCompass(
                    canvas,
                    cx,
                    cy,
                    radius
            );

            return;
        }

        if (mode
                == NavigationController.Mode.NORTH_UP) {

            drawNorthUp(
                    canvas,
                    cx,
                    cy,
                    radius
            );

            return;
        }

        drawDirectionArrow(
                canvas,
                cx,
                cy,
                radius
                        * 1.10f
        );
    }

    private void drawCompass(
            Canvas canvas,
            float cx,
            float cy,
            float radius
    ) {
        if (compassDrawable != null) {
            int half =
                    Math.round(
                            radius
                                    * 0.74f
                    );

            Rect bounds =
                    new Rect(
                            Math.round(cx) - half,
                            Math.round(cy) - half,
                            Math.round(cx) + half,
                            Math.round(cy) + half
                    );

            compassDrawable.setBounds(
                    bounds
            );

            int save =
                    canvas.save();

            /*
             * Same rotation semantics as the former standalone compass view.
             */
            canvas.rotate(
                    (float)
                            -mapBearing,
                    cx,
                    cy
            );

            compassDrawable.draw(
                    canvas
            );

            canvas.restoreToCount(
                    save
            );

            return;
        }

        textPaint.setTextSize(
                sp(
                        10.5f
                )
        );

        canvas.drawText(
                "N",
                cx,
                cy
                        - radius
                        * 0.34f,
                textPaint
        );

        Path needle =
                new Path();

        needle.moveTo(
                cx,
                cy
                        - radius
                        * 0.58f
        );

        needle.lineTo(
                cx
                        + radius
                        * 0.20f,
                cy
                        + radius
                        * 0.40f
        );

        needle.lineTo(
                cx,
                cy
                        + radius
                        * 0.18f
        );

        needle.lineTo(
                cx
                        - radius
                        * 0.20f,
                cy
                        + radius
                        * 0.40f
        );

        needle.close();

        int save =
                canvas.save();

        canvas.rotate(
                (float)
                        -mapBearing,
                cx,
                cy
        );

        canvas.drawPath(
                needle,
                iconPaint
        );

        canvas.restoreToCount(
                save
        );
    }

    private void drawNorthUp(
            Canvas canvas,
            float cx,
            float cy,
            float radius
    ) {
        textPaint.setTextSize(
                sp(
                        9.5f
                )
        );

        Paint.FontMetrics metrics =
                textPaint.getFontMetrics();

        float baseline =
                cy
                        - radius
                        * 0.43f
                        - (
                        metrics.ascent
                                + metrics.descent
                )
                        / 2.0f;

        canvas.drawText(
                "N",
                cx,
                baseline,
                textPaint
        );

        drawDirectionArrow(
                canvas,
                cx,
                cy
                        + radius
                        * 0.20f,
                radius
                        * 0.72f
        );
    }

    private void drawDirectionArrow(
            Canvas canvas,
            float cx,
            float cy,
            float size
    ) {
        Path arrow =
                new Path();

        arrow.moveTo(
                cx,
                cy
                        - size
        );

        arrow.lineTo(
                cx
                        + size
                        * 0.64f,
                cy
                        + size
                        * 0.72f
        );

        arrow.lineTo(
                cx,
                cy
                        + size
                        * 0.42f
        );

        arrow.lineTo(
                cx
                        - size
                        * 0.64f,
                cy
                        + size
                        * 0.72f
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

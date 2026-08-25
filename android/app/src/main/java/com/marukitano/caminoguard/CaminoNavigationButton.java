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

    private final Paint reticlePaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private NavigationController.Mode mode =
            NavigationController.Mode.MANUAL;

    private boolean suspended;

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

        reticlePaint.setColor(
                Color.rgb(
                        61,
                        51,
                        44
                )
        );

        reticlePaint.setStyle(
                Paint.Style.STROKE
        );

        reticlePaint.setStrokeWidth(
                dp(
                        1.6f
                )
        );

        reticlePaint.setStrokeCap(
                Paint.Cap.ROUND
        );
    }

    void setMode(
            NavigationController.Mode mode,
            boolean suspended
    ) {
        this.mode =
                mode == null
                        ? NavigationController.Mode.MANUAL
                        : mode;

        this.suspended =
                this.mode
                        != NavigationController.Mode.MANUAL
                        && suspended;

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

        if (mode
                == NavigationController.Mode.MANUAL) {

            /*
             * Exactly the old visual idea: the native MapLibre compass drawable
             * by itself, with no extra v88 circle around it.
             */
            drawCompass(
                    canvas,
                    cx,
                    cy,
                    radius
            );

            return;
        }

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

        if (suspended) {
            drawRecenterReticle(
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
                        * 0.88f
        );
    }

    private void drawCompass(
            Canvas canvas,
            float cx,
            float cy,
            float radius
    ) {
        if (compassDrawable != null) {
            int intrinsicWidth =
                    compassDrawable.getIntrinsicWidth();

            int intrinsicHeight =
                    compassDrawable.getIntrinsicHeight();

            int availableWidth =
                    Math.max(
                            1,
                            getWidth()
                    );

            int availableHeight =
                    Math.max(
                            1,
                            getHeight()
                    );

            int drawWidth =
                    availableWidth;

            int drawHeight =
                    availableHeight;

            /*
             * Match ImageView.ScaleType.CENTER_INSIDE from the pre-v88 compass:
             * preserve aspect ratio and never upscale a smaller native drawable.
             */
            if (intrinsicWidth > 0
                    && intrinsicHeight > 0) {

                float scale =
                        Math.min(
                                1.0f,
                                Math.min(
                                        availableWidth
                                                / (float) intrinsicWidth,
                                        availableHeight
                                                / (float) intrinsicHeight
                                )
                        );

                drawWidth =
                        Math.max(
                                1,
                                Math.round(
                                        intrinsicWidth
                                                * scale
                                )
                        );

                drawHeight =
                        Math.max(
                                1,
                                Math.round(
                                        intrinsicHeight
                                                * scale
                                )
                        );
            }

            int left =
                    Math.round(
                            cx
                                    - drawWidth
                                    / 2.0f
                    );

            int top =
                    Math.round(
                            cy
                                    - drawHeight
                                    / 2.0f
                    );

            compassDrawable.setBounds(
                    new Rect(
                            left,
                            top,
                            left
                                    + drawWidth,
                            top
                                    + drawHeight
                    )
            );

            int save =
                    canvas.save();

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

        /*
         * Only a fallback for an unavailable MapLibre compass drawable.
         */
        textPaint.setTextSize(
                sp(
                        10.0f
                )
        );

        canvas.drawText(
                "N",
                cx,
                cy
                        - radius
                        * 0.28f,
                textPaint
        );

        Path needle =
                new Path();

        needle.moveTo(
                cx,
                cy
                        - radius
                        * 0.55f
        );

        needle.lineTo(
                cx
                        + radius
                        * 0.18f,
                cy
                        + radius
                        * 0.38f
        );

        needle.lineTo(
                cx,
                cy
                        + radius
                        * 0.15f
        );

        needle.lineTo(
                cx
                        - radius
                        * 0.18f,
                cy
                        + radius
                        * 0.38f
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

    private void drawRecenterReticle(
            Canvas canvas,
            float cx,
            float cy,
            float radius
    ) {
        float ring =
                radius
                        * 0.34f;

        float inner =
                radius
                        * 0.50f;

        float outer =
                radius
                        * 0.76f;

        canvas.drawCircle(
                cx,
                cy,
                ring,
                reticlePaint
        );

        canvas.drawLine(
                cx,
                cy - outer,
                cx,
                cy - inner,
                reticlePaint
        );

        canvas.drawLine(
                cx,
                cy + inner,
                cx,
                cy + outer,
                reticlePaint
        );

        canvas.drawLine(
                cx - outer,
                cy,
                cx - inner,
                cy,
                reticlePaint
        );

        canvas.drawLine(
                cx + inner,
                cy,
                cx + outer,
                cy,
                reticlePaint
        );

        canvas.drawCircle(
                cx,
                cy,
                dp(
                        1.2f
                ),
                iconPaint
        );
    }

    private void drawNorthUp(
            Canvas canvas,
            float cx,
            float cy,
            float radius
    ) {
        /*
         * Keep N and arrow visually separate. Both are intentionally smaller
         * than v88 so they cannot overlap inside the 40dp control.
         */
        textPaint.setTextSize(
                sp(
                        8.0f
                )
        );

        Paint.FontMetrics metrics =
                textPaint.getFontMetrics();

        float baseline =
                cy
                        - radius
                        * 0.48f
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
                        * 0.25f,
                radius
                        * 0.48f
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

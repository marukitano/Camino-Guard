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


    private final Paint haloPaint =
            new Paint(
                    Paint.ANTI_ALIAS_FLAG
            );

    private NavigationController.Mode mode =
            NavigationController.Mode.MANUAL;

    private boolean suspended;
    private boolean rotationResetHalo;

    private double mapBearing;

    CaminoNavigationButton(
            Context context
    ) {
        super(
                context
        );

        circlePaint.setColor(
                Color.argb(
                        165,
                        35,
                        39,
                        43
                )
        );

        circlePaint.setStyle(
                Paint.Style.FILL
        );

        outlinePaint.setColor(
                Color.argb(
                        185,
                        255,
                        255,
                        255
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
                Color.WHITE
        );

        iconPaint.setStyle(
                Paint.Style.FILL
        );

        textPaint.setColor(
                Color.WHITE
        );

        textPaint.setTextAlign(
                Paint.Align.CENTER
        );

        textPaint.setFakeBoldText(
                true
        );

        reticlePaint.setColor(
                Color.WHITE
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


        haloPaint.setColor(
                Color.rgb(
                        74,
                        144,
                        226
                )
        );

        haloPaint.setStyle(
                Paint.Style.STROKE
        );

        /*
         * Deliberately raw pixels: requested visual halo is 3 px.
         */
        haloPaint.setStrokeWidth(
                3.0f
        );

        haloPaint.setStrokeCap(
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

    void setRotationResetHalo(
            boolean visible
    ) {
        rotationResetHalo =
                visible;

        invalidate();
    }


    void setCompassDrawable(
            Drawable drawable
    ) {
        /*
         * API bleibt bestehen; gezeichnet wird absichtlich unser
         * stabiles eigenes Kompasssymbol.
         */
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

        /*
         * All three map controls share the same quiet dark-grey base.
         * Navigation state is communicated only by the symbol, not by changing
         * the whole button from black/white to dark/light.
         */
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


        if (rotationResetHalo) {
            canvas.drawCircle(
                    cx,
                    cy,
                    radius + 2.5f,
                    haloPaint
            );
        }

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
                        * 0.72f
        );
    }

    private void drawCompass(
            Canvas canvas,
            float cx,
            float cy,
            float radius
    ) {
        /*
         * Classic compass needle:
         * north = red acute triangle
         * south = white acute triangle
         */
        int save =
                canvas.save();

        canvas.rotate(
                (float) -mapBearing,
                cx,
                cy
        );

        float tipDistance =
                radius
                        * 0.62f;

        float halfWidth =
                radius
                        * 0.16f;

        Path north =
                new Path();

        north.moveTo(
                cx,
                cy - tipDistance
        );

        north.lineTo(
                cx + halfWidth,
                cy
        );

        north.lineTo(
                cx - halfWidth,
                cy
        );

        north.close();

        Paint northPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        northPaint.setStyle(
                Paint.Style.FILL
        );

        northPaint.setColor(
                Color.rgb(
                        210,
                        52,
                        52
                )
        );

        canvas.drawPath(
                north,
                northPaint
        );

        Path south =
                new Path();

        south.moveTo(
                cx,
                cy + tipDistance
        );

        south.lineTo(
                cx + halfWidth,
                cy
        );

        south.lineTo(
                cx - halfWidth,
                cy
        );

        south.close();

        Paint southPaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        southPaint.setStyle(
                Paint.Style.FILL
        );

        southPaint.setColor(
                Color.WHITE
        );

        canvas.drawPath(
                south,
                southPaint
        );

        Paint centrePaint =
                new Paint(
                        Paint.ANTI_ALIAS_FLAG
                );

        centrePaint.setStyle(
                Paint.Style.FILL
        );

        centrePaint.setColor(
                Color.rgb(
                        35,
                        39,
                        43
                )
        );

        canvas.drawCircle(
                cx,
                cy,
                dp(1.25f),
                centrePaint
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

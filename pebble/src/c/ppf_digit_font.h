#pragma once

#include <pebble.h>

#define PPF_VALUE_HEIGHT 21
#define PPF_SMALL_HEIGHT 11

/*
 * Camino Guard's dashboard icons all use the same 20x20 icon box. Keep their
 * internal drawing code unchanged, but shift that shared box 4 px left so the
 * whole icon column sits visually centered over the metric bars.
 *
 * This wrapper only affects the exact dashboard icon rectangle shape
 * (x=8, 20x20); every other GRect in the watchapp is passed through unchanged.
 */
static inline GRect camino_guard_rect(int16_t x, int16_t y, int16_t w, int16_t h) {
    if (x == 8 && w == 20 && h == 20) {
        x = 4;
    }
    return (GRect){{x, y}, {w, h}};
}

#ifdef GRect
#undef GRect
#endif
#define GRect(x, y, w, h) camino_guard_rect((x), (y), (w), (h))

int ppf_value_width(const char *text);
void ppf_draw_value(GContext *ctx, const char *text, int right_x, int y, GColor color);
int ppf_small_value_width(const char *text);
void ppf_draw_small_value_centered(GContext *ctx, const char *text, GRect box, GColor color);

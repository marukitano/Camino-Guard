#pragma once

#include <pebble.h>

/*
 * main.c deliberately keeps several tiny embedded rendering helpers compact.
 * GCC's -Wmisleading-indentation flags those same-line statements even though
 * their control flow is explicit. The Pebble SDK promotes warnings to errors,
 * so silence this formatting-only diagnostic for translation units that use
 * the shared PPF helpers. Other compiler warnings remain enabled.
 */
#if defined(__GNUC__)
#pragma GCC diagnostic ignored "-Wmisleading-indentation"
#endif

#define PPF_VALUE_HEIGHT 21
#define PPF_DEGREE_SYMBOL_WIDTH 6
#define PPF_PERCENT_SYMBOL_WIDTH 17

int ppf_value_width(const char *text);
void ppf_draw_value(GContext *ctx, const char *text, int right_x, int y, GColor color);
void ppf_draw_degree_symbol(GContext *ctx, int x, int y, GColor color);
void ppf_draw_percent_symbol(GContext *ctx, int x, int y, GColor color);

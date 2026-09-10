#pragma once

#include <pebble.h>

#define PPF_VALUE_HEIGHT 21
#define PPF_SMALL_HEIGHT 11

int ppf_value_width(const char *text);
void ppf_draw_value(GContext *ctx, const char *text, int right_x, int y, GColor color);
int ppf_small_value_width(const char *text);
void ppf_draw_small_value_centered(GContext *ctx, const char *text, GRect box, GColor color);

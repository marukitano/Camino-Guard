#pragma once

#include <pebble.h>

#define PPF_VALUE_HEIGHT 21

int ppf_value_width(const char *text);
void ppf_draw_value(GContext *ctx, const char *text, int right_x, int y, GColor color);

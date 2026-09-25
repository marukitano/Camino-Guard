#include "ppf_digit_font.h"

#include <string.h>

#define DIGIT_WIDTH 24
#define DIGIT_HEIGHT 21
#define DIGIT_SPACING 2
#define DOT_WIDTH 4
#define DASH_WIDTH 12
#define COLON_WIDTH 4

static const uint32_t DIGITS[10][DIGIT_HEIGHT] = {
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xFFFC00u,0xFFFC00u,0xFFFC00u,0xFFFC00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0x003C00u,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF00000u,0xF00000u,0xF00000u,0xF00000u,0xF00000u,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0x3FFFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00FFFFu,0x00FFFFu,0x00FFFFu,0x00FFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x3FFFFFu,0x3FFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF00000u,0xF00000u,0xF00000u,0xF00000u,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF00000u,0xF00000u,0xF00000u,0xF00000u,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0x7FFFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu},
  {0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xF0000Fu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0xFFFFFFu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu,0x00000Fu}
};

static int char_width(char c) {
  if (c >= '0' && c <= '9') return DIGIT_WIDTH;
  if (c == '.' || c == ',') return DOT_WIDTH;
  if (c == ':') return COLON_WIDTH;
  if (c == '-') return DASH_WIDTH;
  return 0;
}

int ppf_value_width(const char *text) {
  if (!text || !*text) return 0;
  int width = 0;
  bool first = true;
  for (const char *p = text; *p; ++p) {
    int w = char_width(*p);
    if (!w) continue;
    if (!first) width += DIGIT_SPACING;
    width += w;
    first = false;
  }
  return width;
}

static void draw_digit(GContext *ctx, int digit, int x, int y) {
  for (int row = 0; row < DIGIT_HEIGHT; ++row) {
    uint32_t bits = DIGITS[digit][row];
    int run = -1;
    for (int col = 0; col <= DIGIT_WIDTH; ++col) {
      bool set = col < DIGIT_WIDTH && (bits & (1u << (DIGIT_WIDTH - 1 - col)));
      if (set && run < 0) run = col;
      if (!set && run >= 0) {
        graphics_fill_rect(ctx, GRect(x + run, y + row, col - run, 1), 0, GCornerNone);
        run = -1;
      }
    }
  }
}

void ppf_draw_value(GContext *ctx, const char *text, int right_x, int y, GColor color) {
  if (!ctx || !text) return;
  graphics_context_set_fill_color(ctx, color);
  int x = right_x - ppf_value_width(text);
  bool first = true;
  for (const char *p = text; *p; ++p) {
    int w = char_width(*p);
    if (!w) continue;
    if (!first) x += DIGIT_SPACING;
    if (*p >= '0' && *p <= '9') {
      draw_digit(ctx, *p - '0', x, y);
    } else if (*p == '.' || *p == ',') {
      graphics_fill_rect(ctx, GRect(x, y + DIGIT_HEIGHT - 4, DOT_WIDTH, 4), 0, GCornerNone);
    } else if (*p == ':') {
      graphics_fill_rect(ctx, GRect(x, y + 5, COLON_WIDTH, 4), 0, GCornerNone);
      graphics_fill_rect(ctx, GRect(x, y + 13, COLON_WIDTH, 4), 0, GCornerNone);
    } else if (*p == '-') {
      graphics_fill_rect(ctx, GRect(x, y + 9, DASH_WIDTH, 4), 0, GCornerNone);
    }
    x += w;
    first = false;
  }
}

static const uint8_t DEGREE_ROWS[6] = {
  0x3Fu, 0x3Fu, 0x33u, 0x33u, 0x3Fu, 0x3Fu
};

void ppf_draw_degree_symbol(GContext *ctx, int x, int y, GColor color) {
  if (!ctx) return;
  graphics_context_set_fill_color(ctx, color);
  for (int row = 0; row < 6; ++row) {
    int run = -1;
    for (int col = 0; col <= 6; ++col) {
      bool set = col < 6 && (DEGREE_ROWS[row] & (1u << (5 - col)));
      if (set && run < 0) run = col;
      if (!set && run >= 0) {
        graphics_fill_rect(ctx, GRect(x + run, y + row, col - run, 1), 0, GCornerNone);
        run = -1;
      }
    }
  }
}

void ppf_draw_percent_symbol(GContext *ctx, int x, int y, GColor color) {
  if (!ctx) return;
  ppf_draw_degree_symbol(ctx, x, y + 1, color);
  ppf_draw_degree_symbol(ctx, x + 11, y + 14, color);
  graphics_context_set_stroke_color(ctx, color);
  graphics_context_set_stroke_width(ctx, 2);
  graphics_draw_line(ctx, GPoint(x + 5, y + 18), GPoint(x + 12, y + 3));
  graphics_context_set_stroke_width(ctx, 1);
}

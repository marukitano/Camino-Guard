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

static int small_char_width(char c) {
  int w = char_width(c);
  return w ? (w + 1) / 2 : 0;
}

int ppf_small_value_width(const char *text) {
  if (!text || !*text) return 0;
  int width = 0;
  bool first = true;
  for (const char *p = text; *p; ++p) {
    int w = small_char_width(*p);
    if (!w) continue;
    if (!first) width += 1;
    width += w;
    first = false;
  }
  return width;
}

static void draw_small_digit(GContext *ctx, int digit, int x, int y) {
  for (int sy = 0; sy < PPF_SMALL_HEIGHT; ++sy) {
    int src_y = sy * 2;
    if (src_y >= DIGIT_HEIGHT) src_y = DIGIT_HEIGHT - 1;
    uint32_t bits = DIGITS[digit][src_y];
    for (int sx = 0; sx < 12; ++sx) {
      int src_x = sx * 2;
      if (bits & (1u << (DIGIT_WIDTH - 1 - src_x))) {
        graphics_draw_pixel(ctx, GPoint(x + sx, y + sy));
      }
    }
  }
}

void ppf_draw_small_value_centered(GContext *ctx, const char *text, GRect box, GColor color) {
  if (!ctx || !text) return;
  graphics_context_set_fill_color(ctx, color);
  graphics_context_set_stroke_color(ctx, color);
  int x = box.origin.x + (box.size.w - ppf_small_value_width(text)) / 2;
  int y = box.origin.y + (box.size.h - PPF_SMALL_HEIGHT) / 2;
  bool first = true;
  for (const char *p = text; *p; ++p) {
    int w = small_char_width(*p);
    if (!w) continue;
    if (!first) x += 1;
    if (*p >= '0' && *p <= '9') {
      draw_small_digit(ctx, *p - '0', x, y);
    } else if (*p == '.' || *p == ',') {
      graphics_fill_rect(ctx, GRect(x, y + PPF_SMALL_HEIGHT - 2, 2, 2), 0, GCornerNone);
    } else if (*p == ':') {
      graphics_fill_rect(ctx, GRect(x, y + 3, 2, 2), 0, GCornerNone);
      graphics_fill_rect(ctx, GRect(x, y + 7, 2, 2), 0, GCornerNone);
    } else if (*p == '-') {
      graphics_fill_rect(ctx, GRect(x, y + 5, 6, 2), 0, GCornerNone);
    }
    x += w;
    first = false;
  }
}

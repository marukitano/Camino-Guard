#include <pebble.h>

#include <stdio.h>
#include <string.h>

/*
 * Camino Guard Pebble Time 2 dashboard.
 *
 * Watch-owned:
 *   - time / date
 *   - heart rate
 *   - battery
 *
 * Android-owned:
 *   - glucose
 *   - current GPS speed
 *   - next stop name / distance / ETA
 *   - ETA model's flat-ground reference speed
 *   - route validity / alarm
 */

static Window *s_window;
static Layer *s_dashboard_layer;

static char s_time_text[16];
static char s_date_text[24];
static char s_battery_text[24];
static char s_glucose_text[32] = "--";
static char s_next_name_text[40] = "--";
static char s_distance_text[32] = "--";
static char s_next_time_text[32] = "--";
static char s_speed_text[32] = "--";
static char s_flat_speed_text[32] = "--";
static int s_heart_rate = -1;
static bool s_alarm_active;
static bool s_route_valid;

#if defined(PBL_HEALTH)
static bool s_health_subscribed;
#endif

static int clamp_int(int value, int low, int high) {
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

static void mark_dashboard_dirty(void) {
    if (s_dashboard_layer != NULL) {
        layer_mark_dirty(s_dashboard_layer);
    }
}

static bool parse_decimal_tenths(const char *text, int *value) {
    if (text == NULL || value == NULL) return false;

    int whole = 0;
    int fraction = 0;
    bool have_digit = false;
    bool after_decimal = false;

    for (const char *p = text; *p != '\0'; ++p) {
        if (*p >= '0' && *p <= '9') {
            have_digit = true;
            if (!after_decimal) {
                whole = whole * 10 + (*p - '0');
            } else {
                fraction = *p - '0';
                break;
            }
        } else if ((*p == '.' || *p == ',') && have_digit) {
            after_decimal = true;
        } else if (have_digit) {
            break;
        }
    }

    if (!have_digit) return false;
    *value = whole * 10 + fraction;
    return true;
}

static const uint8_t *dot_rows_for_char(char ch) {
    static const uint8_t blank[7] = {0,0,0,0,0,0,0};
    static const uint8_t unknown[7] = {14,17,1,2,4,0,4};

    switch (ch) {
        case 'A': { static const uint8_t r[7]={14,17,17,31,17,17,17}; return r; }
        case 'B': { static const uint8_t r[7]={30,17,17,30,17,17,30}; return r; }
        case 'C': { static const uint8_t r[7]={14,17,16,16,16,17,14}; return r; }
        case 'D': { static const uint8_t r[7]={30,17,17,17,17,17,30}; return r; }
        case 'E': { static const uint8_t r[7]={31,16,16,30,16,16,31}; return r; }
        case 'F': { static const uint8_t r[7]={31,16,16,30,16,16,16}; return r; }
        case 'G': { static const uint8_t r[7]={14,17,16,23,17,17,15}; return r; }
        case 'H': { static const uint8_t r[7]={17,17,17,31,17,17,17}; return r; }
        case 'I': { static const uint8_t r[7]={14,4,4,4,4,4,14}; return r; }
        case 'J': { static const uint8_t r[7]={7,2,2,2,18,18,12}; return r; }
        case 'K': { static const uint8_t r[7]={17,18,20,24,20,18,17}; return r; }
        case 'L': { static const uint8_t r[7]={16,16,16,16,16,16,31}; return r; }
        case 'M': { static const uint8_t r[7]={17,27,21,21,17,17,17}; return r; }
        case 'N': { static const uint8_t r[7]={17,25,21,19,17,17,17}; return r; }
        case 'O': { static const uint8_t r[7]={14,17,17,17,17,17,14}; return r; }
        case 'P': { static const uint8_t r[7]={30,17,17,30,16,16,16}; return r; }
        case 'Q': { static const uint8_t r[7]={14,17,17,17,21,18,13}; return r; }
        case 'R': { static const uint8_t r[7]={30,17,17,30,20,18,17}; return r; }
        case 'S': { static const uint8_t r[7]={15,16,16,14,1,1,30}; return r; }
        case 'T': { static const uint8_t r[7]={31,4,4,4,4,4,4}; return r; }
        case 'U': { static const uint8_t r[7]={17,17,17,17,17,17,14}; return r; }
        case 'V': { static const uint8_t r[7]={17,17,17,17,17,10,4}; return r; }
        case 'W': { static const uint8_t r[7]={17,17,17,21,21,21,10}; return r; }
        case 'X': { static const uint8_t r[7]={17,17,10,4,10,17,17}; return r; }
        case 'Y': { static const uint8_t r[7]={17,17,10,4,4,4,4}; return r; }
        case 'Z': { static const uint8_t r[7]={31,1,2,4,8,16,31}; return r; }
        case '0': { static const uint8_t r[7]={14,17,19,21,25,17,14}; return r; }
        case '1': { static const uint8_t r[7]={4,12,4,4,4,4,14}; return r; }
        case '2': { static const uint8_t r[7]={14,17,1,2,4,8,31}; return r; }
        case '3': { static const uint8_t r[7]={30,1,1,14,1,1,30}; return r; }
        case '4': { static const uint8_t r[7]={2,6,10,18,31,2,2}; return r; }
        case '5': { static const uint8_t r[7]={31,16,16,30,1,1,30}; return r; }
        case '6': { static const uint8_t r[7]={14,16,16,30,17,17,14}; return r; }
        case '7': { static const uint8_t r[7]={31,1,2,4,8,8,8}; return r; }
        case '8': { static const uint8_t r[7]={14,17,17,14,17,17,14}; return r; }
        case '9': { static const uint8_t r[7]={14,17,17,15,1,1,14}; return r; }
        case '.': { static const uint8_t r[7]={0,0,0,0,0,12,12}; return r; }
        case ',': { static const uint8_t r[7]={0,0,0,0,0,12,8}; return r; }
        case ':': { static const uint8_t r[7]={0,12,12,0,12,12,0}; return r; }
        case '-': { static const uint8_t r[7]={0,0,0,31,0,0,0}; return r; }
        case '/': { static const uint8_t r[7]={1,2,2,4,8,8,16}; return r; }
        case '%': { static const uint8_t r[7]={17,2,4,8,16,0,17}; return r; }
        case '(': { static const uint8_t r[7]={2,4,8,8,8,4,2}; return r; }
        case ')': { static const uint8_t r[7]={8,4,2,2,2,4,8}; return r; }
        case ' ': return blank;
        default: return unknown;
    }
}

static uint32_t next_codepoint(const char **cursor) {
    const unsigned char *s = (const unsigned char *)*cursor;
    uint32_t cp;
    if (s[0] < 0x80) {
        cp = s[0]; *cursor += 1; return cp;
    }
    if ((s[0] & 0xE0) == 0xC0 && s[1]) {
        cp = ((uint32_t)(s[0] & 0x1F) << 6) | (uint32_t)(s[1] & 0x3F);
        *cursor += 2; return cp;
    }
    if ((s[0] & 0xF0) == 0xE0 && s[1] && s[2]) {
        cp = ((uint32_t)(s[0] & 0x0F) << 12) | ((uint32_t)(s[1] & 0x3F) << 6) | (uint32_t)(s[2] & 0x3F);
        *cursor += 3; return cp;
    }
    *cursor += 1;
    return '?';
}

static char normalize_codepoint(uint32_t cp, bool *umlaut) {
    *umlaut = false;
    if (cp >= 'a' && cp <= 'z') return (char)(cp - 'a' + 'A');
    if (cp < 128) return (char)cp;
    switch (cp) {
        case 0x00C4: case 0x00E4: *umlaut = true; return 'A';
        case 0x00D6: case 0x00F6: *umlaut = true; return 'O';
        case 0x00DC: case 0x00FC: *umlaut = true; return 'U';
        case 0x00C0: case 0x00C1: case 0x00C2: case 0x00C3: case 0x00C5:
        case 0x00E0: case 0x00E1: case 0x00E2: case 0x00E3: case 0x00E5: return 'A';
        case 0x00C7: case 0x00E7: return 'C';
        case 0x00C8: case 0x00C9: case 0x00CA: case 0x00CB:
        case 0x00E8: case 0x00E9: case 0x00EA: case 0x00EB: return 'E';
        case 0x00CC: case 0x00CD: case 0x00CE: case 0x00CF:
        case 0x00EC: case 0x00ED: case 0x00EE: case 0x00EF: return 'I';
        case 0x00D1: case 0x00F1: return 'N';
        case 0x00D2: case 0x00D3: case 0x00D4: case 0x00D5:
        case 0x00F2: case 0x00F3: case 0x00F4: case 0x00F5: return 'O';
        case 0x00D9: case 0x00DA: case 0x00DB:
        case 0x00F9: case 0x00FA: case 0x00FB: return 'U';
        case 0x00DD: case 0x00FD: case 0x00FF: return 'Y';
        case 0x00DF: return 'B';
        default: return '?';
    }
}

static int dot_text_width(const char *text, int pitch) {
    int count = 0;
    const char *p = text;
    while (*p) { (void)next_codepoint(&p); ++count; }
    if (count == 0) return 0;
    return count * (5 * pitch + pitch) - pitch;
}

static void draw_dot_text(
        GContext *ctx,
        const char *text,
        GRect rect,
        int pitch,
        GTextAlignment alignment
) {
    if (text == NULL || pitch < 1) return;
    int width = dot_text_width(text, pitch);
    int x = rect.origin.x;
    if (alignment == GTextAlignmentCenter) x += (rect.size.w - width) / 2;
    else if (alignment == GTextAlignmentRight) x += rect.size.w - width;

    int y = rect.origin.y;
    graphics_context_set_fill_color(ctx, GColorBlack);
    const char *p = text;
    while (*p) {
        bool umlaut = false;
        char ch = normalize_codepoint(next_codepoint(&p), &umlaut);
        const uint8_t *rows = dot_rows_for_char(ch);
        if (umlaut) {
            graphics_fill_circle(ctx, GPoint(x + pitch, y), pitch > 2 ? 1 : 0);
            graphics_fill_circle(ctx, GPoint(x + 3 * pitch, y), pitch > 2 ? 1 : 0);
        }
        for (int row = 0; row < 7; ++row) {
            for (int col = 0; col < 5; ++col) {
                if (rows[row] & (1 << (4 - col))) {
                    if (pitch >= 3) {
                        graphics_fill_circle(ctx, GPoint(x + col * pitch, y + 2 + row * pitch), 1);
                    } else {
                        graphics_draw_pixel(ctx, GPoint(x + col * pitch, y + 1 + row * pitch));
                    }
                }
            }
        }
        x += 6 * pitch;
        if (x > rect.origin.x + rect.size.w) break;
    }
}

static void draw_heart_icon(GContext *ctx, GRect r) {
    graphics_context_set_fill_color(ctx, GColorBlack);
    const int cx = r.origin.x + r.size.w / 2;
    const int y = r.origin.y + 5;
    const int rad = r.size.w / 4;
    graphics_fill_circle(ctx, GPoint(cx - rad, y + rad), rad);
    graphics_fill_circle(ctx, GPoint(cx + rad, y + rad), rad);
    GPathInfo info = {
        .num_points = 3,
        .points = (GPoint[]) {
            {1, 8},
            {r.size.w - 1, 8},
            {r.size.w / 2, r.size.h - 1}
        }
    };
    GPath *path = gpath_create(&info);
    gpath_move_to(path, r.origin);
    gpath_draw_filled(ctx, path);
    gpath_destroy(path);
}

static void draw_drop_icon(GContext *ctx, GRect r) {
    graphics_context_set_fill_color(ctx, GColorBlack);
    const int cx = r.origin.x + r.size.w / 2;
    GPathInfo info = {
        .num_points = 6,
        .points = (GPoint[]) {
            {r.size.w / 2, 0},
            {r.size.w - 2, r.size.h / 2},
            {r.size.w - 3, r.size.h - 6},
            {r.size.w / 2, r.size.h - 1},
            {2, r.size.h - 6},
            {1, r.size.h / 2}
        }
    };
    GPath *path = gpath_create(&info);
    gpath_move_to(path, r.origin);
    gpath_draw_filled(ctx, path);
    gpath_destroy(path);
    graphics_context_set_fill_color(ctx, GColorWhite);
    graphics_fill_circle(
            ctx,
            GPoint(cx, r.origin.y + (r.size.h * 2) / 3),
            3
    );
}

static void draw_shoe_icon(GContext *ctx, GRect r) {
    graphics_context_set_fill_color(ctx, GColorBlack);
    GPoint pts[] = {
        {2, 4}, {8, 4}, {10, 9}, {15, 12},
        {r.size.w - 2, 13}, {r.size.w - 1, r.size.h - 4},
        {4, r.size.h - 3}, {1, r.size.h - 7}
    };
    GPathInfo info = {.num_points = 8, .points = pts};
    GPath *path = gpath_create(&info);
    gpath_move_to(path, r.origin);
    gpath_draw_filled(ctx, path);
    gpath_destroy(path);
    graphics_context_set_stroke_color(ctx, GColorWhite);
    graphics_draw_line(
            ctx,
            GPoint(r.origin.x + 9, r.origin.y + 9),
            GPoint(r.origin.x + 15, r.origin.y + 9)
    );
}

static void draw_shell_icon(GContext *ctx, GRect r) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 2);

    const GPoint hinge = GPoint(r.origin.x + r.size.w / 2, r.origin.y + r.size.h - 3);
    const int left = r.origin.x + 3;
    const int right = r.origin.x + r.size.w - 3;
    const int top = r.origin.y + 4;
    const int mid = r.origin.x + r.size.w / 2;

    graphics_draw_line(ctx, hinge, GPoint(left, top + 5));
    graphics_draw_line(ctx, hinge, GPoint(left + 5, top + 1));
    graphics_draw_line(ctx, hinge, GPoint(mid - 4, top));
    graphics_draw_line(ctx, hinge, GPoint(mid + 4, top));
    graphics_draw_line(ctx, hinge, GPoint(right - 5, top + 1));
    graphics_draw_line(ctx, hinge, GPoint(right, top + 5));

    graphics_draw_arc(
            ctx,
            GRect(r.origin.x + 2, r.origin.y + 1, r.size.w - 4, r.size.h - 5),
            GOvalScaleModeFitCircle,
            DEG_TO_TRIGANGLE(205),
            DEG_TO_TRIGANGLE(335)
    );
    graphics_context_set_stroke_width(ctx, 1);
}

static void draw_route_icon(GContext *ctx, GRect r) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_fill_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 2);

    GPoint c = GPoint(r.origin.x + 7, r.origin.y + 7);
    graphics_draw_circle(ctx, c, 5);
    graphics_draw_line(ctx, GPoint(c.x - 3, c.y + 4), GPoint(c.x, c.y + 10));
    graphics_draw_line(ctx, GPoint(c.x + 3, c.y + 4), GPoint(c.x, c.y + 10));
    graphics_draw_line(ctx, GPoint(c.x, c.y + 10), GPoint(c.x + 5, c.y + 7));
    graphics_draw_line(
            ctx,
            GPoint(c.x + 5, c.y + 7),
            GPoint(r.origin.x + r.size.w - 2, r.origin.y + r.size.h - 3)
    );
    graphics_fill_circle(
            ctx,
            GPoint(r.origin.x + r.size.w - 2, r.origin.y + r.size.h - 3),
            2
    );
    graphics_context_set_stroke_width(ctx, 1);
}

static void draw_speedometer_icon(GContext *ctx, GRect r) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_fill_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 2);
    graphics_draw_arc(
            ctx,
            r,
            GOvalScaleModeFitCircle,
            DEG_TO_TRIGANGLE(205),
            DEG_TO_TRIGANGLE(335)
    );
    GPoint c = GPoint(r.origin.x + r.size.w / 2, r.origin.y + r.size.h / 2 + 3);
    graphics_draw_line(ctx, c, GPoint(r.origin.x + r.size.w - 4, r.origin.y + 5));
    graphics_fill_circle(ctx, c, 2);
    graphics_context_set_stroke_width(ctx, 1);
}

static void draw_clock_icon(GContext *ctx, GRect r) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 2);
    GPoint c = GPoint(r.origin.x + r.size.w / 2, r.origin.y + r.size.h / 2);
    int radius = (r.size.w < r.size.h ? r.size.w : r.size.h) / 2 - 2;
    graphics_draw_circle(ctx, c, radius);
    graphics_draw_line(ctx, c, GPoint(c.x, c.y - radius + 3));
    graphics_draw_line(ctx, c, GPoint(c.x + radius - 4, c.y));
    graphics_context_set_stroke_width(ctx, 1);
}

static void draw_metric_bar(
        GContext *ctx,
        GRect track,
        int fraction_1000,
        GColor color
) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 1);
    graphics_draw_round_rect(ctx, track, 4);

    int fill_w = (track.size.w - 2) * clamp_int(fraction_1000, 0, 1000) / 1000;
    if (fill_w > 0) {
        graphics_context_set_fill_color(ctx, color);
        graphics_fill_rect(
                ctx,
                GRect(track.origin.x + 1, track.origin.y + 1, fill_w, track.size.h - 2),
                3,
                GCornersAll
        );
    }
}

static void draw_live_row(
        GContext *ctx,
        int y,
        int kind,
        const char *value_text,
        int fraction_1000,
        GColor bar_color,
        GRect bounds
) {
    GRect icon = GRect(8, y + 1, 22, 20);
    if (kind == 0) draw_heart_icon(ctx, icon);
    else if (kind == 1) draw_drop_icon(ctx, icon);
    else draw_shoe_icon(ctx, icon);

    const int value_w = 48;
    const int bar_x = 36;
    const int bar_w = bounds.size.w - bar_x - value_w - 8;
    draw_metric_bar(ctx, GRect(bar_x, y + 3, bar_w, 17), fraction_1000, bar_color);
    draw_dot_text(ctx, value_text, GRect(bounds.size.w - value_w - 5, y - 2, value_w, 27), 3, GTextAlignmentRight);
}

static void dashboard_update_proc(Layer *layer, GContext *ctx) {
    GRect b = layer_get_bounds(layer);

    graphics_context_set_fill_color(ctx, GColorWhite);
    graphics_fill_rect(ctx, b, 0, GCornerNone);

    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 2);
    graphics_draw_round_rect(ctx, GRect(2, 2, b.size.w - 4, b.size.h - 4), 10);
    graphics_context_set_stroke_width(ctx, 1);

    draw_dot_text(ctx, s_date_text, GRect(10, 5, 62, 24), 2, GTextAlignmentLeft);
    draw_dot_text(ctx, s_battery_text, GRect((b.size.w - 62) / 2, 5, 62, 24), 2, GTextAlignmentCenter);
    draw_dot_text(ctx, s_time_text, GRect(b.size.w - 72, 5, 62, 24), 2, GTextAlignmentRight);

    graphics_draw_line(ctx, GPoint(7, 31), GPoint(b.size.w - 8, 31));

    char heart[16] = "--";
    if (s_heart_rate > 0) snprintf(heart, sizeof(heart), "%d", s_heart_rate);
    int heart_fraction = s_heart_rate > 0
            ? (clamp_int(s_heart_rate, 40, 180) - 40) * 1000 / 140
            : 0;

    int glucose_tenths = 0;
    bool have_glucose = parse_decimal_tenths(s_glucose_text, &glucose_tenths);
    int glucose_fraction = have_glucose
            ? (clamp_int(glucose_tenths, 20, 140) - 20) * 1000 / 120
            : 0;

    int speed_tenths = 0;
    bool have_speed = parse_decimal_tenths(s_speed_text, &speed_tenths);
    int speed_fraction = have_speed
            ? clamp_int(speed_tenths, 0, 80) * 1000 / 80
            : 0;

    char glucose_value[16] = "--";
    if (have_glucose) snprintf(glucose_value, sizeof(glucose_value), "%d.%d", glucose_tenths / 10, glucose_tenths % 10);
    char speed_value[16] = "--";
    if (have_speed) snprintf(speed_value, sizeof(speed_value), "%d.%d", speed_tenths / 10, speed_tenths % 10);

    draw_live_row(ctx, 36, 0, heart, heart_fraction, GColorRed, b);
    draw_live_row(ctx, 61, 1, glucose_value, glucose_fraction, GColorGreen, b);
    draw_live_row(ctx, 86, 2, speed_value, speed_fraction, GColorBlue, b);

    const int panel_y = 114;
    const int panel_h = b.size.h - panel_y - 5;
    GRect panel = GRect(5, panel_y, b.size.w - 10, panel_h);
    graphics_context_set_stroke_width(ctx, 2);
    graphics_draw_round_rect(ctx, panel, 11);
    graphics_context_set_stroke_width(ctx, 1);

    draw_shell_icon(ctx, GRect(12, panel_y + 8, 30, 28));
    draw_dot_text(ctx, "NÄCHSTES ZIEL", GRect(45, panel_y + 8, b.size.w - 55, 22), 2, GTextAlignmentLeft);
    draw_dot_text(ctx, s_next_name_text, GRect(45, panel_y + 27, b.size.w - 55, 27), 2, GTextAlignmentLeft);

    const int divider_y = panel_y + 55;
    graphics_draw_line(ctx, GPoint(12, divider_y), GPoint(b.size.w - 13, divider_y));

    const int inner_x = 8;
    const int inner_w = b.size.w - 16;
    const int col_w = inner_w / 3;
    const int bottom_y = divider_y + 3;

    graphics_draw_line(ctx, GPoint(inner_x + col_w, bottom_y), GPoint(inner_x + col_w, b.size.h - 12));
    graphics_draw_line(ctx, GPoint(inner_x + col_w * 2, bottom_y), GPoint(inner_x + col_w * 2, b.size.h - 12));

    int icon_y = bottom_y + 2;
    draw_route_icon(ctx, GRect(inner_x + (col_w - 24) / 2, icon_y, 24, 20));
    draw_speedometer_icon(ctx, GRect(inner_x + col_w + (col_w - 24) / 2, icon_y, 24, 20));
    draw_clock_icon(ctx, GRect(inner_x + col_w * 2 + (col_w - 22) / 2, icon_y, 22, 20));

    int value_y = icon_y + 21;
    draw_dot_text(ctx, s_distance_text, GRect(inner_x, value_y, col_w, 21), 2, GTextAlignmentCenter);
    draw_dot_text(ctx, s_flat_speed_text, GRect(inner_x + col_w, value_y + 1, col_w, 20), 2, GTextAlignmentCenter);
    draw_dot_text(ctx, s_next_time_text, GRect(inner_x + col_w * 2, value_y, inner_w - col_w * 2, 21), 2, GTextAlignmentCenter);

    int label_y = value_y + 20;
    draw_dot_text(ctx, "DIST", GRect(inner_x, label_y, col_w, 19), 2, GTextAlignmentCenter);
    draw_dot_text(ctx, "SPEED", GRect(inner_x + col_w, label_y, col_w, 19), 2, GTextAlignmentCenter);
    draw_dot_text(ctx, "ETA", GRect(inner_x + col_w * 2, label_y, inner_w - col_w * 2, 19), 2, GTextAlignmentCenter);

    if (s_alarm_active) {
        graphics_context_set_stroke_color(ctx, GColorRed);
        graphics_context_set_stroke_width(ctx, 3);
        graphics_draw_round_rect(ctx, GRect(4, 4, b.size.w - 8, b.size.h - 8), 9);
        graphics_context_set_stroke_width(ctx, 1);
    }
}

static void update_clock(struct tm *time_value) {
    struct tm local_value;
    if (time_value == NULL) {
        time_t now = time(NULL);
        local_value = *localtime(&now);
        time_value = &local_value;
    }

    if (clock_is_24h_style()) {
        strftime(s_time_text, sizeof(s_time_text), "%H:%M", time_value);
    } else {
        strftime(s_time_text, sizeof(s_time_text), "%I:%M", time_value);
        if (s_time_text[0] == '0') {
            memmove(s_time_text, s_time_text + 1, strlen(s_time_text));
        }
    }
    strftime(s_date_text, sizeof(s_date_text), "%d.%m", time_value);
    mark_dashboard_dirty();
}

static void tick_handler(struct tm *tick_time, TimeUnits units_changed) {
    update_clock(tick_time);
}

static void update_battery(BatteryChargeState state) {
    snprintf(s_battery_text, sizeof(s_battery_text), "%d%%", state.charge_percent);
    mark_dashboard_dirty();
}

static void battery_handler(BatteryChargeState state) {
    update_battery(state);
}

static void update_heart_rate(void) {
#if defined(PBL_HEALTH)
    HealthValue value = health_service_peek_current_value(HealthMetricHeartRateBPM);
    s_heart_rate = value > 0 ? (int)value : -1;
#else
    s_heart_rate = -1;
#endif
    mark_dashboard_dirty();
}

#if defined(PBL_HEALTH)
static void health_handler(HealthEventType event, void *context) {
    if (event == HealthEventHeartRateUpdate || event == HealthEventSignificantUpdate) {
        update_heart_rate();
    }
}
#endif

static void copy_tuple_text(DictionaryIterator *iterator, uint32_t key, char *target, size_t target_size) {
    Tuple *tuple = dict_find(iterator, key);
    if (tuple == NULL || target == NULL || target_size == 0) return;
    snprintf(target, target_size, "%s", tuple->value->cstring);
}

static bool tuple_is_one(DictionaryIterator *iterator, uint32_t key, bool fallback) {
    Tuple *tuple = dict_find(iterator, key);
    if (tuple == NULL) return fallback;
    return strcmp(tuple->value->cstring, "1") == 0;
}

static void inbox_received(DictionaryIterator *iterator, void *context) {
    copy_tuple_text(iterator, MESSAGE_KEY_GLUCOSE, s_glucose_text, sizeof(s_glucose_text));
    copy_tuple_text(iterator, MESSAGE_KEY_NEXT_NAME, s_next_name_text, sizeof(s_next_name_text));
    copy_tuple_text(iterator, MESSAGE_KEY_NEXT_DISTANCE, s_distance_text, sizeof(s_distance_text));
    copy_tuple_text(iterator, MESSAGE_KEY_NEXT_TIME, s_next_time_text, sizeof(s_next_time_text));
    copy_tuple_text(iterator, MESSAGE_KEY_CURRENT_SPEED, s_speed_text, sizeof(s_speed_text));
    copy_tuple_text(iterator, MESSAGE_KEY_FLAT_SPEED, s_flat_speed_text, sizeof(s_flat_speed_text));

    s_alarm_active = tuple_is_one(iterator, MESSAGE_KEY_ALARM_ACTIVE, s_alarm_active);
    s_route_valid = tuple_is_one(iterator, MESSAGE_KEY_ROUTE_VALID, s_route_valid);

    if (!s_route_valid && !s_alarm_active) {
        snprintf(s_next_name_text, sizeof(s_next_name_text), "--");
        snprintf(s_distance_text, sizeof(s_distance_text), "--");
        snprintf(s_next_time_text, sizeof(s_next_time_text), "--");
        snprintf(s_flat_speed_text, sizeof(s_flat_speed_text), "--");
    }

    mark_dashboard_dirty();
}

static void inbox_dropped(AppMessageResult reason, void *context) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "AppMessage dropped: %d", (int)reason);
}

static void window_load(Window *window) {
    Layer *root = window_get_root_layer(window);
    GRect bounds = layer_get_bounds(root);
    s_dashboard_layer = layer_create(bounds);
    layer_set_update_proc(s_dashboard_layer, dashboard_update_proc);
    layer_add_child(root, s_dashboard_layer);

    window_set_background_color(window, GColorWhite);
    update_clock(NULL);
    update_battery(battery_state_service_peek());
    update_heart_rate();
}

static void window_unload(Window *window) {
    layer_destroy(s_dashboard_layer);
    s_dashboard_layer = NULL;
}

static void init(void) {
    s_window = window_create();
    window_set_window_handlers(
            s_window,
            (WindowHandlers) {
                .load = window_load,
                .unload = window_unload
            }
    );
    window_stack_push(s_window, true);

    tick_timer_service_subscribe(MINUTE_UNIT, tick_handler);
    battery_state_service_subscribe(battery_handler);

#if defined(PBL_HEALTH)
    s_health_subscribed = health_service_events_subscribe(health_handler, NULL);
#endif

    app_message_register_inbox_received(inbox_received);
    app_message_register_inbox_dropped(inbox_dropped);

    AppMessageResult result = app_message_open(256, 64);
    if (result != APP_MSG_OK) {
        APP_LOG(APP_LOG_LEVEL_ERROR, "AppMessage open failed: %d", (int)result);
    }
}

static void deinit(void) {
    tick_timer_service_unsubscribe();
    battery_state_service_unsubscribe();

#if defined(PBL_HEALTH)
    if (s_health_subscribed) health_service_events_unsubscribe();
#endif

    window_destroy(s_window);
}

int main(void) {
    init();
    app_event_loop();
    deinit();
    return 0;
}

#include <pebble.h>

#include <stdio.h>
#include <string.h>


/*
 * Camino Guard Pebble Time 2 watchface.
 *
 * v0.1 deliberately has no visual design yet.
 *
 * Watch-owned:
 *   - time
 *   - date
 *   - heart rate
 *   - battery
 *
 * Android-owned:
 *   - glucose
 *   - distance to next timetable stop
 *   - time to next timetable stop
 *   - current GPS speed
 *   - off-route alarm
 */

static Window *s_window;

static TextLayer *s_time_layer;
static TextLayer *s_date_layer;
static Layer *s_heart_bar_layer;
static Layer *s_glucose_bar_layer;
static TextLayer *s_battery_layer;

static TextLayer *s_distance_layer;
static TextLayer *s_next_time_layer;
static TextLayer *s_speed_layer;



static char s_time_text[16];
static char s_date_text[24];
static char s_heart_text[24];
static int s_heart_rate = -1;
static char s_battery_text[24];

static char s_glucose_text[32] = "--";
static char s_next_name_text[40] = "--";
static char s_distance_text[32] = "--";
static char s_next_time_text[32] = "--";
static char s_speed_text[32] = "--";

static bool s_alarm_active;
static bool s_route_valid;

#if defined(PBL_HEALTH)
static bool s_health_subscribed;
#endif


static void configure_text_layer(
        TextLayer *layer,
        Layer *parent,
        const char *font_key,
        GTextAlignment alignment
) {
    text_layer_set_background_color(
            layer,
            GColorClear
    );

    text_layer_set_text_color(
            layer,
            GColorBlack
    );

    text_layer_set_font(
            layer,
            fonts_get_system_font(
                    font_key
            )
    );

    text_layer_set_text_alignment(
            layer,
            alignment
    );

    layer_add_child(
            parent,
            text_layer_get_layer(
                    layer
            )
    );
}


static void update_clock(
        struct tm *time_value
) {
    struct tm local_value;

    if (time_value == NULL) {
        time_t now = time(NULL);
        local_value = *localtime(&now);
        time_value = &local_value;
    }

    if (clock_is_24h_style()) {
        strftime(
                s_time_text,
                sizeof(s_time_text),
                "%H:%M",
                time_value
        );
    } else {
        strftime(
                s_time_text,
                sizeof(s_time_text),
                "%I:%M",
                time_value
        );

        if (s_time_text[0] == '0') {
            memmove(
                    s_time_text,
                    s_time_text + 1,
                    strlen(s_time_text)
            );
        }
    }

    strftime(
            s_date_text,
            sizeof(s_date_text),
            "%d.%m",
            time_value
    );

    text_layer_set_text(
            s_time_layer,
            s_time_text
    );

    text_layer_set_text(
            s_date_layer,
            s_date_text
    );
}


static void tick_handler(
        struct tm *tick_time,
        TimeUnits units_changed
) {
    update_clock(
            tick_time
    );
}


static void update_battery(
        BatteryChargeState state
) {
    snprintf(
            s_battery_text,
            sizeof(s_battery_text),
            "%d%%",
            state.charge_percent
    );

    text_layer_set_text(
            s_battery_layer,
            s_battery_text
    );
}


static void battery_handler(
        BatteryChargeState state
) {
    update_battery(
            state
    );
}


static void heart_bar_update_proc(
        Layer *layer,
        GContext *ctx
) {
    GRect bounds = layer_get_bounds(layer);

    const int min_rate = 20;
    const int max_rate = 180;

    if (s_heart_rate < 0) {
        graphics_context_set_text_color(
                ctx,
                GColorBlack
        );

        graphics_draw_text(
                ctx,
                "--",
                fonts_get_system_font(
                        FONT_KEY_GOTHIC_24_BOLD
                ),
                bounds,
                GTextOverflowModeFill,
                GTextAlignmentLeft,
                NULL
        );

        return;
    }

    int rate = s_heart_rate;

    if (rate < min_rate) {
        rate = min_rate;
    }

    if (rate > max_rate) {
        rate = max_rate;
    }

    int bar_end =
            ((rate - min_rate) * bounds.size.w)
                    / (max_rate - min_rate);

    graphics_context_set_fill_color(
            ctx,
            GColorRed
    );

    graphics_fill_rect(
            ctx,
            GRect(
                    0,
                    3,
                    bar_end,
                    22
            ),
            0,
            GCornerNone
    );

    snprintf(
            s_heart_text,
            sizeof(s_heart_text),
            "%d",
            s_heart_rate
    );

    const int text_width = 42;
    int text_x = bar_end + 3;

    if (text_x + text_width > bounds.size.w) {
        text_x = bar_end - text_width - 3;
    }

    if (text_x < 0) {
        text_x = 0;
    }

    graphics_context_set_text_color(
            ctx,
            GColorBlack
    );

    graphics_draw_text(
            ctx,
            s_heart_text,
            fonts_get_system_font(
                    FONT_KEY_GOTHIC_24_BOLD
            ),
            GRect(
                    text_x,
                    -2,
                    text_width,
                    bounds.size.h + 4
            ),
            GTextOverflowModeFill,
            GTextAlignmentCenter,
            NULL
    );
}


static void update_heart_rate(void) {
#if defined(PBL_HEALTH)
    HealthValue value =
            health_service_peek_current_value(
                    HealthMetricHeartRateBPM
            );

    if (value > 0) {
        s_heart_rate = (int) value;
    } else {
        s_heart_rate = -1;
    }
#else
    s_heart_rate = -1;
#endif

    if (s_heart_bar_layer != NULL) {
        layer_mark_dirty(
                s_heart_bar_layer
        );
    }
}


#if defined(PBL_HEALTH)
static void health_handler(
        HealthEventType event,
        void *context
) {
    if (event == HealthEventHeartRateUpdate
            || event == HealthEventSignificantUpdate) {

        update_heart_rate();
    }
}
#endif


static bool parse_glucose_tenths(
        const char *text,
        int *value
) {
    if (text == NULL || value == NULL) {
        return false;
    }

    int whole = 0;
    int fraction = 0;
    bool have_digit = false;
    bool after_decimal = false;

    for (const char *p = text; *p != '\0'; p++) {
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

    if (!have_digit) {
        return false;
    }

    *value = whole * 10 + fraction;
    return true;
}


static void glucose_bar_update_proc(
        Layer *layer,
        GContext *ctx
) {
    GRect bounds = layer_get_bounds(layer);

    const int min_glucose = 20;   /* 2.0 mmol/L */
    const int max_glucose = 140;  /* 14.0 mmol/L */

    int glucose = 0;

    if (!parse_glucose_tenths(
            s_glucose_text,
            &glucose
    )) {
        graphics_context_set_text_color(
                ctx,
                GColorBlack
        );

        graphics_draw_text(
                ctx,
                "--",
                fonts_get_system_font(
                        FONT_KEY_GOTHIC_24_BOLD
                ),
                bounds,
                GTextOverflowModeFill,
                GTextAlignmentLeft,
                NULL
        );

        return;
    }

    int clamped = glucose;

    if (clamped < min_glucose) {
        clamped = min_glucose;
    }

    if (clamped > max_glucose) {
        clamped = max_glucose;
    }

    int bar_end =
            ((clamped - min_glucose) * bounds.size.w)
                    / (max_glucose - min_glucose);

    graphics_context_set_fill_color(
            ctx,
            GColorGreen
    );

    graphics_fill_rect(
            ctx,
            GRect(
                    0,
                    3,
                    bar_end,
                    22
            ),
            0,
            GCornerNone
    );

    char value_text[16];

    snprintf(
            value_text,
            sizeof(value_text),
            "%d.%d",
            glucose / 10,
            glucose % 10
    );

    const int text_width = 48;
    int text_x = bar_end + 3;

    if (text_x + text_width > bounds.size.w) {
        text_x = bar_end - text_width - 3;
    }

    if (text_x < 0) {
        text_x = 0;
    }

    graphics_context_set_text_color(
            ctx,
            GColorBlack
    );

    graphics_draw_text(
            ctx,
            value_text,
            fonts_get_system_font(
                    FONT_KEY_GOTHIC_24_BOLD
            ),
            GRect(
                    text_x,
                    -2,
                    text_width,
                    bounds.size.h + 4
            ),
            GTextOverflowModeFill,
            GTextAlignmentCenter,
            NULL
    );
}


static void apply_phone_values(void) {
    static char distance_line[48];
    static char time_line[48];
    static char speed_line[48];

    snprintf(
            distance_line,
            sizeof(distance_line),
            "Ziel %s",
            s_next_name_text
    );

    snprintf(
            time_line,
            sizeof(time_line),
            "%s  %s",
            s_distance_text,
            s_next_time_text
    );

    snprintf(
            speed_line,
            sizeof(speed_line),
            "Tempo %s",
            s_speed_text
    );

    if (s_glucose_bar_layer != NULL) {
        layer_mark_dirty(
                s_glucose_bar_layer
        );
    }

    text_layer_set_text(
            s_distance_layer,
            distance_line
    );

    text_layer_set_text(
            s_next_time_layer,
            time_line
    );

    text_layer_set_text(
            s_speed_layer,
            speed_line
    );
}



static void copy_tuple_text(
        DictionaryIterator *iterator,
        uint32_t key,
        char *target,
        size_t target_size
) {
    Tuple *tuple =
            dict_find(
                    iterator,
                    key
            );

    if (tuple == NULL
            || target == NULL
            || target_size == 0) {

        return;
    }

    snprintf(
            target,
            target_size,
            "%s",
            tuple->value->cstring
    );
}


static bool tuple_is_one(
        DictionaryIterator *iterator,
        uint32_t key,
        bool fallback
) {
    Tuple *tuple =
            dict_find(
                    iterator,
                    key
            );

    if (tuple == NULL) {

        return fallback;
    }

    return strcmp(
            tuple->value->cstring,
            "1"
    ) == 0;
}


static void inbox_received(
        DictionaryIterator *iterator,
        void *context
) {
    copy_tuple_text(
            iterator,
            MESSAGE_KEY_GLUCOSE,
            s_glucose_text,
            sizeof(s_glucose_text)
    );

    copy_tuple_text(
            iterator,
            MESSAGE_KEY_NEXT_NAME,
            s_next_name_text,
            sizeof(s_next_name_text)
    );

    copy_tuple_text(
            iterator,
            MESSAGE_KEY_NEXT_DISTANCE,
            s_distance_text,
            sizeof(s_distance_text)
    );

    copy_tuple_text(
            iterator,
            MESSAGE_KEY_NEXT_TIME,
            s_next_time_text,
            sizeof(s_next_time_text)
    );

    copy_tuple_text(
            iterator,
            MESSAGE_KEY_CURRENT_SPEED,
            s_speed_text,
            sizeof(s_speed_text)
    );

    s_route_valid =
            tuple_is_one(
                    iterator,
                    MESSAGE_KEY_ROUTE_VALID,
                    s_route_valid
            );

    if (!s_route_valid
            && !s_alarm_active) {

        snprintf(
                s_next_name_text,
                sizeof(s_next_name_text),
                "--"
        );

        snprintf(
                s_distance_text,
                sizeof(s_distance_text),
                "--"
        );

        snprintf(
                s_next_time_text,
                sizeof(s_next_time_text),
                "--"
        );

    }

    apply_phone_values();
}


static void inbox_dropped(
        AppMessageResult reason,
        void *context
) {
    APP_LOG(
            APP_LOG_LEVEL_WARNING,
            "AppMessage dropped: %d",
            (int) reason
    );
}


static void window_load(
        Window *window
) {
    Layer *root =
            window_get_root_layer(
                    window
            );

    GRect bounds =
            layer_get_bounds(
                    root
            );

    /*
     * Plain diagnostic layout for the first working version.
     * Design comes later.
     */
    s_time_layer =
            text_layer_create(
                    GRect(
                            0,
                            2,
                            bounds.size.w,
                            38
                    )
            );

    s_date_layer =
            text_layer_create(
                    GRect(
                            0,
                            2,
                            bounds.size.w,
                            38
                    )
            );

    s_heart_bar_layer =
            layer_create(
                    GRect(
                            0,
                            66,
                            bounds.size.w,
                            28
                    )
            );

    s_glucose_bar_layer =
            layer_create(
                    GRect(
                            0,
                            98,
                            bounds.size.w,
                            28
                    )
            );

    s_battery_layer =
            text_layer_create(
                    GRect(
                            0,
                            2,
                            bounds.size.w,
                            38
                    )
            );

    s_distance_layer =
            text_layer_create(
                    GRect(
                            0,
                            148,
                            bounds.size.w,
                            24
                    )
            );

    s_next_time_layer =
            text_layer_create(
                    GRect(
                            0,
                            172,
                            bounds.size.w,
                            24
                    )
            );

    s_speed_layer =
            text_layer_create(
                    GRect(
                            0,
                            196,
                            bounds.size.w,
                            24
                    )
            );


    configure_text_layer(
            s_time_layer,
            root,
            FONT_KEY_GOTHIC_28,
            GTextAlignmentRight
    );

    configure_text_layer(
            s_date_layer,
            root,
            FONT_KEY_GOTHIC_28,
            GTextAlignmentLeft
    );

    layer_set_update_proc(
            s_heart_bar_layer,
            heart_bar_update_proc
    );

    layer_add_child(
            root,
            s_heart_bar_layer
    );

    layer_set_update_proc(
            s_glucose_bar_layer,
            glucose_bar_update_proc
    );

    layer_add_child(
            root,
            s_glucose_bar_layer
    );

    configure_text_layer(
            s_battery_layer,
            root,
            FONT_KEY_GOTHIC_28,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_distance_layer,
            root,
            FONT_KEY_GOTHIC_18,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_next_time_layer,
            root,
            FONT_KEY_GOTHIC_18,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_speed_layer,
            root,
            FONT_KEY_GOTHIC_18,
            GTextAlignmentCenter
    );

    window_set_background_color(
            window,
            GColorWhite
    );

    update_clock(
            NULL
    );

    update_battery(
            battery_state_service_peek()
    );

    update_heart_rate();

    apply_phone_values();
}


static void window_unload(
        Window *window
) {
    text_layer_destroy(
            s_time_layer
    );

    text_layer_destroy(
            s_date_layer
    );

    layer_destroy(
            s_heart_bar_layer
    );

    layer_destroy(
            s_glucose_bar_layer
    );

    text_layer_destroy(
            s_battery_layer
    );

    text_layer_destroy(
            s_distance_layer
    );

    text_layer_destroy(
            s_next_time_layer
    );

    text_layer_destroy(
            s_speed_layer
    );

}


static void init(void) {
    s_window =
            window_create();

    window_set_window_handlers(
            s_window,
            (WindowHandlers) {
                    .load = window_load,
                    .unload = window_unload
            }
    );

    window_stack_push(
            s_window,
            true
    );

    tick_timer_service_subscribe(
            MINUTE_UNIT,
            tick_handler
    );

    battery_state_service_subscribe(
            battery_handler
    );

#if defined(PBL_HEALTH)
    s_health_subscribed =
            health_service_events_subscribe(
                    health_handler,
                    NULL
            );
#endif

    app_message_register_inbox_received(
            inbox_received
    );

    app_message_register_inbox_dropped(
            inbox_dropped
    );

    AppMessageResult result =
            app_message_open(
                    256,
                    64
            );

    if (result != APP_MSG_OK) {
        APP_LOG(
                APP_LOG_LEVEL_ERROR,
                "AppMessage open failed: %d",
                (int) result
        );
    }
}


static void deinit(void) {
    tick_timer_service_unsubscribe();
    battery_state_service_unsubscribe();

#if defined(PBL_HEALTH)
    if (s_health_subscribed) {
        health_service_events_unsubscribe();
    }
#endif

    window_destroy(
            s_window
    );
}


int main(void) {
    init();
    app_event_loop();
    deinit();

    return 0;
}

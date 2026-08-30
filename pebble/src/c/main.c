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
static TextLayer *s_heart_layer;
static TextLayer *s_glucose_layer;
static TextLayer *s_battery_layer;

static TextLayer *s_distance_layer;
static TextLayer *s_next_time_layer;
static TextLayer *s_speed_layer;

static TextLayer *s_alarm_layer;


static char s_time_text[16];
static char s_date_text[24];
static char s_heart_text[24];
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
            GColorWhite
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
            "%d.%m.%Y",
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
            "Akku %d%%",
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


static void update_heart_rate(void) {
#if defined(PBL_HEALTH)
    HealthValue value =
            health_service_peek_current_value(
                    HealthMetricHeartRateBPM
            );

    if (value > 0) {
        snprintf(
                s_heart_text,
                sizeof(s_heart_text),
                "Puls %ld bpm",
                (long) value
        );
    } else {
        snprintf(
                s_heart_text,
                sizeof(s_heart_text),
                "Puls --"
        );
    }
#else
    snprintf(
            s_heart_text,
            sizeof(s_heart_text),
            "Puls --"
    );
#endif

    text_layer_set_text(
            s_heart_layer,
            s_heart_text
    );
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


static void apply_phone_values(void) {
    static char glucose_line[48];
    static char distance_line[48];
    static char time_line[48];
    static char speed_line[48];

    snprintf(
            glucose_line,
            sizeof(glucose_line),
            "Glukose %s",
            s_glucose_text
    );

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

    text_layer_set_text(
            s_glucose_layer,
            glucose_line
    );

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


static void apply_alarm_visibility(void) {
    bool show_route =
            !s_alarm_active;

    layer_set_hidden(
            text_layer_get_layer(
                    s_distance_layer
            ),
            !show_route
    );

    layer_set_hidden(
            text_layer_get_layer(
                    s_next_time_layer
            ),
            !show_route
    );

    layer_set_hidden(
            text_layer_get_layer(
                    s_speed_layer
            ),
            !show_route
    );

    layer_set_hidden(
            text_layer_get_layer(
                    s_alarm_layer
            ),
            !s_alarm_active
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
    bool previous_alarm =
            s_alarm_active;

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

    s_alarm_active =
            tuple_is_one(
                    iterator,
                    MESSAGE_KEY_ALARM_ACTIVE,
                    s_alarm_active
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

        snprintf(
                s_speed_text,
                sizeof(s_speed_text),
                "--"
        );
    }

    apply_phone_values();
    apply_alarm_visibility();

    /*
     * Alarm vibration only on:
     *
     *   ON ROUTE -> OFF ROUTE
     *
     * Staying off route therefore does not vibrate every GPS update.
     */
    if (!previous_alarm
            && s_alarm_active) {

        vibes_double_pulse();
    }
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
                            38,
                            bounds.size.w,
                            24
                    )
            );

    s_heart_layer =
            text_layer_create(
                    GRect(
                            0,
                            66,
                            bounds.size.w,
                            24
                    )
            );

    s_glucose_layer =
            text_layer_create(
                    GRect(
                            0,
                            90,
                            bounds.size.w,
                            24
                    )
            );

    s_battery_layer =
            text_layer_create(
                    GRect(
                            0,
                            114,
                            bounds.size.w,
                            24
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

    s_alarm_layer =
            text_layer_create(
                    GRect(
                            0,
                            158,
                            bounds.size.w,
                            48
                    )
            );


    configure_text_layer(
            s_time_layer,
            root,
            FONT_KEY_GOTHIC_28_BOLD,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_date_layer,
            root,
            FONT_KEY_GOTHIC_18,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_heart_layer,
            root,
            FONT_KEY_GOTHIC_18,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_glucose_layer,
            root,
            FONT_KEY_GOTHIC_18,
            GTextAlignmentCenter
    );

    configure_text_layer(
            s_battery_layer,
            root,
            FONT_KEY_GOTHIC_18,
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

    configure_text_layer(
            s_alarm_layer,
            root,
            FONT_KEY_GOTHIC_24_BOLD,
            GTextAlignmentCenter
    );


    text_layer_set_text(
            s_alarm_layer,
            "OFF ROUTE"
    );

    text_layer_set_text_alignment(
            s_alarm_layer,
            GTextAlignmentCenter
    );

    window_set_background_color(
            window,
            GColorBlack
    );

    update_clock(
            NULL
    );

    update_battery(
            battery_state_service_peek()
    );

    update_heart_rate();

    apply_phone_values();
    apply_alarm_visibility();
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

    text_layer_destroy(
            s_heart_layer
    );

    text_layer_destroy(
            s_glucose_layer
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

    text_layer_destroy(
            s_alarm_layer
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

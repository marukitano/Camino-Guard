#include <pebble.h>
#include <stdio.h>
#include <string.h>

#include "ppf_digit_font.h"

static Window *s_window;
static Layer *s_dashboard_layer;

static GBitmap *s_icon_heart;
static GBitmap *s_icon_blood;
static GBitmap *s_icon_shoe;
static GBitmap *s_icon_shell;

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
static GColor s_ink;

#if defined(PBL_HEALTH)
static bool s_health_subscribed;
#endif

static int clamp_i(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static void dirty(void) {
    if (s_dashboard_layer) layer_mark_dirty(s_dashboard_layer);
}

static bool parse_tenths(const char *text, int *value) {
    if (!text || !value) return false;
    int whole = 0, frac = 0;
    bool digit = false, decimal = false;
    for (const char *p = text; *p; ++p) {
        if (*p >= '0' && *p <= '9') {
            digit = true;
            if (!decimal) whole = whole * 10 + (*p - '0');
            else { frac = *p - '0'; break; }
        } else if ((*p == '.' || *p == ',') && digit) {
            decimal = true;
        } else if (digit) {
            break;
        }
    }
    if (!digit) return false;
    *value = whole * 10 + frac;
    return true;
}

static const uint8_t *glyph(char c) {
    static const uint8_t blank[7]={0,0,0,0,0,0,0};
    static const uint8_t q[7]={14,17,1,2,4,0,4};
    switch(c) {
        case 'A': {static const uint8_t r[7]={14,17,17,31,17,17,17};return r;}
        case 'B': {static const uint8_t r[7]={30,17,17,30,17,17,30};return r;}
        case 'C': {static const uint8_t r[7]={14,17,16,16,16,17,14};return r;}
        case 'D': {static const uint8_t r[7]={30,17,17,17,17,17,30};return r;}
        case 'E': {static const uint8_t r[7]={31,16,16,30,16,16,31};return r;}
        case 'F': {static const uint8_t r[7]={31,16,16,30,16,16,16};return r;}
        case 'G': {static const uint8_t r[7]={14,17,16,23,17,17,15};return r;}
        case 'H': {static const uint8_t r[7]={17,17,17,31,17,17,17};return r;}
        case 'I': {static const uint8_t r[7]={14,4,4,4,4,4,14};return r;}
        case 'J': {static const uint8_t r[7]={7,2,2,2,18,18,12};return r;}
        case 'K': {static const uint8_t r[7]={17,18,20,24,20,18,17};return r;}
        case 'L': {static const uint8_t r[7]={16,16,16,16,16,16,31};return r;}
        case 'M': {static const uint8_t r[7]={17,27,21,21,17,17,17};return r;}
        case 'N': {static const uint8_t r[7]={17,25,21,19,17,17,17};return r;}
        case 'O': {static const uint8_t r[7]={14,17,17,17,17,17,14};return r;}
        case 'P': {static const uint8_t r[7]={30,17,17,30,16,16,16};return r;}
        case 'Q': {static const uint8_t r[7]={14,17,17,17,21,18,13};return r;}
        case 'R': {static const uint8_t r[7]={30,17,17,30,20,18,17};return r;}
        case 'S': {static const uint8_t r[7]={15,16,16,14,1,1,30};return r;}
        case 'T': {static const uint8_t r[7]={31,4,4,4,4,4,4};return r;}
        case 'U': {static const uint8_t r[7]={17,17,17,17,17,17,14};return r;}
        case 'V': {static const uint8_t r[7]={17,17,17,17,17,10,4};return r;}
        case 'W': {static const uint8_t r[7]={17,17,17,21,21,21,10};return r;}
        case 'X': {static const uint8_t r[7]={17,17,10,4,10,17,17};return r;}
        case 'Y': {static const uint8_t r[7]={17,17,10,4,4,4,4};return r;}
        case 'Z': {static const uint8_t r[7]={31,1,2,4,8,16,31};return r;}
        case '0': {static const uint8_t r[7]={14,17,19,21,25,17,14};return r;}
        case '1': {static const uint8_t r[7]={4,12,4,4,4,4,14};return r;}
        case '2': {static const uint8_t r[7]={14,17,1,2,4,8,31};return r;}
        case '3': {static const uint8_t r[7]={30,1,1,14,1,1,30};return r;}
        case '4': {static const uint8_t r[7]={2,6,10,18,31,2,2};return r;}
        case '5': {static const uint8_t r[7]={31,16,16,30,1,1,30};return r;}
        case '6': {static const uint8_t r[7]={14,16,16,30,17,17,14};return r;}
        case '7': {static const uint8_t r[7]={31,1,2,4,8,8,8};return r;}
        case '8': {static const uint8_t r[7]={14,17,17,14,17,17,14};return r;}
        case '9': {static const uint8_t r[7]={14,17,17,15,1,1,14};return r;}
        case '.': {static const uint8_t r[7]={0,0,0,0,0,12,12};return r;}
        case ':': {static const uint8_t r[7]={0,12,12,0,12,12,0};return r;}
        case '-': {static const uint8_t r[7]={0,0,0,31,0,0,0};return r;}
        case '/': {static const uint8_t r[7]={1,2,2,4,8,8,16};return r;}
        case '%': {static const uint8_t r[7]={17,2,4,8,16,0,17};return r;}
        case ' ': return blank;
        default: return q;
    }
}

static uint32_t codepoint(const char **p) {
    const unsigned char *s=(const unsigned char *)*p;
    if (s[0] < 0x80) {(*p)++; return s[0];}
    if ((s[0]&0xE0)==0xC0 && s[1]) {uint32_t c=((s[0]&31)<<6)|(s[1]&63);*p+=2;return c;}
    if ((s[0]&0xF0)==0xE0 && s[1] && s[2]) {uint32_t c=((s[0]&15)<<12)|((s[1]&63)<<6)|(s[2]&63);*p+=3;return c;}
    (*p)++; return '?';
}

static char norm(uint32_t c, bool *umlaut) {
    *umlaut=false;
    if (c>='a' && c<='z') return (char)(c-'a'+'A');
    if (c<128) return (char)c;
    if (c==0xC4 || c==0xE4) {*umlaut=true;return 'A';}
    if (c==0xD6 || c==0xF6) {*umlaut=true;return 'O';}
    if (c==0xDC || c==0xFC) {*umlaut=true;return 'U';}
    if ((c>=0xC0&&c<=0xC5)||(c>=0xE0&&c<=0xE5)) return 'A';
    if (c==0xC7||c==0xE7) return 'C';
    if ((c>=0xC8&&c<=0xCB)||(c>=0xE8&&c<=0xEB)) return 'E';
    if ((c>=0xCC&&c<=0xCF)||(c>=0xEC&&c<=0xEF)) return 'I';
    if (c==0xD1||c==0xF1) return 'N';
    if ((c>=0xD2&&c<=0xD6)||(c>=0xF2&&c<=0xF6)) return 'O';
    if ((c>=0xD9&&c<=0xDC)||(c>=0xF9&&c<=0xFC)) return 'U';
    return '?';
}

static int text_w(const char *text, int pitch) {
    int n=0; const char *p=text; while (*p) {codepoint(&p);n++;}
    return n ? n*6*pitch-pitch : 0;
}

static void dot_text(GContext *ctx, const char *text, GRect r, int pitch, GTextAlignment align) {
    if (!text) return;
    int w=text_w(text,pitch), x=r.origin.x;
    if (align==GTextAlignmentCenter) x+=(r.size.w-w)/2;
    else if (align==GTextAlignmentRight) x+=r.size.w-w;
    int y=r.origin.y;
    graphics_context_set_fill_color(ctx,s_ink);
    graphics_context_set_stroke_color(ctx,s_ink);
    const char *p=text;
    while (*p) {
        bool uml=false; char c=norm(codepoint(&p),&uml); const uint8_t *rows=glyph(c);
        if (uml) {graphics_draw_pixel(ctx,GPoint(x+pitch,y));graphics_draw_pixel(ctx,GPoint(x+3*pitch,y));}
        for(int yy=0;yy<7;yy++) for(int xx=0;xx<5;xx++) if(rows[yy]&(1<<(4-xx))) {
            if(pitch>=3) graphics_fill_circle(ctx,GPoint(x+xx*pitch,y+2+yy*pitch),1);
            else graphics_draw_pixel(ctx,GPoint(x+xx*pitch,y+1+yy*pitch));
        }
        x+=6*pitch;
    }
}

static void bold_dot_text(GContext *ctx, const char *text, GRect r, int pitch, GTextAlignment align) {
    dot_text(ctx,text,r,pitch,align);
    r.origin.x += 1;
    dot_text(ctx,text,r,pitch,align);
}

static void draw_bitmap_icon(GContext *ctx, GBitmap *bitmap, GRect r) {
    if (!bitmap) return;
    graphics_context_set_compositing_mode(ctx, GCompOpSet);
    graphics_draw_bitmap_in_rect(ctx, bitmap, r);
}

static void pixel20_icon(GContext *ctx, const uint32_t rows[20], GRect r) {
    graphics_context_set_fill_color(ctx,GColorBlack);
    for (int y=0; y<20; ++y) {
        for (int x=0; x<20; ++x) {
            if (rows[y] & (1u << (19-x))) {
                graphics_draw_pixel(ctx,GPoint(r.origin.x+x,r.origin.y+y));
            }
        }
    }
}

static void route_icon(GContext *ctx,GRect r) {
    static const uint32_t a[20]={0x00000u,0x00000u,0x00000u,0x00000u,0x00000u,0x00000u,0x1C000u,0x7E00Cu,0xFF07Cu,0xE3180u,0xC3800u,0xC3980u,0xFF0B0u,0x7F010u,0x7E00Cu,0x3C004u,0x1C000u,0x18018u,0x0B6D0u,0x0B600u};
    pixel20_icon(ctx,a,r);
}
static void gauge_icon(GContext *ctx,GRect r) {
    static const uint32_t a[20]={0x00000u,0x01F80u,0x07FE0u,0x0E670u,0x1C618u,0x3E00Cu,0x360ECu,0x601C6u,0x607C6u,0x78F9Eu,0x78F1Eu,0x60F06u,0x60606u,0x3000Cu,0x3000Cu,0x18018u,0x0C030u,0x04020u,0x00000u,0x00000u};
    pixel20_icon(ctx,a,r);
}
static void clock_icon(GContext *ctx,GRect r) {
    static const uint32_t a[20]={0x00000u,0x00000u,0x1FFF8u,0x1FFF8u,0x1B378u,0x1FFF8u,0x1ECD8u,0x1FFF8u,0x1B378u,0x1FFF8u,0x1ECD8u,0x1FFF8u,0x1FFF8u,0x18000u,0x18000u,0x18000u,0x18000u,0x10000u,0x00000u,0x00000u};
    pixel20_icon(ctx,a,r);
}

static int metric_bar(GContext *ctx, int y, int fraction, GColor color, GRect b) {
    const int min_w = 30;
    const int value_lane = 84;
    const int max_w = b.size.w - value_lane;
    int w = max_w * clamp_i(fraction,0,1000) / 1000;
    if (w < min_w) w = min_w;
    graphics_context_set_fill_color(ctx,color);
    graphics_fill_rect(ctx,GRect(0,y,w,PPF_VALUE_HEIGHT),0,GCornerNone);
    return w;
}

static void live_row(GContext *ctx,int y,int kind,const char *value,int fraction,GColor color,GRect b) {
    const int row_y = y + 2;
    const int bar_w = metric_bar(ctx,row_y,fraction,color,b);

    if(kind==0) draw_bitmap_icon(ctx,s_icon_heart,GRect(8,row_y,20,20));
    else if(kind==1) draw_bitmap_icon(ctx,s_icon_blood,GRect(8,row_y,20,20));
    else draw_bitmap_icon(ctx,s_icon_shoe,GRect(8,row_y,20,20));

    const int value_x = bar_w + 2;
    ppf_draw_value(ctx,value,value_x + ppf_value_width(value),row_y,GColorWhite);
}

static void dashboard_update_proc(Layer *layer,GContext *ctx) {
    GRect b=layer_get_bounds(layer);
    graphics_context_set_fill_color(ctx,GColorBlack); graphics_fill_rect(ctx,b,0,GCornerNone);
    s_ink=GColorWhite; graphics_context_set_stroke_color(ctx,GColorWhite);

    dot_text(ctx,s_date_text,GRect(8,5,62,24),2,GTextAlignmentLeft);
    dot_text(ctx,s_battery_text,GRect((b.size.w-62)/2,5,62,24),2,GTextAlignmentCenter);
    dot_text(ctx,s_time_text,GRect(b.size.w-70,5,62,24),2,GTextAlignmentRight);

    char heart[16]="--"; if(s_heart_rate>0) snprintf(heart,sizeof(heart),"%d",s_heart_rate);
    int hf=s_heart_rate>0?(clamp_i(s_heart_rate,40,180)-40)*1000/140:0;
    int gt=0,st=0; bool hg=parse_tenths(s_glucose_text,&gt), hs=parse_tenths(s_speed_text,&st);
    int gf=hg?(clamp_i(gt,20,140)-20)*1000/120:0, sf=hs?clamp_i(st,0,80)*1000/80:0;
    char gv[16]="--",sv[16]="--";
    if(hg) snprintf(gv,sizeof(gv),"%d.%d",gt/10,gt%10);
    if(hs) snprintf(sv,sizeof(sv),"%d.%d",st/10,st%10);
    live_row(ctx,31,0,heart,hf,GColorRed,b);
    live_row(ctx,56,1,gv,gf,GColorGreen,b);
    live_row(ctx,81,2,sv,sf,GColorBlue,b);

    const int py=114, ph=b.size.h-py-5;
    GRect panel=GRect(5,py,b.size.w-10,ph);
    GRect outer_panel=GRect(panel.origin.x-1,panel.origin.y-1,panel.size.w+2,panel.size.h+2);
    graphics_context_set_fill_color(ctx,GColorChromeYellow); graphics_fill_rect(ctx,panel,11,GCornersAll);
    graphics_context_set_stroke_color(ctx,GColorWhite); graphics_context_set_stroke_width(ctx,1);
    graphics_draw_round_rect(ctx,outer_panel,12);
    graphics_context_set_stroke_color(ctx,GColorYellow); graphics_context_set_stroke_width(ctx,3);
    graphics_draw_round_rect(ctx,panel,11); graphics_context_set_stroke_width(ctx,1);
    s_ink=GColorBlack;

    draw_bitmap_icon(ctx,s_icon_shell,GRect(10,py+6,34,34));
    s_ink=GColorBlack;
    bold_dot_text(ctx,"NEXT STOP",GRect(50,py+6,b.size.w-58,18),2,GTextAlignmentLeft);
    int next_pitch = text_w(s_next_name_text,3) <= b.size.w-58 ? 3 : 2;
    dot_text(ctx,s_next_name_text,GRect(50,py+25,b.size.w-58,27),next_pitch,GTextAlignmentLeft);

    const int dy=py+55, ix=8, iw=b.size.w-16, cw=iw/3, by=dy+3;
    graphics_context_set_stroke_color(ctx,GColorBlack);
    graphics_draw_line(ctx,GPoint(12,dy),GPoint(b.size.w-13,dy));
    graphics_draw_line(ctx,GPoint(ix+cw,by),GPoint(ix+cw,b.size.h-12));
    graphics_draw_line(ctx,GPoint(ix+cw*2,by),GPoint(ix+cw*2,b.size.h-12));

    int iy=by+3;
    route_icon(ctx,GRect(ix+(cw-20)/2,iy,20,20));
    gauge_icon(ctx,GRect(ix+cw+(cw-20)/2,iy,20,20));
    clock_icon(ctx,GRect(ix+cw*2+(cw-20)/2,iy,20,20));
    int vy=iy+22;
    ppf_draw_small_value_centered(ctx,s_distance_text,GRect(ix,vy,cw,18),GColorBlack);
    ppf_draw_small_value_centered(ctx,s_flat_speed_text,GRect(ix+cw,vy,cw,18),GColorBlack);
    ppf_draw_small_value_centered(ctx,s_next_time_text,GRect(ix+cw*2,vy,iw-cw*2,18),GColorBlack);

    if(s_alarm_active) {
        graphics_context_set_stroke_color(ctx,GColorRed); graphics_context_set_stroke_width(ctx,3);
        graphics_draw_round_rect(ctx,GRect(panel.origin.x+1,panel.origin.y+1,panel.size.w-2,panel.size.h-2),10);
        graphics_context_set_stroke_width(ctx,1);
    }
    s_ink=GColorWhite;
}

static void update_clock(struct tm *t) {
    struct tm local;
    if(!t){time_t now=time(NULL);local=*localtime(&now);t=&local;}
    if(clock_is_24h_style()) strftime(s_time_text,sizeof(s_time_text),"%H:%M",t);
    else {strftime(s_time_text,sizeof(s_time_text),"%I:%M",t);if(s_time_text[0]=='0')memmove(s_time_text,s_time_text+1,strlen(s_time_text));}
    strftime(s_date_text,sizeof(s_date_text),"%d.%m",t); dirty();
}
static void tick_handler(struct tm *t,TimeUnits u){update_clock(t);}
static void update_battery(BatteryChargeState s){snprintf(s_battery_text,sizeof(s_battery_text),"%d%%",s.charge_percent);dirty();}
static void battery_handler(BatteryChargeState s){update_battery(s);}

static void update_heart_rate(void) {
#if defined(PBL_HEALTH)
    HealthValue v=health_service_peek_current_value(HealthMetricHeartRateBPM); s_heart_rate=v>0?(int)v:-1;
#else
    s_heart_rate=-1;
#endif
    dirty();
}
#if defined(PBL_HEALTH)
static void health_handler(HealthEventType e,void *c){if(e==HealthEventHeartRateUpdate||e==HealthEventSignificantUpdate)update_heart_rate();}
#endif

static void copy_text(DictionaryIterator *it,uint32_t key,char *dst,size_t n){Tuple *t=dict_find(it,key);if(t&&dst&&n)snprintf(dst,n,"%s",t->value->cstring);}
static bool tuple_one(DictionaryIterator *it,uint32_t key,bool fallback){Tuple *t=dict_find(it,key);return t?strcmp(t->value->cstring,"1")==0:fallback;}

static void inbox_received(DictionaryIterator *it,void *ctx) {
    copy_text(it,MESSAGE_KEY_GLUCOSE,s_glucose_text,sizeof(s_glucose_text));
    copy_text(it,MESSAGE_KEY_NEXT_NAME,s_next_name_text,sizeof(s_next_name_text));
    copy_text(it,MESSAGE_KEY_NEXT_DISTANCE,s_distance_text,sizeof(s_distance_text));
    copy_text(it,MESSAGE_KEY_NEXT_TIME,s_next_time_text,sizeof(s_next_time_text));
    copy_text(it,MESSAGE_KEY_CURRENT_SPEED,s_speed_text,sizeof(s_speed_text));
    copy_text(it,MESSAGE_KEY_FLAT_SPEED,s_flat_speed_text,sizeof(s_flat_speed_text));
    s_alarm_active=tuple_one(it,MESSAGE_KEY_ALARM_ACTIVE,s_alarm_active);
    s_route_valid=tuple_one(it,MESSAGE_KEY_ROUTE_VALID,s_route_valid);
    if(!s_route_valid&&!s_alarm_active){
        snprintf(s_next_name_text,sizeof(s_next_name_text),"--");
        snprintf(s_distance_text,sizeof(s_distance_text),"--");
        snprintf(s_next_time_text,sizeof(s_next_time_text),"--");
        snprintf(s_flat_speed_text,sizeof(s_flat_speed_text),"--");
    }
    dirty();
}
static void inbox_dropped(AppMessageResult r,void *ctx){APP_LOG(APP_LOG_LEVEL_WARNING,"AppMessage dropped: %d",(int)r);}

static void window_load(Window *w){
    Layer *root=window_get_root_layer(w);GRect b=layer_get_bounds(root);
    s_icon_heart=gbitmap_create_with_resource(RESOURCE_ID_ICON_HEART);
    s_icon_blood=gbitmap_create_with_resource(RESOURCE_ID_ICON_BLOOD);
    s_icon_shoe=gbitmap_create_with_resource(RESOURCE_ID_ICON_SHOE);
    s_icon_shell=gbitmap_create_with_resource(RESOURCE_ID_ICON_SHELL);
    s_dashboard_layer=layer_create(b);layer_set_update_proc(s_dashboard_layer,dashboard_update_proc);layer_add_child(root,s_dashboard_layer);
    window_set_background_color(w,GColorBlack);update_clock(NULL);update_battery(battery_state_service_peek());update_heart_rate();
}
static void window_unload(Window *w){
    layer_destroy(s_dashboard_layer);s_dashboard_layer=NULL;
    gbitmap_destroy(s_icon_heart);s_icon_heart=NULL;
    gbitmap_destroy(s_icon_blood);s_icon_blood=NULL;
    gbitmap_destroy(s_icon_shoe);s_icon_shoe=NULL;
    gbitmap_destroy(s_icon_shell);s_icon_shell=NULL;
}

static void init(void){
    s_window=window_create();window_set_window_handlers(s_window,(WindowHandlers){.load=window_load,.unload=window_unload});window_stack_push(s_window,true);
    tick_timer_service_subscribe(MINUTE_UNIT,tick_handler);battery_state_service_subscribe(battery_handler);
#if defined(PBL_HEALTH)
    s_health_subscribed=health_service_events_subscribe(health_handler,NULL);
#endif
    app_message_register_inbox_received(inbox_received);app_message_register_inbox_dropped(inbox_dropped);
    AppMessageResult r=app_message_open(256,64);if(r!=APP_MSG_OK)APP_LOG(APP_LOG_LEVEL_ERROR,"AppMessage open failed: %d",(int)r);
}
static void deinit(void){
    tick_timer_service_unsubscribe();battery_state_service_unsubscribe();
#if defined(PBL_HEALTH)
    if(s_health_subscribed)health_service_events_unsubscribe();
#endif
    window_destroy(s_window);
}
int main(void){init();app_event_loop();deinit();return 0;}

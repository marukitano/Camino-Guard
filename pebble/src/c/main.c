#include <pebble.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "ppf_digit_font.h"

/* Keep old generated build headers usable after package.json message-key changes. */
#ifndef MESSAGE_KEY_STOP_IS_GOAL
#define MESSAGE_KEY_STOP_IS_GOAL 29
#endif

#define PAGE_DASHBOARD 0
#define PAGE_TIMETABLE 1
#define PAGE_MAP 2
#define PAGE_COUNT 3
#define UNKNOWN_METRIC ((int32_t)0x80000000)

#define MAP_PAYLOAD_MAX 192
#define MAP_FRAME_WIDTH 200
#define MAP_FRAME_HEIGHT 228
#define MAP_ROAD_WIDTH 256
#define MAP_ROAD_HEIGHT 256
#define MAP_ROAD_HALF_WIDTH_M 180
#define MAP_ROAD_HALF_HEIGHT_M 180
#define MAP_ROAD_BYTES ((MAP_ROAD_WIDTH * MAP_ROAD_HEIGHT + 7) / 8)
#define MAP_ROAD_CHUNK_BYTES 160
#define MAP_ROAD_MAX_CHUNKS 64
#define MAP_ROAD_COMPRESSED_MAX (MAP_ROAD_BYTES + ((MAP_ROAD_BYTES + 127) / 128) + 8)

/* Keep the Nasu-style touch physics that already feels good on PT2. */
#define SCROLL_Q8 256
#define SCROLL_FRAME_MS 16
#define SCROLL_BREAKAWAY_PX 9
#define SCROLL_QUICK_SWIPE_MIN_PX 5
#define SCROLL_QUICK_SWIPE_MAX_MS 230
#define SCROLL_FINGER_SPRING_NUM 18
#define SCROLL_FINGER_SPRING_DEN 100
#define SCROLL_FINGER_DAMPING_NUM 68
#define SCROLL_FINGER_DAMPING_DEN 100
#define SCROLL_SNAP_SPRING_NUM 24
#define SCROLL_SNAP_SPRING_DEN 100
#define SCROLL_SNAP_DAMPING_NUM 62
#define SCROLL_SNAP_DAMPING_DEN 100
#define SCROLL_MAX_VELOCITY_Q8 (32 * SCROLL_Q8)
#define SCROLL_STOP_POSITION_Q8 (SCROLL_Q8 / 4)
#define SCROLL_STOP_VELOCITY_Q8 (SCROLL_Q8 / 4)
#define PAGE_SLOW_COMMIT_PERCENT 42
#define TIMETABLE_SLOW_SWIPE_PX 30
#define STOP_REQUEST_TIMEOUT_MS 1500
#define TIMETABLE_BOUNCE_PX 16
#define TIMETABLE_LINE_X 20
#define TIMETABLE_STOP_Y 28
#define TIMETABLE_STOP_RADIUS 6
#define TIMETABLE_CONTENT_X 38
#define TIMETABLE_NAME_Y 0
#define TIMETABLE_NAME_H 60
#define TIMETABLE_DISTANCE_Y 70
#define TIMETABLE_TIME_Y 108
#define TIMETABLE_PROGRESS_Y 146
#define TIMETABLE_TEMP_Y 184

#if defined(PBL_TOUCH)
typedef enum {
    TOUCH_AXIS_NONE = 0,
    TOUCH_AXIS_HORIZONTAL,
    TOUCH_AXIS_VERTICAL
} TouchAxis;
#endif

typedef enum {
    PAGE_SCROLL_IDLE = 0,
    PAGE_SCROLL_TOUCH,
    PAGE_SCROLL_SNAP
} PageScrollMode;

typedef struct {
    char name[48];
    char time[24];
    char distance[24];
    int32_t percent;
    bool is_goal;
    bool is_start;
} StopView;

enum DashboardIcon {
    DASH_ICON_HEART = 0,
    DASH_ICON_GLUCOSE,
    DASH_ICON_SPEED,
    DASH_ICON_TEMP,
    DASH_ICON_STEPS,
    DASH_ICON_TIME,
    DASH_ICON_ELEVATION,
    DASH_ICON_PROGRESS
};

static Window *s_window;
static Layer *s_page_layers[PAGE_COUNT];
static GBitmap *s_icon_heart;
static GBitmap *s_icon_blood;
static GBitmap *s_icon_shoe;
static GFont s_font_megafont_14;
static GFont s_font_megafont_18;

static int s_page = PAGE_DASHBOARD;
static GColor s_ink;
static char s_time_text[16] = "--:--";
static char s_glucose_text[32] = "--";
static char s_speed_text[32] = "--";
static int s_heart_rate = -1;
static int s_steps = -1;
static int32_t s_temp_current = UNKNOWN_METRIC;
static int32_t s_temp_min = UNKNOWN_METRIC;
static int32_t s_temp_max = UNKNOWN_METRIC;
static int32_t s_sunrise_minutes = UNKNOWN_METRIC;
static int32_t s_sunset_minutes = UNKNOWN_METRIC;
static int32_t s_elevation_current = UNKNOWN_METRIC;
static int32_t s_elevation_min = UNKNOWN_METRIC;
static int32_t s_elevation_max = UNKNOWN_METRIC;
static int32_t s_route_progress_percent = UNKNOWN_METRIC;

static StopView s_stop = {"--", "--", "--", -1, false, false};
static StopView s_stop_previous = {"--", "--", "--", -1, false, false};
static bool s_stop_request_pending;
static int s_stop_request_delta;
static int s_stop_queued_delta;
static AppTimer *s_stop_request_timeout_timer;
static bool s_stop_animating;
static bool s_stop_bouncing;
static bool s_stop_bounce_returning;
static int s_stop_anim_direction;
static int32_t s_stop_position_q8;
static int32_t s_stop_target_q8;
static int32_t s_stop_velocity_q8;
static AppTimer *s_stop_animation_timer;

static uint8_t s_map_payload[MAP_PAYLOAD_MAX];
static size_t s_map_payload_len;
static int s_map_position_east;
static int s_map_position_north;
static int32_t s_map_sine;
static int32_t s_map_cosine = TRIG_MAX_RATIO;
static uint8_t s_road_mask[MAP_ROAD_BYTES];
static uint8_t s_road_compressed[MAP_ROAD_COMPRESSED_MAX];
static size_t s_road_compressed_len;
static bool s_road_mask_valid;
static int32_t s_road_generation;
static int32_t s_road_receiving_generation;
static int s_road_expected_chunks;
static int s_road_received_chunks;

/*
 * Nasu-style retained image for Screen 3. While a page is sliding we draw one
 * opaque 200x228 bitmap only; the expensive map construction happens only
 * while the map is settled. Android supplies a larger 360x360 m road mask;
 * the watch samples that overscan into this fixed visible frame.
 */
static GBitmap *s_map_frame_bitmap;
static bool s_map_frame_valid;
static bool s_map_frame_dirty = true;

static GPath *s_marker_outline_path;
static GPath *s_marker_fill_path;
static GPoint s_marker_outline_points[] = {{0,-13},{-8,9},{0,5},{8,9}};
static GPoint s_marker_fill_points[] = {{0,-10},{-5,6},{0,3},{5,6}};
static const GPathInfo s_marker_outline_info = {.num_points=4,.points=s_marker_outline_points};
static const GPathInfo s_marker_fill_info = {.num_points=4,.points=s_marker_fill_points};

static PageScrollMode s_page_scroll_mode = PAGE_SCROLL_IDLE;
static int s_page_neighbor = -1;
static int s_page_direction;
static int32_t s_page_position_q8;
static int32_t s_page_target_q8;
static int32_t s_page_velocity_q8;
static AppTimer *s_page_scroll_timer;

#if defined(PBL_HEALTH)
static bool s_health_subscribed;
static AppTimer *s_heart_rate_timer;
#endif

#if defined(PBL_COMPASS)
static bool s_compass_subscribed;
static bool s_heading_valid;
static CompassHeading s_heading;
#endif

#if defined(PBL_TOUCH)
static bool s_touch_subscribed;
static bool s_touch_active;
static int16_t s_touch_start_x;
static int16_t s_touch_start_y;
static int16_t s_touch_total_x;
static int16_t s_touch_total_y;
static uint32_t s_touch_start_time_ms;
static TouchAxis s_touch_axis;
#endif

static int clamp_i(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
static int32_t clamp_symmetric_i32(int32_t v,int32_t m){return v>m?m:(v<-m?-m:v);}
static int32_t abs_i32(int32_t v){return v<0?-v:v;}

static uint32_t current_time_ms(void){
    time_t s=0; uint16_t ms=0; time_ms(&s,&ms);
    return (uint32_t)((uint32_t)s*1000u+ms);
}

static void dirty(void){
    for(int i=0;i<PAGE_COUNT;i++) if(s_page_layers[i]&&!layer_get_hidden(s_page_layers[i])) layer_mark_dirty(s_page_layers[i]);
}

static bool metric_known(int32_t value){return value!=UNKNOWN_METRIC;}
static int fraction_between(int32_t value,int32_t low,int32_t high){
    if(!metric_known(value)||!metric_known(low)||!metric_known(high)||high<=low) return 0;
    return clamp_i((int)(((int64_t)value-low)*1000LL/((int64_t)high-low)),0,1000);
}

static bool parse_tenths(const char *text,int *value){
    if(!text||!value) return false;
    int whole=0,frac=0; bool digit=false,decimal=false;
    for(const char *p=text;*p;p++){
        if(*p>='0'&&*p<='9'){
            digit=true;
            if(!decimal) whole=whole*10+(*p-'0'); else {frac=*p-'0';break;}
        }else if((*p=='.'||*p==',')&&digit) decimal=true;
        else if(digit) break;
    }
    if(!digit) return false; *value=whole*10+frac; return true;
}

static int parse_age_minutes(const char *text){
    if(!text) return -1; const char *m=strstr(text," min"); if(!m) return -1;
    const char *p=m; while(p>text&&p[-1]>='0'&&p[-1]<='9') p--; if(p==m) return -1;
    int age=0; for(;p<m;p++){age=age*10+(*p-'0');if(age>9999)return 9999;} return age;
}

static void format_age_short(int age,char *dst,size_t n){
    if(!dst||n==0)return; if(age<0){dst[0]='\0';return;}
    if(age<60)snprintf(dst,n,"%dm",age);else snprintf(dst,n,"%dh",clamp_i(age/60,0,999));
}

static const uint8_t *glyph(char c){
    static const uint8_t blank[7]={0,0,0,0,0,0,0},q[7]={14,17,1,2,4,0,4};
    switch(c){
        case 'A':{static const uint8_t r[7]={14,17,17,31,17,17,17};return r;}
        case 'B':{static const uint8_t r[7]={30,17,17,30,17,17,30};return r;}
        case 'C':{static const uint8_t r[7]={14,17,16,16,16,17,14};return r;}
        case 'D':{static const uint8_t r[7]={30,17,17,17,17,17,30};return r;}
        case 'E':{static const uint8_t r[7]={31,16,16,30,16,16,31};return r;}
        case 'F':{static const uint8_t r[7]={31,16,16,30,16,16,16};return r;}
        case 'G':{static const uint8_t r[7]={14,17,16,23,17,17,15};return r;}
        case 'H':{static const uint8_t r[7]={17,17,17,31,17,17,17};return r;}
        case 'I':{static const uint8_t r[7]={14,4,4,4,4,4,14};return r;}
        case 'J':{static const uint8_t r[7]={7,2,2,2,18,18,12};return r;}
        case 'K':{static const uint8_t r[7]={17,18,20,24,20,18,17};return r;}
        case 'L':{static const uint8_t r[7]={16,16,16,16,16,16,31};return r;}
        case 'M':{static const uint8_t r[7]={17,27,21,21,17,17,17};return r;}
        case 'N':{static const uint8_t r[7]={17,25,21,19,17,17,17};return r;}
        case 'O':{static const uint8_t r[7]={14,17,17,17,17,17,14};return r;}
        case 'P':{static const uint8_t r[7]={30,17,17,30,16,16,16};return r;}
        case 'Q':{static const uint8_t r[7]={14,17,17,17,21,18,13};return r;}
        case 'R':{static const uint8_t r[7]={30,17,17,30,20,18,17};return r;}
        case 'S':{static const uint8_t r[7]={15,16,16,14,1,1,30};return r;}
        case 'T':{static const uint8_t r[7]={31,4,4,4,4,4,4};return r;}
        case 'U':{static const uint8_t r[7]={17,17,17,17,17,17,14};return r;}
        case 'V':{static const uint8_t r[7]={17,17,17,17,17,10,4};return r;}
        case 'W':{static const uint8_t r[7]={17,17,17,21,21,21,10};return r;}
        case 'X':{static const uint8_t r[7]={17,17,10,4,10,17,17};return r;}
        case 'Y':{static const uint8_t r[7]={17,17,10,4,4,4,4};return r;}
        case 'Z':{static const uint8_t r[7]={31,1,2,4,8,16,31};return r;}
        case '0':{static const uint8_t r[7]={14,17,19,21,25,17,14};return r;}
        case '1':{static const uint8_t r[7]={4,12,4,4,4,4,14};return r;}
        case '2':{static const uint8_t r[7]={14,17,1,2,4,8,31};return r;}
        case '3':{static const uint8_t r[7]={30,1,1,14,1,1,30};return r;}
        case '4':{static const uint8_t r[7]={2,6,10,18,31,2,2};return r;}
        case '5':{static const uint8_t r[7]={31,16,16,30,1,1,30};return r;}
        case '6':{static const uint8_t r[7]={14,16,16,30,17,17,14};return r;}
        case '7':{static const uint8_t r[7]={31,1,2,4,8,8,8};return r;}
        case '8':{static const uint8_t r[7]={14,17,17,14,17,17,14};return r;}
        case '9':{static const uint8_t r[7]={14,17,17,15,1,1,14};return r;}
        case '.':{static const uint8_t r[7]={0,0,0,0,0,12,12};return r;}
        case ':':{static const uint8_t r[7]={0,12,12,0,12,12,0};return r;}
        case '-':{static const uint8_t r[7]={0,0,0,31,0,0,0};return r;}
        case '/':{static const uint8_t r[7]={1,2,2,4,8,8,16};return r;}
        case '%':{static const uint8_t r[7]={17,2,4,8,16,0,17};return r;}
        case ' ':return blank; default:return q;
    }
}

static uint32_t codepoint(const char **p){
    const unsigned char *s=(const unsigned char *)*p;
    if(s[0]<0x80){(*p)++;return s[0];}
    if((s[0]&0xE0)==0xC0&&s[1]){uint32_t c=((s[0]&31)<<6)|(s[1]&63);*p+=2;return c;}
    if((s[0]&0xF0)==0xE0&&s[1]&&s[2]){uint32_t c=((s[0]&15)<<12)|((s[1]&63)<<6)|(s[2]&63);*p+=3;return c;}
    (*p)++;return '?';
}

static char norm(uint32_t c,bool *umlaut){
    *umlaut=false;if(c>='a'&&c<='z')return(char)(c-'a'+'A');if(c<128)return(char)c;
    if(c==0xC4||c==0xE4){*umlaut=true;return'A';}if(c==0xD6||c==0xF6){*umlaut=true;return'O';}if(c==0xDC||c==0xFC){*umlaut=true;return'U';}
    if((c>=0xC0&&c<=0xC5)||(c>=0xE0&&c<=0xE5))return'A';if(c==0xC7||c==0xE7)return'C';
    if((c>=0xC8&&c<=0xCB)||(c>=0xE8&&c<=0xEB))return'E';if((c>=0xCC&&c<=0xCF)||(c>=0xEC&&c<=0xEF))return'I';
    if(c==0xD1||c==0xF1)return'N';if((c>=0xD2&&c<=0xD6)||(c>=0xF2&&c<=0xF6))return'O';if((c>=0xD9&&c<=0xDC)||(c>=0xF9&&c<=0xFC))return'U';return'?';
}

static void megafont_text(const char *src,char *dst,size_t n){
    if(!dst||!n)return;const char *p=src?src:"";size_t i=0;
    while(*p&&i+1<n){bool uml=false;char c=norm(codepoint(&p),&uml);dst[i++]=((c>='A'&&c<='Z')||(c>='0'&&c<='9')||c==' '||c=='-')?c:' ';}dst[i]='\0';
}

static int text_w(const char *text,int pitch){int n=0;const char*p=text;while(p&&*p){codepoint(&p);n++;}return n?n*6*pitch-pitch:0;}
static void dot_text(GContext *ctx,const char *text,GRect r,int pitch,GTextAlignment align){
    if(!text)return;int w=text_w(text,pitch),x=r.origin.x;if(align==GTextAlignmentCenter)x+=(r.size.w-w)/2;else if(align==GTextAlignmentRight)x+=r.size.w-w;
    int y=r.origin.y;graphics_context_set_fill_color(ctx,s_ink);graphics_context_set_stroke_color(ctx,s_ink);const char*p=text;
    while(*p){bool uml=false;char c=norm(codepoint(&p),&uml);const uint8_t*rows=glyph(c);if(uml){graphics_draw_pixel(ctx,GPoint(x+pitch,y));graphics_draw_pixel(ctx,GPoint(x+3*pitch,y));}
        for(int yy=0;yy<7;yy++)for(int xx=0;xx<5;xx++)if(rows[yy]&(1<<(4-xx))){if(pitch>=3)graphics_fill_circle(ctx,GPoint(x+xx*pitch,y+2+yy*pitch),1);else graphics_draw_pixel(ctx,GPoint(x+xx*pitch,y+1+yy*pitch));}x+=6*pitch;}
}

static void draw_bitmap_icon(GContext *ctx,GBitmap *bitmap,GRect r){if(!bitmap)return;graphics_context_set_compositing_mode(ctx,GCompOpSet);graphics_draw_bitmap_in_rect(ctx,bitmap,r);}
static void draw_temperature_icon(GContext *ctx,GRect r){graphics_context_set_stroke_color(ctx,GColorBlack);graphics_context_set_fill_color(ctx,GColorBlack);graphics_context_set_stroke_width(ctx,2);graphics_draw_round_rect(ctx,GRect(r.origin.x+8,r.origin.y+2,5,12),2);graphics_fill_circle(ctx,GPoint(r.origin.x+10,r.origin.y+15),4);graphics_draw_line(ctx,GPoint(r.origin.x+10,r.origin.y+6),GPoint(r.origin.x+10,r.origin.y+15));graphics_context_set_stroke_width(ctx,1);}
static void draw_steps_icon(GContext *ctx,GRect r){graphics_context_set_fill_color(ctx,GColorBlack);graphics_fill_rect(ctx,GRect(r.origin.x+4,r.origin.y+3,6,9),2,GCornersAll);graphics_fill_circle(ctx,GPoint(r.origin.x+6,r.origin.y+14),2);graphics_fill_rect(ctx,GRect(r.origin.x+12,r.origin.y+9,6,8),2,GCornersAll);graphics_fill_circle(ctx,GPoint(r.origin.x+16,r.origin.y+5),2);}
static void draw_clock_icon(GContext *ctx,GRect r){graphics_context_set_stroke_color(ctx,GColorBlack);graphics_context_set_stroke_width(ctx,2);GPoint c=GPoint(r.origin.x+10,r.origin.y+10);graphics_draw_circle(ctx,c,8);graphics_draw_line(ctx,c,GPoint(c.x,c.y-5));graphics_draw_line(ctx,c,GPoint(c.x+4,c.y+2));graphics_context_set_stroke_width(ctx,1);}
static void draw_elevation_icon(GContext *ctx,GRect r){graphics_context_set_stroke_color(ctx,GColorBlack);graphics_context_set_stroke_width(ctx,2);graphics_draw_line(ctx,GPoint(r.origin.x+1,r.origin.y+17),GPoint(r.origin.x+8,r.origin.y+7));graphics_draw_line(ctx,GPoint(r.origin.x+8,r.origin.y+7),GPoint(r.origin.x+12,r.origin.y+12));graphics_draw_line(ctx,GPoint(r.origin.x+12,r.origin.y+12),GPoint(r.origin.x+16,r.origin.y+4));graphics_draw_line(ctx,GPoint(r.origin.x+16,r.origin.y+4),GPoint(r.origin.x+20,r.origin.y+17));graphics_context_set_stroke_width(ctx,1);}
static void draw_progress_icon(GContext *ctx,GRect r){graphics_context_set_stroke_color(ctx,GColorBlack);graphics_context_set_fill_color(ctx,GColorBlack);graphics_context_set_stroke_width(ctx,2);graphics_draw_line(ctx,GPoint(r.origin.x+5,r.origin.y+16),GPoint(r.origin.x+15,r.origin.y+4));graphics_fill_circle(ctx,GPoint(r.origin.x+6,r.origin.y+5),2);graphics_fill_circle(ctx,GPoint(r.origin.x+14,r.origin.y+15),2);graphics_context_set_stroke_width(ctx,1);}
static void draw_dashboard_icon(GContext *ctx,int kind,int y){GRect r=GRect(4,y,20,20);if(kind==DASH_ICON_HEART)draw_bitmap_icon(ctx,s_icon_heart,r);else if(kind==DASH_ICON_GLUCOSE)draw_bitmap_icon(ctx,s_icon_blood,r);else if(kind==DASH_ICON_SPEED)draw_bitmap_icon(ctx,s_icon_shoe,r);else if(kind==DASH_ICON_TEMP)draw_temperature_icon(ctx,r);else if(kind==DASH_ICON_STEPS)draw_steps_icon(ctx,r);else if(kind==DASH_ICON_TIME)draw_clock_icon(ctx,r);else if(kind==DASH_ICON_ELEVATION)draw_elevation_icon(ctx,r);else draw_progress_icon(ctx,r);}

static GColor glucose_bar_color(int v){if(v<30)return GColorRed;if(v<39)return GColorYellow;if(v<=100)return GColorGreen;if(v<140)return GColorYellow;return GColorRed;}
static GColor heart_rate_bar_color(int bpm){int a=180*70/100,b=180*85/100;if(bpm<a)return GColorGreen;if(bpm<=b)return GColorYellow;return GColorRed;}
static int metric_bar(GContext *ctx,int y,int fraction,GColor color,GRect b,int limit){const int min_w=30,value_lane=84;int base=b.size.w-value_lane,w=base*clamp_i(fraction,0,1000)/1000;if(w<min_w)w=min_w;int cap=limit>0?limit:base;if(cap>base)cap=base;if(cap<min_w)cap=min_w;if(w>cap)w=cap;graphics_context_set_fill_color(ctx,color);graphics_fill_rect(ctx,GRect(0,y,w,PPF_VALUE_HEIGHT),0,GCornerNone);return w;}
static void live_row(GContext *ctx,int y,int kind,const char *value,int fraction,GColor color,GRect b,const char *suffix){int row_y=y+2,value_w=ppf_value_width(value),suffix_w=suffix&&suffix[0]?text_w(suffix,1):0,suffix_gap=suffix_w?2:0;int limit=b.size.w-2-value_w-suffix_gap-suffix_w-4;if(limit<30)limit=30;int bar_w=metric_bar(ctx,row_y,fraction,color,b,limit);draw_dashboard_icon(ctx,kind,row_y);int value_x=bar_w+2;ppf_draw_value(ctx,value,value_x+value_w,row_y,GColorWhite);if(suffix_w){int x=value_x+value_w+suffix_gap;s_ink=GColorWhite;dot_text(ctx,suffix,GRect(x,row_y+6,b.size.w-x,12),1,GTextAlignmentLeft);}}
static void format_int_value(int32_t v,char*d,size_t n){if(!d||!n)return;if(!metric_known(v))snprintf(d,n,"--");else snprintf(d,n,"%ld",(long)v);}

static void update_clock(struct tm*t){struct tm local;if(!t){time_t now=time(NULL);local=*localtime(&now);t=&local;}if(clock_is_24h_style())strftime(s_time_text,sizeof(s_time_text),"%H:%M",t);else{strftime(s_time_text,sizeof(s_time_text),"%I:%M",t);if(s_time_text[0]=='0')memmove(s_time_text,s_time_text+1,strlen(s_time_text));}}
static int current_minutes_of_day(void){time_t now=time(NULL);struct tm*l=localtime(&now);return l?l->tm_hour*60+l->tm_min:0;}
static void update_steps(void){
#if defined(PBL_HEALTH)
    HealthValue v=health_service_sum_today(HealthMetricStepCount);s_steps=v>=0?(int)v:-1;
#else
    s_steps=-1;
#endif
}
static void update_heart_rate(void){
#if defined(PBL_HEALTH)
    HealthValue v=health_service_peek_current_value(HealthMetricHeartRateRawBPM);s_heart_rate=v>0?(int)v:-1;
#else
    s_heart_rate=-1;
#endif
}

#if defined(PBL_HEALTH)
static void heart_rate_timer_handler(void*c){s_heart_rate_timer=NULL;if(s_page==PAGE_DASHBOARD){update_heart_rate();dirty();s_heart_rate_timer=app_timer_register(1000,heart_rate_timer_handler,NULL);}}
static void update_heart_rate_sampling(void){if(s_page==PAGE_DASHBOARD){health_service_set_heart_rate_sample_period(1);update_heart_rate();if(!s_heart_rate_timer)s_heart_rate_timer=app_timer_register(1000,heart_rate_timer_handler,NULL);}else{if(s_heart_rate_timer){app_timer_cancel(s_heart_rate_timer);s_heart_rate_timer=NULL;}health_service_set_heart_rate_sample_period(0);}}
static void health_handler(HealthEventType e,void*c){if(e==HealthEventHeartRateUpdate&&s_page==PAGE_DASHBOARD)update_heart_rate();if(e==HealthEventMovementUpdate||e==HealthEventSignificantUpdate){update_steps();if(s_page==PAGE_DASHBOARD)update_heart_rate();}dirty();}
#endif

#if defined(PBL_COMPASS)
static void compass_handler(CompassHeadingData data){
    bool valid=data.compass_status==CompassStatusCalibrating||data.compass_status==CompassStatusCalibrated;
    CompassHeading h=data.magnetic_heading;
    if(!valid||h<0||h>=TRIG_MAX_ANGLE)return;
    s_heading=h;
    s_heading_valid=true;
    s_map_frame_dirty=true;
    bool can_redraw=s_page==PAGE_MAP&&s_page_scroll_mode==PAGE_SCROLL_IDLE;
#if defined(PBL_TOUCH)
    can_redraw=can_redraw&&!s_touch_active;
#endif
    if(can_redraw&&s_page_layers[PAGE_MAP])layer_mark_dirty(s_page_layers[PAGE_MAP]);
}
static void start_compass_sampling(void){
    if(s_compass_subscribed)return;
    compass_service_set_heading_filter(DEG_TO_TRIGANGLE(5));
    compass_service_subscribe(compass_handler);
    s_compass_subscribed=true;
}
static void stop_compass_sampling(void){if(s_compass_subscribed){compass_service_unsubscribe();s_compass_subscribed=false;}}
#else
static void start_compass_sampling(void){}
static void stop_compass_sampling(void){}
#endif
static void update_compass_sampling(void){if(s_page==PAGE_MAP)start_compass_sampling();else stop_compass_sampling();}

static void draw_dashboard(GContext *ctx,GRect b){
    char heart[16]="--",glucose[16]="--",age[8]="",speed[16]="--",temp[16]="--",steps[16]="--",elev[16]="--",progress[16]="--";
    if(s_heart_rate>0)snprintf(heart,sizeof(heart),"%d",s_heart_rate);int gt=0,st=0;bool hg=parse_tenths(s_glucose_text,&gt),hs=parse_tenths(s_speed_text,&st);
    if(hg)snprintf(glucose,sizeof(glucose),"%d.%d",gt/10,gt%10);if(hs)snprintf(speed,sizeof(speed),"%d.%d",st/10,st%10);format_age_short(parse_age_minutes(s_glucose_text),age,sizeof(age));
    if(metric_known(s_temp_current)){int r=s_temp_current>=0?(s_temp_current+5)/10:(s_temp_current-5)/10;snprintf(temp,sizeof(temp),"%d",r);}if(s_steps>=0)snprintf(steps,sizeof(steps),"%d",s_steps);format_int_value(s_elevation_current,elev,sizeof(elev));if(metric_known(s_route_progress_percent))snprintf(progress,sizeof(progress),"%ld",(long)clamp_i((int)s_route_progress_percent,0,100));
    int hf=s_heart_rate>0?(clamp_i(s_heart_rate,40,180)-40)*1000/140:0,gf=hg?(clamp_i(gt,20,140)-20)*1000/120:0,sf=hs?clamp_i(st,0,80)*1000/80:0,tf=fraction_between(s_temp_current,s_temp_min,s_temp_max),stepf=s_steps<0?0:clamp_i(s_steps,0,10000)*1000/10000,timef=fraction_between(current_minutes_of_day(),s_sunrise_minutes,s_sunset_minutes),elevf=fraction_between(s_elevation_current,s_elevation_min,s_elevation_max),pf=metric_known(s_route_progress_percent)?clamp_i((int)s_route_progress_percent,0,100)*10:0;
    live_row(ctx,1,DASH_ICON_HEART,heart,hf,s_heart_rate>0?heart_rate_bar_color(s_heart_rate):GColorRed,b,NULL);live_row(ctx,29,DASH_ICON_GLUCOSE,glucose,gf,hg?glucose_bar_color(gt):GColorGreen,b,age);live_row(ctx,57,DASH_ICON_SPEED,speed,sf,GColorBlue,b,NULL);live_row(ctx,85,DASH_ICON_TEMP,temp,tf,GColorOrange,b,NULL);live_row(ctx,113,DASH_ICON_STEPS,steps,stepf,GColorGreen,b,NULL);live_row(ctx,141,DASH_ICON_TIME,s_time_text,timef,GColorYellow,b,NULL);live_row(ctx,169,DASH_ICON_ELEVATION,elev,elevf,GColorCyan,b,NULL);live_row(ctx,197,DASH_ICON_PROGRESS,progress,pf,GColorBlue,b,"%");
}

static void draw_timetable_value(GContext*ctx,GRect b,const char*value,int y,const char*suffix){
    const char*shown=value&&value[0]?value:"--";
    int vw=ppf_value_width(shown);
    int x=TIMETABLE_CONTENT_X;
    ppf_draw_value(ctx,shown,x+vw,y,GColorWhite);
    if(suffix&&suffix[0]){
        s_ink=GColorWhite;
        dot_text(ctx,suffix,GRect(x+vw+5,y+6,b.size.w-(x+vw+5)-4,12),1,GTextAlignmentLeft);
    }
}

static void draw_timetable_view(GContext*ctx,GRect b,const StopView*v,int off){
    StopView fallback={"--","--","--",-1,false,false};
    if(!v)v=&fallback;

    GColor timeline=GColorFromHEX(0xFFDD69);
    int line_x=b.origin.x+TIMETABLE_LINE_X;
    int stop_y=b.origin.y+TIMETABLE_STOP_Y+off;
    int top=b.origin.y+off;
    int bottom=b.origin.y+b.size.h-1+off;

    graphics_context_set_antialiased(ctx,true);
    graphics_context_set_stroke_color(ctx,timeline);
    graphics_context_set_fill_color(ctx,timeline);
    graphics_context_set_stroke_width(ctx,2);

    /* Start has no past below; goal has no future above. */
    if(!v->is_goal){
        graphics_draw_line(ctx,GPoint(line_x,top),GPoint(line_x,stop_y-TIMETABLE_STOP_RADIUS));
    }
    if(!v->is_start){
        graphics_draw_line(ctx,GPoint(line_x,stop_y+TIMETABLE_STOP_RADIUS),GPoint(line_x,bottom));
    }

    graphics_fill_circle(ctx,GPoint(line_x,stop_y),TIMETABLE_STOP_RADIUS);
    if(!v->is_goal&&!v->is_start){
        graphics_context_set_fill_color(ctx,GColorBlack);
        graphics_fill_circle(ctx,GPoint(line_x,stop_y),TIMETABLE_STOP_RADIUS-2);
    }
    graphics_context_set_stroke_width(ctx,1);

    char name[48];
    megafont_text(v->name,name,sizeof(name));
    graphics_context_set_text_color(ctx,GColorWhite);
    GRect name_box=GRect(TIMETABLE_CONTENT_X,TIMETABLE_NAME_Y+off,b.size.w-TIMETABLE_CONTENT_X-5,TIMETABLE_NAME_H);
    GFont f=fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
    GSize name_size=graphics_text_layout_get_content_size(name,f,name_box,GTextOverflowModeWordWrap,GTextAlignmentLeft);
    if(name_size.h>TIMETABLE_NAME_H)f=fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
    graphics_draw_text(ctx,name,f,name_box,GTextOverflowModeWordWrap,GTextAlignmentLeft,NULL);

    draw_timetable_value(ctx,b,v->distance,TIMETABLE_DISTANCE_Y+off,"KM");
    draw_timetable_value(ctx,b,v->time,TIMETABLE_TIME_Y+off,NULL);

    char p[16]="--";
    if(v->percent>=0)snprintf(p,sizeof(p),"%ld",(long)clamp_i((int)v->percent,0,100));
    draw_timetable_value(ctx,b,p,TIMETABLE_PROGRESS_Y+off,"%");

    char temp[16]="--";
    if(metric_known(s_temp_current)){
        int rounded=s_temp_current>=0?(s_temp_current+5)/10:(s_temp_current-5)/10;
        snprintf(temp,sizeof(temp),"%d",rounded);
    }
    draw_timetable_value(ctx,b,temp,TIMETABLE_TEMP_Y+off,"C");
}

static int stop_offset_px(void){return (int)((s_stop_position_q8+(s_stop_position_q8>=0?SCROLL_Q8/2:-SCROLL_Q8/2))/SCROLL_Q8);}
static void draw_timetable(GContext*ctx,GRect b){
    if(!s_stop_animating){draw_timetable_view(ctx,b,&s_stop,0);return;}
    int off=stop_offset_px();
    if(s_stop_bouncing){draw_timetable_view(ctx,b,&s_stop,off);return;}
    draw_timetable_view(ctx,b,&s_stop_previous,off);
    draw_timetable_view(ctx,b,&s_stop,off+s_stop_anim_direction*b.size.h);
}

static void update_map_position_from_payload(void){
    s_map_position_east=0;
    s_map_position_north=0;
    if(s_map_payload_len>=5&&s_map_payload[0]==2){
        s_map_position_east=(int8_t)s_map_payload[3];
        s_map_position_north=(int8_t)s_map_payload[4];
    }
}

static void prepare_map_transform(void){
    s_map_sine=0;
    s_map_cosine=TRIG_MAX_RATIO;
#if defined(PBL_COMPASS)
    if(s_heading_valid){
        int32_t clockwise=TRIG_MAX_ANGLE-s_heading;
        if(clockwise>=TRIG_MAX_ANGLE)clockwise-=TRIG_MAX_ANGLE;
        s_map_sine=sin_lookup(clockwise);
        s_map_cosine=cos_lookup(clockwise);
    }
#endif
}

static GPoint map_point(GRect b,int east,int north){
    int local_east=east-s_map_position_east;
    int local_north=north-s_map_position_north;
    int right=(int)(((int64_t)local_east*s_map_cosine-(int64_t)local_north*s_map_sine)/TRIG_MAX_RATIO);
    int forward=(int)(((int64_t)local_east*s_map_sine+(int64_t)local_north*s_map_cosine)/TRIG_MAX_RATIO);
    int cx=b.origin.x+b.size.w/2;
    int cy=b.origin.y+b.size.h/2;
    return GPoint(cx+right,cy-forward);
}

static bool road_mask_at(int x,int y){
    if(x<0||y<0||x>=MAP_ROAD_WIDTH||y>=MAP_ROAD_HEIGHT)return false;
    size_t bit=(size_t)y*MAP_ROAD_WIDTH+(size_t)x;
    return (s_road_mask[bit>>3]&(1u<<(bit&7)))!=0;
}

static bool road_mask_thick_at(int x,int y){return road_mask_at(x,y)||road_mask_at(x-1,y)||road_mask_at(x+1,y)||road_mask_at(x,y-1)||road_mask_at(x,y+1);}

static void draw_road_bitmap(GContext*ctx,GRect b){
    if(!s_road_mask_valid||!s_page_layers[PAGE_MAP])return;
    GBitmap*framebuffer=graphics_capture_frame_buffer_format(ctx,GBitmapFormat8Bit);
    if(!framebuffer)return;
    uint8_t*data=gbitmap_get_data(framebuffer);
    int stride=gbitmap_get_bytes_per_row(framebuffer);
    GRect fbounds=gbitmap_get_bounds(framebuffer);
    GRect frame=layer_get_frame(s_page_layers[PAGE_MAP]);
    int left=frame.origin.x+b.origin.x-fbounds.origin.x;
    int top=frame.origin.y+b.origin.y-fbounds.origin.y;
    if(!data||left<0||top<0||left+b.size.w>fbounds.size.w||top+b.size.h>fbounds.size.h){graphics_release_frame_buffer(ctx,framebuffer);return;}
    const int64_t ratio=TRIG_MAX_RATIO,q16=65536;
    const int64_t road_x_denom=2LL*MAP_ROAD_HALF_WIDTH_M*ratio,road_y_denom=2LL*MAP_ROAD_HALF_HEIGHT_M*ratio;
    const int64_t sx_step=((int64_t)s_map_cosine*(MAP_ROAD_WIDTH-1)*q16)/road_x_denom;
    const int64_t sy_step=((int64_t)s_map_sine*(MAP_ROAD_HEIGHT-1)*q16)/road_y_denom;
    const int cx=b.size.w/2,cy=b.size.h/2;const uint8_t road_color=GColorDarkGray.argb;
    for(int y=0;y<b.size.h;y++){
        int right=-cx,forward=cy-y;
        int64_t east_num=(int64_t)right*s_map_cosine+(int64_t)forward*s_map_sine+(int64_t)s_map_position_east*ratio;
        int64_t north_num=-(int64_t)right*s_map_sine+(int64_t)forward*s_map_cosine+(int64_t)s_map_position_north*ratio;
        int64_t sx_q16=((east_num+(int64_t)MAP_ROAD_HALF_WIDTH_M*ratio)*(MAP_ROAD_WIDTH-1)*q16)/road_x_denom;
        int64_t sy_q16=(((int64_t)MAP_ROAD_HALF_HEIGHT_M*ratio-north_num)*(MAP_ROAD_HEIGHT-1)*q16)/road_y_denom;
        uint8_t*row=data+(top+y)*stride+left;
        for(int x=0;x<b.size.w;x++){
            int sx=(int)((sx_q16+q16/2)/q16),sy=(int)((sy_q16+q16/2)/q16);
            if(road_mask_thick_at(sx,sy))row[x]=road_color;
            sx_q16+=sx_step;sy_q16+=sy_step;
        }
    }
    graphics_release_frame_buffer(ctx,framebuffer);
}

static void draw_distance_grid(GContext*ctx,GRect b){
    int cx=b.origin.x+b.size.w/2,cy=b.origin.y+b.size.h/2;
    graphics_context_set_stroke_color(ctx,GColorDarkGray);graphics_context_set_stroke_width(ctx,1);
    for(int m=-100;m<=100;m+=25){int x=cx+m;if(x>=b.origin.x&&x<b.origin.x+b.size.w)graphics_draw_line(ctx,GPoint(x,b.origin.y),GPoint(x,b.origin.y+b.size.h-1));}
    for(int m=-100;m<=100;m+=25){int y=cy-m;if(y>=b.origin.y&&y<b.origin.y+b.size.h)graphics_draw_line(ctx,GPoint(b.origin.x,y),GPoint(b.origin.x+b.size.w-1,y));}
}

static void draw_map_polyline(GContext*ctx,GRect b,size_t offset,int pairs,bool breaks,GColor color,int width){
    bool have=false;GPoint prev=GPoint(0,0);int radius=width>1?(width+1)/2:0;
    graphics_context_set_antialiased(ctx,true);graphics_context_set_stroke_color(ctx,color);graphics_context_set_fill_color(ctx,color);graphics_context_set_stroke_width(ctx,width);
    for(int i=0;i<pairs;i++){
        if(offset+1>=s_map_payload_len)break;
        int east=(int8_t)s_map_payload[offset++],north=(int8_t)s_map_payload[offset++];
        if(breaks&&east==-128&&north==-128){have=false;continue;}
        GPoint p=map_point(b,east,north);if(have)graphics_draw_line(ctx,prev,p);if(radius>0)graphics_fill_circle(ctx,p,radius);prev=p;have=true;
    }
    graphics_context_set_stroke_width(ctx,1);
}

static void draw_position_marker(GContext*ctx,GPoint p){graphics_context_set_fill_color(ctx,GColorWhite);graphics_fill_circle(ctx,p,7);graphics_context_set_fill_color(ctx,GColorRed);graphics_fill_circle(ctx,p,4);}

static void draw_map(GContext*ctx,GRect b){
    graphics_context_set_antialiased(ctx,true);update_map_position_from_payload();prepare_map_transform();draw_road_bitmap(ctx,b);
    GColor camino_outline=GColorFromHEX(0x000055),camino_core=GColorFromHEX(0x55AAFF);
    if(s_map_payload_len>=5&&s_map_payload[0]==2){
        int rp=s_map_payload[1],tp=s_map_payload[2];size_t ro=5,to=ro+(size_t)rp*2,need=to+(size_t)tp*2;
        if(need<=s_map_payload_len){draw_map_polyline(ctx,b,ro,rp,true,camino_outline,11);draw_map_polyline(ctx,b,ro,rp,true,camino_core,5);draw_map_polyline(ctx,b,to,tp,false,GColorRed,3);}
    }else if(s_map_payload_len>=3&&s_map_payload[0]==1){
        int rp=s_map_payload[1],tp=s_map_payload[2];size_t ro=3,to=ro+(size_t)rp*2,need=to+(size_t)tp*2;
        if(need<=s_map_payload_len){draw_map_polyline(ctx,b,ro,rp,true,camino_outline,11);draw_map_polyline(ctx,b,ro,rp,true,camino_core,5);draw_map_polyline(ctx,b,to,tp,false,GColorRed,3);}
    }
    draw_distance_grid(ctx,b);draw_position_marker(ctx,GPoint(b.origin.x+b.size.w/2,b.origin.y+b.size.h/2));
}

static bool capture_map_frame(GContext*ctx,GRect b){
    if(!s_map_frame_bitmap||!s_page_layers[PAGE_MAP])return false;GBitmap*framebuffer=graphics_capture_frame_buffer_format(ctx,GBitmapFormat8Bit);if(!framebuffer)return false;
    uint8_t*src=gbitmap_get_data(framebuffer),*dst=gbitmap_get_data(s_map_frame_bitmap);int src_stride=gbitmap_get_bytes_per_row(framebuffer),dst_stride=gbitmap_get_bytes_per_row(s_map_frame_bitmap);GRect fbounds=gbitmap_get_bounds(framebuffer),frame=layer_get_frame(s_page_layers[PAGE_MAP]);int sx=frame.origin.x+b.origin.x-fbounds.origin.x,sy=frame.origin.y+b.origin.y-fbounds.origin.y;bool ok=src&&dst&&sx>=0&&sy>=0&&sx+b.size.w<=fbounds.size.w&&sy+b.size.h<=fbounds.size.h&&b.size.w<=MAP_FRAME_WIDTH&&b.size.h<=MAP_FRAME_HEIGHT;
    if(ok)for(int y=0;y<b.size.h;y++)memcpy(dst+y*dst_stride,src+(sy+y)*src_stride+sx,b.size.w);graphics_release_frame_buffer(ctx,framebuffer);if(ok){s_map_frame_valid=true;s_map_frame_dirty=false;}return ok;
}

static bool draw_cached_map_frame(GContext*ctx,GRect b){if(!s_map_frame_bitmap||!s_map_frame_valid)return false;graphics_context_set_compositing_mode(ctx,GCompOpAssign);graphics_draw_bitmap_in_rect(ctx,s_map_frame_bitmap,b);return true;}
static void page_background(GContext*ctx,GRect b){graphics_context_set_fill_color(ctx,GColorBlack);graphics_fill_rect(ctx,b,0,GCornerNone);s_ink=GColorWhite;}
static void dashboard_update_proc(Layer*l,GContext*c){GRect b=layer_get_bounds(l);page_background(c,b);draw_dashboard(c,b);}
static void timetable_update_proc(Layer*l,GContext*c){GRect b=layer_get_bounds(l);page_background(c,b);draw_timetable(c,b);}
static void map_update_proc(Layer*l,GContext*c){
    GRect b=layer_get_bounds(l);bool settled=s_page==PAGE_MAP&&s_page_scroll_mode==PAGE_SCROLL_IDLE&&layer_get_frame(l).origin.x==0;
#if defined(PBL_TOUCH)
    settled=settled&&!s_touch_active;
#endif
    if(!settled){if(draw_cached_map_frame(c,b))return;page_background(c,b);draw_distance_grid(c,b);draw_position_marker(c,GPoint(b.origin.x+b.size.w/2,b.origin.y+b.size.h/2));return;}
    if(!s_map_frame_dirty&&draw_cached_map_frame(c,b))return;page_background(c,b);draw_map(c,b);capture_map_frame(c,b);
}

static void send_control(uint32_t key,int32_t value){DictionaryIterator*it=NULL;AppMessageResult r=app_message_outbox_begin(&it);if(r!=APP_MSG_OK||!it){APP_LOG(APP_LOG_LEVEL_WARNING,"Control outbox begin failed: %d",(int)r);return;}DictionaryResult d=dict_write_int32(it,key,value);if(d!=DICT_OK){APP_LOG(APP_LOG_LEVEL_WARNING,"Control dict write failed: %d",(int)d);return;}r=app_message_outbox_send();if(r!=APP_MSG_OK)APP_LOG(APP_LOG_LEVEL_WARNING,"Control send failed: %d",(int)r);}
static int page_width(void){return s_page_layers[s_page]?layer_get_bounds(s_page_layers[s_page]).size.w:200;}
static void set_page_layer_x(int page,int x){if(page<PAGE_DASHBOARD||page>PAGE_MAP||!s_page_layers[page])return;GRect f=layer_get_frame(s_page_layers[page]);f.origin.x=x;f.origin.y=0;layer_set_frame(s_page_layers[page],f);}
static void reset_page_layers(void){for(int i=0;i<PAGE_COUNT;i++)if(s_page_layers[i]){set_page_layer_x(i,0);layer_set_hidden(s_page_layers[i],i!=s_page);}}
static void update_page_layer_positions(void){if(!s_page_layers[s_page])return;int32_t r=s_page_position_q8>=0?s_page_position_q8+SCROLL_Q8/2:s_page_position_q8-SCROLL_Q8/2;int x=(int)(r/SCROLL_Q8);set_page_layer_x(s_page,x);if(s_page_neighbor>=PAGE_DASHBOARD&&s_page_neighbor<=PAGE_MAP&&s_page_neighbor!=s_page){layer_set_hidden(s_page_layers[s_page_neighbor],false);set_page_layer_x(s_page_neighbor,x+s_page_direction*page_width());}}
static void cancel_page_scroll_timer(void){if(s_page_scroll_timer){app_timer_cancel(s_page_scroll_timer);s_page_scroll_timer=NULL;}}
static void schedule_page_scroll(void);
static void finish_page_scroll(bool committed){cancel_page_scroll_timer();if(committed&&s_page_neighbor>=PAGE_DASHBOARD&&s_page_neighbor<=PAGE_MAP&&s_page_neighbor!=s_page){s_page=s_page_neighbor;
#if defined(PBL_HEALTH)
    update_heart_rate_sampling();
#endif
} s_page_neighbor=-1;s_page_direction=0;s_page_position_q8=0;s_page_target_q8=0;s_page_velocity_q8=0;s_page_scroll_mode=PAGE_SCROLL_IDLE;reset_page_layers();update_compass_sampling();dirty();}
static void page_scroll_tick(void*c){s_page_scroll_timer=NULL;if(s_page_scroll_mode==PAGE_SCROLL_IDLE)return;int32_t force=(s_page_target_q8-s_page_position_q8)*(s_page_scroll_mode==PAGE_SCROLL_TOUCH?SCROLL_FINGER_SPRING_NUM:SCROLL_SNAP_SPRING_NUM)/(s_page_scroll_mode==PAGE_SCROLL_TOUCH?SCROLL_FINGER_SPRING_DEN:SCROLL_SNAP_SPRING_DEN);s_page_velocity_q8+=force;s_page_velocity_q8=s_page_velocity_q8*(s_page_scroll_mode==PAGE_SCROLL_TOUCH?SCROLL_FINGER_DAMPING_NUM:SCROLL_SNAP_DAMPING_NUM)/(s_page_scroll_mode==PAGE_SCROLL_TOUCH?SCROLL_FINGER_DAMPING_DEN:SCROLL_SNAP_DAMPING_DEN);s_page_velocity_q8=clamp_symmetric_i32(s_page_velocity_q8,SCROLL_MAX_VELOCITY_Q8);s_page_position_q8+=s_page_velocity_q8;update_page_layer_positions();if(s_page_scroll_mode==PAGE_SCROLL_SNAP&&abs_i32(s_page_target_q8-s_page_position_q8)<=SCROLL_STOP_POSITION_Q8&&abs_i32(s_page_velocity_q8)<=SCROLL_STOP_VELOCITY_Q8){bool committed=s_page_target_q8!=0&&s_page_neighbor>=PAGE_DASHBOARD&&s_page_neighbor<=PAGE_MAP&&s_page_neighbor!=s_page;finish_page_scroll(committed);return;}schedule_page_scroll();}
static void schedule_page_scroll(void){if(!s_page_scroll_timer&&s_page_scroll_mode!=PAGE_SCROLL_IDLE)s_page_scroll_timer=app_timer_register(SCROLL_FRAME_MS,page_scroll_tick,NULL);}
static bool prepare_page_neighbor(int dir){int n=s_page+dir;if(n<PAGE_DASHBOARD||n>PAGE_MAP){s_page_neighbor=-1;s_page_direction=dir;return false;}s_page_neighbor=n;s_page_direction=dir;if(n==PAGE_MAP)start_compass_sampling();if(s_page_layers[n]){layer_set_hidden(s_page_layers[n],false);set_page_layer_x(n,dir*page_width());layer_mark_dirty(s_page_layers[n]);}return true;}
static void start_page_touch(int dir){if(s_page_scroll_mode!=PAGE_SCROLL_IDLE)return;prepare_page_neighbor(dir);s_page_position_q8=s_page_target_q8=s_page_velocity_q8=0;s_page_scroll_mode=PAGE_SCROLL_TOUCH;schedule_page_scroll();}
static void snap_page(bool commit){if(s_page_scroll_mode==PAGE_SCROLL_IDLE)return;if(commit&&s_page_neighbor>=PAGE_DASHBOARD&&s_page_neighbor<=PAGE_MAP&&s_page_neighbor!=s_page){s_page_target_q8=-(int32_t)s_page_direction*page_width()*SCROLL_Q8;send_control(MESSAGE_KEY_WATCH_PAGE,s_page_neighbor);}else s_page_target_q8=0;s_page_scroll_mode=PAGE_SCROLL_SNAP;schedule_page_scroll();}
static void animate_to_page(int target,int dir){target=clamp_i(target,PAGE_DASHBOARD,PAGE_MAP);if(target==s_page||s_page_scroll_mode!=PAGE_SCROLL_IDLE)return;if(!dir)dir=target>s_page?1:-1;s_page_neighbor=target;s_page_direction=dir<0?-1:1;s_page_position_q8=s_page_target_q8=s_page_velocity_q8=0;if(target==PAGE_MAP)start_compass_sampling();if(s_page_layers[target]){layer_set_hidden(s_page_layers[target],false);set_page_layer_x(target,s_page_direction*page_width());layer_mark_dirty(s_page_layers[target]);}s_page_scroll_mode=PAGE_SCROLL_SNAP;s_page_target_q8=-(int32_t)s_page_direction*page_width()*SCROLL_Q8;send_control(MESSAGE_KEY_WATCH_PAGE,target);schedule_page_scroll();}
static void cancel_page_animation_to_current(void){cancel_page_scroll_timer();s_page_neighbor=-1;s_page_direction=0;s_page_position_q8=s_page_target_q8=s_page_velocity_q8=0;s_page_scroll_mode=PAGE_SCROLL_IDLE;reset_page_layers();update_compass_sampling();}
static void show_map_once(void){vibes_double_pulse();if(s_page_scroll_mode!=PAGE_SCROLL_IDLE)cancel_page_animation_to_current();if(s_page==PAGE_MAP){send_control(MESSAGE_KEY_WATCH_PAGE,PAGE_MAP);update_compass_sampling();dirty();return;}animate_to_page(PAGE_MAP,1);}

static void stop_request_timeout(void*c);static void change_stop(int delta);static void schedule_stop_animation(void);
static void cancel_stop_request_timeout(void){if(s_stop_request_timeout_timer){app_timer_cancel(s_stop_request_timeout_timer);s_stop_request_timeout_timer=NULL;}}
static void finish_stop_animation(void){if(s_stop_animation_timer){app_timer_cancel(s_stop_animation_timer);s_stop_animation_timer=NULL;}s_stop_animating=false;s_stop_bouncing=false;s_stop_bounce_returning=false;s_stop_anim_direction=0;s_stop_position_q8=s_stop_target_q8=s_stop_velocity_q8=0;if(s_page_layers[PAGE_TIMETABLE])layer_mark_dirty(s_page_layers[PAGE_TIMETABLE]);if(s_stop_queued_delta){int d=s_stop_queued_delta>0?1:-1;s_stop_queued_delta-=d;change_stop(d);}}
static void stop_animation_tick(void*c){
    s_stop_animation_timer=NULL;if(!s_stop_animating)return;
    int32_t force=(s_stop_target_q8-s_stop_position_q8)*SCROLL_SNAP_SPRING_NUM/SCROLL_SNAP_SPRING_DEN;s_stop_velocity_q8+=force;s_stop_velocity_q8=s_stop_velocity_q8*SCROLL_SNAP_DAMPING_NUM/SCROLL_SNAP_DAMPING_DEN;s_stop_velocity_q8=clamp_symmetric_i32(s_stop_velocity_q8,SCROLL_MAX_VELOCITY_Q8);s_stop_position_q8+=s_stop_velocity_q8;if(s_page_layers[PAGE_TIMETABLE])layer_mark_dirty(s_page_layers[PAGE_TIMETABLE]);
    if(abs_i32(s_stop_target_q8-s_stop_position_q8)<=SCROLL_STOP_POSITION_Q8&&abs_i32(s_stop_velocity_q8)<=SCROLL_STOP_VELOCITY_Q8){
        if(s_stop_bouncing&&!s_stop_bounce_returning){s_stop_bounce_returning=true;s_stop_target_q8=0;s_stop_velocity_q8=0;schedule_stop_animation();return;}
        finish_stop_animation();return;
    }
    schedule_stop_animation();
}
static void schedule_stop_animation(void){if(!s_stop_animation_timer&&s_stop_animating)s_stop_animation_timer=app_timer_register(SCROLL_FRAME_MS,stop_animation_tick,NULL);}
static void begin_stop_animation(int d,const StopView*old){if(!d)return;if(old)s_stop_previous=*old;s_stop_bouncing=false;s_stop_bounce_returning=false;s_stop_anim_direction=d<0?-1:1;s_stop_position_q8=0;int h=s_page_layers[PAGE_TIMETABLE]?layer_get_bounds(s_page_layers[PAGE_TIMETABLE]).size.h:228;s_stop_target_q8=-(int32_t)s_stop_anim_direction*h*SCROLL_Q8;s_stop_velocity_q8=0;s_stop_animating=true;schedule_stop_animation();}
static void begin_stop_bounce(int d){if(!d)return;s_stop_bouncing=true;s_stop_bounce_returning=false;s_stop_anim_direction=d<0?-1:1;s_stop_position_q8=0;s_stop_target_q8=-(int32_t)s_stop_anim_direction*TIMETABLE_BOUNCE_PX*SCROLL_Q8;s_stop_velocity_q8=0;s_stop_animating=true;schedule_stop_animation();}
static void stop_request_timeout(void*c){s_stop_request_timeout_timer=NULL;if(!s_stop_request_pending)return;s_stop_request_pending=false;s_stop_request_delta=0;if(s_stop_queued_delta){int d=s_stop_queued_delta>0?1:-1;s_stop_queued_delta-=d;change_stop(d);}}
static void change_stop(int d){if(s_page!=PAGE_TIMETABLE||!d)return;d=d<0?-1:1;if(s_stop_request_pending||s_stop_animating){s_stop_queued_delta=clamp_i(s_stop_queued_delta+d,-3,3);return;}s_stop_previous=s_stop;s_stop_request_pending=true;s_stop_request_delta=d;cancel_stop_request_timeout();s_stop_request_timeout_timer=app_timer_register(STOP_REQUEST_TIMEOUT_MS,stop_request_timeout,NULL);send_control(MESSAGE_KEY_WATCH_STOP_DELTA,d);}

static void select_click_handler(ClickRecognizerRef r,void*c){if(s_page_scroll_mode==PAGE_SCROLL_IDLE)animate_to_page((s_page+1)%PAGE_COUNT,1);}
static void up_click_handler(ClickRecognizerRef r,void*c){if(s_page==PAGE_TIMETABLE)change_stop(-1);else animate_to_page(s_page-1,-1);}
static void down_click_handler(ClickRecognizerRef r,void*c){if(s_page==PAGE_TIMETABLE)change_stop(1);else animate_to_page(s_page+1,1);}
static void click_config_provider(void*c){window_single_click_subscribe(BUTTON_ID_SELECT,select_click_handler);window_single_click_subscribe(BUTTON_ID_UP,up_click_handler);window_single_click_subscribe(BUTTON_ID_DOWN,down_click_handler);}

#if defined(PBL_TOUCH)
static void reset_touch_state(void){s_touch_active=false;s_touch_axis=TOUCH_AXIS_NONE;s_touch_total_x=s_touch_total_y=0;}
static void touch_begin(const TouchEvent*e){if(s_page_scroll_mode!=PAGE_SCROLL_IDLE)return;s_touch_active=true;s_touch_start_x=e->x;s_touch_start_y=e->y;s_touch_total_x=s_touch_total_y=0;s_touch_start_time_ms=current_time_ms();s_touch_axis=TOUCH_AXIS_NONE;}
static void touch_update(const TouchEvent*e){if(!s_touch_active)return;s_touch_total_x=(int16_t)(e->x-s_touch_start_x);s_touch_total_y=(int16_t)(e->y-s_touch_start_y);int ax=s_touch_total_x<0?-s_touch_total_x:s_touch_total_x,ay=s_touch_total_y<0?-s_touch_total_y:s_touch_total_y;if(s_touch_axis==TOUCH_AXIS_NONE&&ax>=SCROLL_BREAKAWAY_PX&&ax>ay){s_touch_axis=TOUCH_AXIS_HORIZONTAL;start_page_touch(s_touch_total_x<0?1:-1);}else if(s_touch_axis==TOUCH_AXIS_NONE&&s_page==PAGE_TIMETABLE&&ay>=SCROLL_BREAKAWAY_PX&&ay>ax)s_touch_axis=TOUCH_AXIS_VERTICAL;if(s_touch_axis==TOUCH_AXIS_HORIZONTAL&&s_page_scroll_mode==PAGE_SCROLL_TOUCH){int32_t target=(int32_t)s_touch_total_x*SCROLL_Q8,limit=(int32_t)page_width()*SCROLL_Q8;target=clamp_symmetric_i32(target,limit);if(s_page_neighbor<0)target/=3;s_page_target_q8=target;schedule_page_scroll();}}
static void touch_end(const TouchEvent*e){if(!s_touch_active)return;s_touch_total_x=(int16_t)(e->x-s_touch_start_x);s_touch_total_y=(int16_t)(e->y-s_touch_start_y);uint32_t elapsed=current_time_ms()-s_touch_start_time_ms;int ax=s_touch_total_x<0?-s_touch_total_x:s_touch_total_x,ay=s_touch_total_y<0?-s_touch_total_y:s_touch_total_y;bool quick=elapsed<=SCROLL_QUICK_SWIPE_MAX_MS;if(s_touch_axis==TOUCH_AXIS_HORIZONTAL||(s_touch_axis==TOUCH_AXIS_NONE&&ax>ay&&quick&&ax>=SCROLL_QUICK_SWIPE_MIN_PX)){int dir=s_touch_total_x<0?1:-1;if(s_page_scroll_mode==PAGE_SCROLL_IDLE)start_page_touch(dir);bool valid=s_page_neighbor>=PAGE_DASHBOARD&&s_page_neighbor<=PAGE_MAP&&s_page_neighbor!=s_page;bool q=quick&&ax>=SCROLL_QUICK_SWIPE_MIN_PX;bool slow=abs_i32(s_page_position_q8)>=(int32_t)page_width()*SCROLL_Q8*PAGE_SLOW_COMMIT_PERCENT/100;snap_page(valid&&(q||slow));reset_touch_state();return;}if(s_page==PAGE_TIMETABLE&&(s_touch_axis==TOUCH_AXIS_VERTICAL||(s_touch_axis==TOUCH_AXIS_NONE&&ay>ax&&quick&&ay>=SCROLL_QUICK_SWIPE_MIN_PX))){if((quick&&ay>=SCROLL_QUICK_SWIPE_MIN_PX)||ay>=TIMETABLE_SLOW_SWIPE_PX)change_stop(s_touch_total_y<0?1:-1);}reset_touch_state();}
static void touch_handler(const TouchEvent*e,void*c){if(!e)return;switch(e->type){case TouchEvent_Touchdown:touch_begin(e);break;case TouchEvent_PositionUpdate:touch_update(e);break;case TouchEvent_Liftoff:touch_end(e);break;}}
#endif

static void tick_handler(struct tm*t,TimeUnits u){update_clock(t);update_steps();dirty();}
static void copy_text(DictionaryIterator*it,uint32_t key,char*dst,size_t n){Tuple*t=dict_find(it,key);if(t&&dst&&n)snprintf(dst,n,"%s",t->value->cstring);}
static void copy_int32(DictionaryIterator*it,uint32_t key,int32_t*dst){Tuple*t=dict_find(it,key);if(t&&dst)*dst=t->value->int32;}
static void copy_map_vector(DictionaryIterator*it){Tuple*t=dict_find(it,MESSAGE_KEY_MAP_VECTOR);if(!t)return;size_t n=t->length;if(n>sizeof(s_map_payload))n=sizeof(s_map_payload);memcpy(s_map_payload,t->value->data,n);s_map_payload_len=n;s_map_frame_dirty=true;}

static bool decompress_road_mask(size_t compressed_len){size_t in=0,out=0;memset(s_road_mask,0,sizeof(s_road_mask));while(in<compressed_len&&out<sizeof(s_road_mask)){uint8_t control=s_road_compressed[in++];size_t count=(size_t)(control&0x7f)+1u;if(control&0x80){if(out+count>sizeof(s_road_mask))return false;memset(s_road_mask+out,0,count);out+=count;}else{if(in+count>compressed_len||out+count>sizeof(s_road_mask))return false;memcpy(s_road_mask+out,s_road_compressed+in,count);in+=count;out+=count;}}return in==compressed_len&&out==sizeof(s_road_mask);}
static void note_road_generation(DictionaryIterator*it){Tuple*t=dict_find(it,MESSAGE_KEY_MAP_ROADS_GENERATION);if(!t)return;int32_t g=t->value->int32;if(g<=0){s_road_generation=s_road_receiving_generation=0;s_road_expected_chunks=s_road_received_chunks=0;s_road_compressed_len=0;s_road_mask_valid=false;s_map_frame_dirty=true;return;}if(g!=s_road_generation&&g!=s_road_receiving_generation){s_road_receiving_generation=g;s_road_expected_chunks=s_road_received_chunks=0;s_road_compressed_len=0;}}
static void copy_road_chunk(DictionaryIterator*it){Tuple*data=dict_find(it,MESSAGE_KEY_MAP_ROADS_CHUNK_DATA),*gt=dict_find(it,MESSAGE_KEY_MAP_ROADS_GENERATION),*itx=dict_find(it,MESSAGE_KEY_MAP_ROADS_CHUNK_INDEX),*ct=dict_find(it,MESSAGE_KEY_MAP_ROADS_CHUNK_COUNT);if(!data||!gt||!itx||!ct)return;int32_t g=gt->value->int32;int idx=itx->value->int32,count=ct->value->int32;if(g<=0||idx<0||count<=0||count>MAP_ROAD_MAX_CHUNKS||idx>=count)return;if(idx==0||g!=s_road_receiving_generation){s_road_receiving_generation=g;s_road_expected_chunks=count;s_road_received_chunks=0;s_road_compressed_len=0;}if(g!=s_road_receiving_generation||count!=s_road_expected_chunks||idx!=s_road_received_chunks)return;size_t off=(size_t)idx*MAP_ROAD_CHUNK_BYTES,n=data->length;if(off+n>sizeof(s_road_compressed))return;memcpy(s_road_compressed+off,data->value->data,n);s_road_received_chunks++;if(s_road_received_chunks==s_road_expected_chunks){s_road_compressed_len=off+n;s_road_mask_valid=decompress_road_mask(s_road_compressed_len);if(s_road_mask_valid){s_road_generation=g;s_map_frame_dirty=true;}s_road_receiving_generation=0;s_road_expected_chunks=s_road_received_chunks=0;}}
static bool stop_fields_present(DictionaryIterator*it){return dict_find(it,MESSAGE_KEY_STOP_NAME)||dict_find(it,MESSAGE_KEY_STOP_TIME)||dict_find(it,MESSAGE_KEY_STOP_DISTANCE)||dict_find(it,MESSAGE_KEY_STOP_PERCENT)||dict_find(it,MESSAGE_KEY_STOP_IS_GOAL);}
static void copy_stop_fields(DictionaryIterator*it,StopView*v){
    if(!v)return;
    copy_text(it,MESSAGE_KEY_STOP_NAME,v->name,sizeof(v->name));copy_text(it,MESSAGE_KEY_STOP_TIME,v->time,sizeof(v->time));copy_text(it,MESSAGE_KEY_STOP_DISTANCE,v->distance,sizeof(v->distance));copy_int32(it,MESSAGE_KEY_STOP_PERCENT,&v->percent);
    Tuple*endpoint=dict_find(it,MESSAGE_KEY_STOP_IS_GOAL);
    if(endpoint){int32_t flags=endpoint->value->int32;v->is_goal=(flags&0x1)!=0;v->is_start=(flags&0x2)!=0;}
}
static void inbox_received(DictionaryIterator*it,void*c){
    copy_text(it,MESSAGE_KEY_GLUCOSE,s_glucose_text,sizeof(s_glucose_text));copy_text(it,MESSAGE_KEY_CURRENT_SPEED,s_speed_text,sizeof(s_speed_text));copy_int32(it,MESSAGE_KEY_TEMP_CURRENT_TENTHS,&s_temp_current);copy_int32(it,MESSAGE_KEY_TEMP_MIN_TENTHS,&s_temp_min);copy_int32(it,MESSAGE_KEY_TEMP_MAX_TENTHS,&s_temp_max);copy_int32(it,MESSAGE_KEY_SUNRISE_MINUTES,&s_sunrise_minutes);copy_int32(it,MESSAGE_KEY_SUNSET_MINUTES,&s_sunset_minutes);copy_int32(it,MESSAGE_KEY_ELEVATION_CURRENT,&s_elevation_current);copy_int32(it,MESSAGE_KEY_ELEVATION_MIN,&s_elevation_min);copy_int32(it,MESSAGE_KEY_ELEVATION_MAX,&s_elevation_max);copy_int32(it,MESSAGE_KEY_ROUTE_PROGRESS_PERCENT,&s_route_progress_percent);
    if(stop_fields_present(it)){
        StopView old=s_stop,incoming=s_stop;copy_stop_fields(it,&incoming);s_stop=incoming;
        if(s_stop_request_pending){
            int d=s_stop_request_delta;bool boundary=(d>0&&old.is_start&&incoming.is_start)||(d<0&&old.is_goal&&incoming.is_goal);
            s_stop_request_pending=false;s_stop_request_delta=0;cancel_stop_request_timeout();
            if(boundary)begin_stop_bounce(d);else begin_stop_animation(d,&old);
        }
    }
    copy_map_vector(it);note_road_generation(it);copy_road_chunk(it);Tuple*show=dict_find(it,MESSAGE_KEY_SHOW_MAP_ONCE);if(show&&show->value->int32)show_map_once();dirty();
}
static void inbox_dropped(AppMessageResult r,void*c){APP_LOG(APP_LOG_LEVEL_WARNING,"AppMessage dropped: %d",(int)r);}
static void outbox_failed(DictionaryIterator*it,AppMessageResult r,void*c){APP_LOG(APP_LOG_LEVEL_WARNING,"AppMessage control failed: %d",(int)r);}

static void window_load(Window*w){Layer*root=window_get_root_layer(w);GRect b=layer_get_bounds(root);s_icon_heart=gbitmap_create_with_resource(RESOURCE_ID_ICON_HEART);s_icon_blood=gbitmap_create_with_resource(RESOURCE_ID_ICON_BLOOD);s_icon_shoe=gbitmap_create_with_resource(RESOURCE_ID_ICON_SHOE);s_font_megafont_14=fonts_load_custom_font(resource_get_handle(RESOURCE_ID_FONT_MEGAFONT_14));s_font_megafont_18=fonts_load_custom_font(resource_get_handle(RESOURCE_ID_FONT_MEGAFONT_18));s_marker_outline_path=gpath_create(&s_marker_outline_info);s_marker_fill_path=gpath_create(&s_marker_fill_info);s_map_frame_bitmap=gbitmap_create_blank(GSize(MAP_FRAME_WIDTH,MAP_FRAME_HEIGHT),GBitmapFormat8Bit);s_map_frame_valid=false;s_map_frame_dirty=true;s_page_layers[0]=layer_create(b);s_page_layers[1]=layer_create(b);s_page_layers[2]=layer_create(b);if(s_page_layers[0])layer_set_update_proc(s_page_layers[0],dashboard_update_proc);if(s_page_layers[1])layer_set_update_proc(s_page_layers[1],timetable_update_proc);if(s_page_layers[2])layer_set_update_proc(s_page_layers[2],map_update_proc);for(int i=0;i<PAGE_COUNT;i++)if(s_page_layers[i])layer_add_child(root,s_page_layers[i]);reset_page_layers();window_set_background_color(w,GColorBlack);window_set_click_config_provider(w,click_config_provider);update_clock(NULL);update_steps();update_heart_rate();}
static void window_appear(Window*w){
#if defined(PBL_TOUCH)
    if(!s_touch_subscribed&&touch_service_is_enabled()){touch_service_subscribe(touch_handler,NULL);s_touch_subscribed=true;}
#endif
    update_compass_sampling();
}
static void window_disappear(Window*w){cancel_page_scroll_timer();if(s_stop_animation_timer){app_timer_cancel(s_stop_animation_timer);s_stop_animation_timer=NULL;}cancel_stop_request_timeout();s_stop_request_pending=false;s_stop_animating=false;s_stop_bouncing=false;s_stop_bounce_returning=false;stop_compass_sampling();
#if defined(PBL_TOUCH)
    if(s_touch_subscribed){touch_service_unsubscribe();s_touch_subscribed=false;reset_touch_state();}
#endif
}
static void window_unload(Window*w){for(int i=0;i<PAGE_COUNT;i++)if(s_page_layers[i]){layer_destroy(s_page_layers[i]);s_page_layers[i]=NULL;}if(s_map_frame_bitmap){gbitmap_destroy(s_map_frame_bitmap);s_map_frame_bitmap=NULL;}if(s_marker_outline_path){gpath_destroy(s_marker_outline_path);s_marker_outline_path=NULL;}if(s_marker_fill_path){gpath_destroy(s_marker_fill_path);s_marker_fill_path=NULL;}gbitmap_destroy(s_icon_heart);s_icon_heart=NULL;gbitmap_destroy(s_icon_blood);s_icon_blood=NULL;gbitmap_destroy(s_icon_shoe);s_icon_shoe=NULL;if(s_font_megafont_14){fonts_unload_custom_font(s_font_megafont_14);s_font_megafont_14=NULL;}if(s_font_megafont_18){fonts_unload_custom_font(s_font_megafont_18);s_font_megafont_18=NULL;}}

static void init(void){s_window=window_create();window_set_window_handlers(s_window,(WindowHandlers){.load=window_load,.appear=window_appear,.disappear=window_disappear,.unload=window_unload});window_stack_push(s_window,true);tick_timer_service_subscribe(MINUTE_UNIT,tick_handler);
#if defined(PBL_HEALTH)
    s_health_subscribed=health_service_events_subscribe(health_handler,NULL);update_heart_rate_sampling();
#endif
    app_message_register_inbox_received(inbox_received);app_message_register_inbox_dropped(inbox_dropped);app_message_register_outbox_failed(outbox_failed);AppMessageResult r=app_message_open(256,64);if(r!=APP_MSG_OK)APP_LOG(APP_LOG_LEVEL_ERROR,"AppMessage open failed: %d",(int)r);else send_control(MESSAGE_KEY_WATCH_PAGE,PAGE_DASHBOARD);
}
static void deinit(void){tick_timer_service_unsubscribe();cancel_page_scroll_timer();if(s_stop_animation_timer){app_timer_cancel(s_stop_animation_timer);s_stop_animation_timer=NULL;}cancel_stop_request_timeout();stop_compass_sampling();
#if defined(PBL_TOUCH)
    if(s_touch_subscribed){touch_service_unsubscribe();s_touch_subscribed=false;}
#endif
#if defined(PBL_HEALTH)
    if(s_heart_rate_timer){app_timer_cancel(s_heart_rate_timer);s_heart_rate_timer=NULL;}health_service_set_heart_rate_sample_period(0);if(s_health_subscribed)health_service_events_unsubscribe();
#endif
    window_destroy(s_window);
}

int main(void){init();app_event_loop();deinit();return 0;}

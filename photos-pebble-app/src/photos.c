/*
 * This file is licensed under the Creative Commons Zero License 1.0
 * Feel free to do what you want with it.
 */

#include <pebble.h>
#include "file_receiver.h"

#ifdef PBL_PLATFORM_APLITE
#include "png.h"
#endif

#define COMMAND_KEY 0
#define COLOR_KEY 1

#define ID_KEY 47000
#define BYTES_KEY 47001
#define TOTAL_SIZE_KEY 47002
#define OFFSET_KEY 47003

#define RANDOM_PHOTO_COMMAND 0

FileReceiver *s_filereceiver;

static Window *s_window;
static BitmapLayer *s_bitmap_layer;
static GBitmap *s_current_bitmap;
static TextLayer *s_status_text_layer;

#define MAX_STATUS_TEXT_LENGTH 255

static char s_status_text[MAX_STATUS_TEXT_LENGTH + 1];

static void set_status(const char *text) {
  if (text) {
    text_layer_set_text(s_status_text_layer, text);
    layer_set_hidden(text_layer_get_layer(s_status_text_layer), false);
  } else {
    layer_set_hidden(text_layer_get_layer(s_status_text_layer), true);
  }
}

static void load_image() {
  APP_LOG(APP_LOG_LEVEL_INFO, "sending load random image command");

  filereceiver_reset(s_filereceiver);

  DictionaryIterator *outbox;
  app_message_outbox_begin(&outbox);
  dict_write_uint8(outbox, COMMAND_KEY, RANDOM_PHOTO_COMMAND);
#ifdef PBL_COLOR
    dict_write_uint8(outbox, COLOR_KEY, 1);
#else
    dict_write_uint8(outbox, COLOR_KEY, 0);
#endif
  app_message_outbox_send();

  app_comm_set_sniff_interval(SNIFF_INTERVAL_REDUCED);
  set_status("Loading");
}

static void clear_image() {
  if (s_current_bitmap) {
    bitmap_layer_set_bitmap(s_bitmap_layer, NULL);
    gbitmap_destroy(s_current_bitmap);
    s_current_bitmap = NULL;
  }
}

static void image_loaded(GBitmap *bitmap) {
  layer_set_hidden(text_layer_get_layer(s_status_text_layer), true);

  clear_image();

  bitmap_layer_set_bitmap(s_bitmap_layer, bitmap);
  s_current_bitmap = bitmap;

  set_status(NULL);
}

static void handle_download_progress(uint32_t file_id, size_t downloaded, size_t total) {
  snprintf(s_status_text, MAX_STATUS_TEXT_LENGTH, "%d / %d", downloaded, total);
  set_status(s_status_text);
}

static void handle_download_error(int error) {
  app_comm_set_sniff_interval(SNIFF_INTERVAL_NORMAL);

  snprintf(s_status_text, MAX_STATUS_TEXT_LENGTH, "Error %d", error);
  set_status(s_status_text);
}

static void handle_image_received(uint32_t image_id, unsigned char *data, size_t size) {
  app_comm_set_sniff_interval(SNIFF_INTERVAL_NORMAL);

#ifdef PBL_PLATFORM_APLITE
  // Take over ownership of the data pointer
  filereceiver_disown_data(s_filereceiver, data);

  // Use upng decoder (this will free the data buffer)
  GBitmap *bitmap = gbitmap_create_with_png_data(data, size);
#else
  GBitmap *bitmap = gbitmap_create_from_png_data(data, size);
#endif

  if (!bitmap) return;

  image_loaded(bitmap);

  set_status(NULL);
}

static void single_click_handler(ClickRecognizerRef recognizer, void *context) {
  clear_image();
  load_image();
}

static void click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, single_click_handler);
  window_single_click_subscribe(BUTTON_ID_UP, single_click_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, single_click_handler);
}

static void window_load() {
  Layer *window_layer = window_get_root_layer(s_window);
  GRect bounds = layer_get_bounds(window_layer);

  s_bitmap_layer = bitmap_layer_create(bounds);
  layer_add_child(window_layer, bitmap_layer_get_layer(s_bitmap_layer));

  s_status_text_layer = text_layer_create(GRect(0, 70, 144, 30));
  text_layer_set_font(s_status_text_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_status_text_layer, GTextAlignmentCenter);
  text_layer_set_text(s_status_text_layer, "Waiting");
  layer_add_child(window_layer, text_layer_get_layer(s_status_text_layer));

  load_image();
}

static void window_unload() {
  // FIXME cleanup
}

static void handle_incoming_message(DictionaryIterator *iter, void *context) {
  filereceiver_handle_message(s_filereceiver, iter);
}

static void init() {
  s_window = window_create();

  window_set_click_config_provider(s_window, click_config_provider);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });

  // Init app message infrastructure
  app_message_register_inbox_received(handle_incoming_message);
  app_message_open(app_message_inbox_size_maximum(), app_message_outbox_size_maximum());

  s_filereceiver = filereceiver_create((FileReceiverMessageKeys) {
    .id_key = ID_KEY,
    .size_key = TOTAL_SIZE_KEY,
    .offset_key = OFFSET_KEY,
    .bytes_key = BYTES_KEY
  });

  filereceiver_set_callbacks(s_filereceiver, (FileReceiverCallbacks) {
    .error = handle_download_error,
    .file_progress = handle_download_progress,
    .file_received = handle_image_received
  });

  window_stack_push(s_window, false);
}

static void deinit() {
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}

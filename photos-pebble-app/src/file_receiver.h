/*
 * This file is licensed under the Creative Commons Zero License 1.0
 * Feel free to do what you want with it.
 */

#pragma once

#include <pebble.h>

#define FILERECEIVER_ERROR_INVALID_STATE 1
#define FILERECEIVER_ERROR_INVALID_ID 2
#define FILERECEIVER_ERROR_INVALID_SIZE 3
#define FILERECEIVER_ERROR_INVALID_OFFSET 4

struct FileReceiver;
typedef struct FileReceiver FileReceiver;

typedef void (*FileReceiverErrorCallback)(int error_code);
typedef void (*FileReceivedCallback)(uint32_t id, unsigned char *data, size_t size);
typedef void (*FileProgressCallback)(uint32_t id, size_t current_size, size_t total_size);

typedef struct FileReceiverMessageKeys {
  uint32_t id_key;
  uint32_t size_key;
  uint32_t offset_key;
  uint32_t bytes_key;
} FileReceiverMessageKeys;

typedef struct FileReceiverCallbacks {
  FileReceiverErrorCallback error;
  FileReceivedCallback file_received;
  FileProgressCallback file_progress;
} FileReceiverCallbacks;

typedef struct FileReceiver {
  uint32_t file_id;
  unsigned char *buffer;
  uint16_t pos;
  size_t size;
  uint8_t state;

  FileReceiverMessageKeys keys;
  FileReceiverCallbacks callbacks;
} FileReceiver;

FileReceiver *filereceiver_create(FileReceiverMessageKeys keys);
void filereceiver_destroy(FileReceiver *receiver);
void filereceiver_set_callbacks(FileReceiver *receiver, FileReceiverCallbacks callbacks);

// Reset the FileReceiver state so that it's ready to receive a new file
void filereceiver_reset(FileReceiver *receiver);

// Let the FileReceiver process an incoming message. The caller should make sure that
// the message is part of a valid file download.
void filereceiver_handle_message(FileReceiver *receiver, DictionaryIterator *iter);

// Given a buffer pointer that was passed to FileReceivedCallback,
// asks the FileReceiver to give up ownership of the malloc'd memory.
// The caller is then responsible for freeing the memory later.
void filereceiver_disown_data(FileReceiver *receiver, unsigned char *data);

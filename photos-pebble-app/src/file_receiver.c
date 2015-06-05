/*
 * This file is licensed under the Creative Commons Zero License 1.0
 * Feel free to do what you want with it.
 */

#include "file_receiver.h"

#define EXPECTING_SIZE 0
#define ERROR 1
#define DOWNLOADING 2

FileReceiver *filereceiver_create(FileReceiverMessageKeys keys) {
  FileReceiver *receiver = malloc(sizeof(FileReceiver));
  memset(receiver, 0, sizeof(FileReceiver));
  receiver->keys = keys;
  return receiver;
}

void filereceiver_reset(FileReceiver *receiver) {
  receiver->state = EXPECTING_SIZE;
  receiver->size = 0;
  receiver->pos = 0;

  if (receiver->buffer) {
    free(receiver->buffer);
    receiver->buffer = NULL;
  }
}

void filereceiver_destroy(FileReceiver *receiver) {
  if (!receiver) return;

  filereceiver_reset(receiver);
  free(receiver);
}

void filereceiver_disown_data(FileReceiver *receiver, unsigned char *data) {
  if (data == receiver->buffer) {
    receiver->state = ERROR;
    receiver->buffer = NULL;
  }
}

void filereceiver_set_callbacks(FileReceiver *receiver, FileReceiverCallbacks callbacks) {
  receiver->callbacks = callbacks;
}

static void filereceiver_report_error(FileReceiver *receiver, int error_code) {
  filereceiver_reset(receiver);
  receiver->state = ERROR;

  if (receiver->callbacks.error) {
    receiver->callbacks.error(error_code);
  }
}

void filereceiver_handle_message(FileReceiver *receiver, DictionaryIterator *iter) {
  FileReceiverMessageKeys *keys = &receiver->keys;

  if (!receiver || receiver->state == ERROR) {
    return;
  }

  int error_code = 0;

  uint32_t id;
  Tuple *id_tuple = dict_find(iter, keys->id_key);
  if (id_tuple) {
    id = id_tuple->value->uint32;
  } else {
    error_code = FILERECEIVER_ERROR_INVALID_ID;
    goto error;
  }

  switch (receiver->state) {
    case EXPECTING_SIZE:
    {
      Tuple *total_size_tuple = dict_find(iter, keys->size_key);
      if (total_size_tuple && total_size_tuple->type == TUPLE_UINT) {
        uint16_t size = total_size_tuple->value->uint16;
        receiver->buffer = malloc(size);
        if (!receiver->buffer) goto error;

        receiver->file_id = id;
        receiver->size = size;
        receiver->pos = 0;
        receiver->state = DOWNLOADING;
      } else {
        error_code = FILERECEIVER_ERROR_INVALID_STATE;
        goto error;
      }
      break;
    }
    case DOWNLOADING:
    {
      if (id != receiver->file_id) {
        // possibly an old transfer that hasn't been cancelled yet; ignore it
        return;
      }

      // offset can be optionally included to double-check that
      // we're writing to the right position
      Tuple *offset_tuple = dict_find(iter, keys->offset_key);
      if (offset_tuple && offset_tuple->type == TUPLE_UINT) {
        if (offset_tuple->value->uint16 != receiver->pos) {
          error_code = FILERECEIVER_ERROR_INVALID_OFFSET;
          goto error;
        }
      } 
      break;
    }
  }

  Tuple *bytes_tuple = dict_find(iter, keys->bytes_key);
  if (bytes_tuple && bytes_tuple->type == TUPLE_BYTE_ARRAY) {
    uint16_t length = bytes_tuple->length;
    if (receiver->pos + length > receiver->size) {
      error_code = FILERECEIVER_ERROR_INVALID_OFFSET;
      goto error;
    }

    memcpy(receiver->buffer + receiver->pos, bytes_tuple->value->data, length);
    receiver->pos += length;

    if (receiver->pos == receiver->size) { // done
      if (receiver->callbacks.file_received) {
        receiver->callbacks.file_received(receiver->file_id, receiver->buffer, receiver->size);
      }

      filereceiver_reset(receiver);
    } else {
       if (receiver->callbacks.file_progress) {
         receiver->callbacks.file_progress(receiver->file_id, receiver->pos, receiver->size);
       }
    }
  }

  return;

error:
  filereceiver_report_error(receiver, error_code);
}

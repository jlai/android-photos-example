package org.example.CompanionPhotosExample;

import android.app.Service;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.util.Log;

import com.getpebble.android.kit.util.PebbleDictionary;

import org.example.CompanionPhotosExample.helpers.CompanionService;
import org.example.CompanionPhotosExample.helpers.OutboxManager;
import org.example.CompanionPhotosExample.helpers.SimpleImageEncoder;

import java.util.Arrays;
import java.util.UUID;

public class ExamplePhotoService extends CompanionService {
    static final String TAG = ExamplePhotoService.class.getSimpleName();
    static final UUID PEBBLE_APP_UUID = UUID.fromString("07a1352b-f019-4385-b933-e1b22438f663");

    final int COMMAND_KEY = 0;
    final int COLOR_KEY = 1;

    final int ID_KEY = 47000;
    final int BYTES_KEY = 47001;
    final int TOTAL_SIZE_KEY = 47002;
    final int OFFSET_KEY = 47003;

    final int RANDOM_PHOTO_COMMAND = 0;

    public ExamplePhotoService() {
        // Must set this in constructor
        setAppUUID(PEBBLE_APP_UUID);
    }

    public static class Receiver extends ForwardReceiver {
        @Override
        public Class<? extends Service> getServiceClass() {
            return ExamplePhotoService.class;
        }
    }

    @Override
    protected void handleData(int transactionId, PebbleDictionary data) {
        Long commandValue = data.getUnsignedIntegerAsLong(COMMAND_KEY);
        int command = commandValue != null ? commandValue.intValue() : -1;
        Log.w(TAG, "received command command id " + command);

        try {
            switch (command) {
                case RANDOM_PHOTO_COMMAND:
                    Long color = data.getUnsignedIntegerAsLong(COLOR_KEY);

                    // Abort the current queued message (if any)
                    clearOutbox();

                    // Send a random photo
                    Log.w(TAG, "sending with color=" + color);
                    sendRandomPhoto(color == 1);
                    break;
                default:
                    Log.w(TAG, "unrecognized command id " + command);
            }
            this.sendAck(transactionId);
        } catch (Throwable e) {
            this.sendNack(transactionId);
            throw e;
        }
    }

    void sendRandomPhoto(boolean color) {
        String where = "";
        String orderBy = "RANDOM() LIMIT 1";

        Cursor cur = MediaStore.Images.Media.query(getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, where, orderBy);

        if (cur != null && cur.moveToFirst()) {
            try {
                int id = cur.getInt(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
                Bitmap photo = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), id,
                        MediaStore.Images.Thumbnails.MINI_KIND, null);

                if (photo == null) {
                    return;
                }

                Log.d(TAG, "original thumbnail size: " + photo.getWidth() + "x" + photo.getHeight());

                float ratio = Math.min(144f / photo.getWidth(), 168f / photo.getHeight());

                int width = (int) (photo.getWidth() * ratio);
                int height = (int) (photo.getHeight() * ratio);

                photo = Bitmap.createScaledBitmap(photo, width, height, false);
                Log.d(TAG, "resized thumbnail size: " + photo.getWidth() + "x" + photo.getHeight());

                byte [] png = SimpleImageEncoder.encodeBitmapAsPNG(photo, color);

                this.sendBytes(id, png, 0);
            } finally {
                cur.close();
            }
        }
    }

    /**
     * Send an array of bytes to the watch. The data will be split into
     * multiple messages and sent in sequence after each prior message
     * is acknowledged.
     *
     * @param id An ID used to identify the data being sent
     * @param bytes Array of bytes
     * @param offset Offset into array
     */
    void sendBytes(final int id, final byte [] bytes, final int offset) {
        // Max message size is currently ~124 bytes for companion apps
        // Use 100 bytes to leave room for a few small fields
        final int MAX_BYTES = 100;

        PebbleDictionary data = new PebbleDictionary();

        final int end = Math.min(offset + MAX_BYTES, bytes.length);

        data.addUint32(ID_KEY, id);
        data.addBytes(BYTES_KEY, Arrays.copyOfRange(bytes, offset, end));
        data.addUint16(TOTAL_SIZE_KEY, (short) bytes.length);
        data.addUint16(OFFSET_KEY, (short) offset);

        Log.d(TAG, "Sending bytes " + offset + "-" + end + " of " + bytes.length);
        this.sendMessage(data, new OutboxManager.OutgoingMessageCallbacks() {
            @Override
            public void onSendSuccess() {
                // Send the next chunk of bytes
                if (end < bytes.length) {
                    Log.d(TAG, "Sending next chunk starting from " + end);
                    sendBytes(id, bytes, end);
                }
            }

            @Override
            public void onSendFailure() {
                Log.w(TAG, "Failed to send photo");
            }
        });
    }
}

package org.example.CompanionPhotosExample.helpers;

import android.content.Context;
import android.util.Log;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Manages reliably sending messages to the Pebble watch.
 */
public class OutboxManager {
    static final String TAG = OutboxManager.class.getSimpleName();
    protected UUID uuid;

    protected Context context;
    protected ArrayList<OutgoingMessage> outbox = new ArrayList<OutgoingMessage>();

    protected int nextId = 1;

    public OutboxManager(Context context, UUID uuid) {
        this.context = context;
        this.uuid = uuid;
    }

    protected int getNextTransactionId() {
        return (nextId++ % 255);
    }

    protected static class OutgoingMessage {
        int transactionId;
        boolean sent = false;
        int retryCount = 0;
        int maxRetries = 3;

        PebbleDictionary data;
        OutgoingMessageCallbacks callbacks;

        public OutgoingMessage(PebbleDictionary data) {
            this.data = data;
        }

        public OutgoingMessage(PebbleDictionary data,
                               OutgoingMessageCallbacks callbacks) {
            this.data = data;
            this.callbacks = callbacks;
        }

        public PebbleDictionary getData() {
            return data;
        }

        public void setTransactionId(int id) { transactionId = id; }
        public int getTransactionId() { return transactionId; }

        public boolean shouldAttemptResend() {
            retryCount++;
            return retryCount <= maxRetries;
        }
    }

    public interface OutgoingMessageCallbacks {
        public void onSendSuccess();
        public void onSendFailure();
    }

    /**
     * Send data to the watch, with built-in retry handling.
     * Should only be called from the service's handler thread.
     *
     * @param data
     */
    public void sendMessage(PebbleDictionary data) {
        sendMessage(new OutgoingMessage(data));
    }

    /**
     * Send data to the watch, with built-in retry handling.
     * Should only be called from the service's handler thread.
     *
     * Also allows providing callbacks for when the message
     * has been sent or failed.
     *
     * @param data
     */
    public void sendMessage(PebbleDictionary data,
                            OutgoingMessageCallbacks callbacks) {
        sendMessage(new OutgoingMessage(data, callbacks));
    }

    protected void sendMessage(OutgoingMessage message) {
        int id = getNextTransactionId();
        message.setTransactionId(id);

        outbox.add(message);

        if (outbox.size() <= 1) {
            Log.d(TAG, "sending data with transactionId " + id);
            message.sent = true;
            // Send immediately if this is the only queued message
            PebbleKit.sendDataToPebbleWithTransactionId(
                    context, uuid,
                    message.getData(), id);
        } // otherwise, wait for next ACK. TODO: schedule timeout
    }

    protected void clearOutbox() {
        outbox.clear();
    }

    // Send the first unsent message
    protected void flushOutbox() {
        for (OutgoingMessage message : outbox) {
            if (!message.sent) {
                Log.d(TAG, "sending queued data with transactionId " + message.getTransactionId());
                PebbleKit.sendDataToPebbleWithTransactionId(
                        context, uuid,
                        message.getData(), message.getTransactionId());
                message.sent = true;
                break;
            }
        }
    }

    protected OutgoingMessage findOutgoingMessage(int transactionId) {
        // Non-optimal, but the queue should be small in most cases,
        // and messages should be near the beginning of the queue
        // (unless lost/missing)
        for (OutgoingMessage message : outbox) {
            if (message.getTransactionId() == transactionId) {
                return message;
            }
        }
        return null;
    }

    public void handleAck(int transactionId) {
        OutgoingMessage message = findOutgoingMessage(transactionId);
        if (message != null) {
            outbox.remove(message);
            if (message.callbacks != null)
                message.callbacks.onSendSuccess();
        }
        flushOutbox();
    }

    public void handleNack(int transactionId) {
        OutgoingMessage message = findOutgoingMessage(transactionId);
        if (message != null) {
            if (message.shouldAttemptResend()) {
                Log.d(TAG, "resending data with transactionId " + message.getTransactionId());
                PebbleKit.sendDataToPebbleWithTransactionId(
                        context, uuid,
                        message.getData(), message.getTransactionId());
            } else {
                outbox.remove(message);
                if (message.callbacks != null)
                    message.callbacks.onSendFailure();
                flushOutbox();
            }
        }
    }
}

package org.example.CompanionPhotosExample.helpers;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.getpebble.android.kit.Constants;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Base class for writing companion app services for the Pebble watch.
 *
 * NOTE: all methods must be called from the handler thread. If you
 * need to call a method like sendMessage from another thread, you should
 * use asyncSendMessage to run sendMessage on the handler thread.
 *
 * Basic usage:
 *
 * - In the constructor, call setAppUUID with your Pebble application's UUID.
 *
 * - Override handleData() to handle incoming data from the watch.
 *   Make sure to call sendAck(transactionId) to acknowledge the data.
 *
 * - Define an subclass of ForwardReceiver that implements getServiceClass()
 *   to return your service class.
 *
 * - Create a manifest entry to start your service on boot or if a Pebble
 *   message comes in while the service is not running.
 *
 * {@code
 * <receiver android:name=".YourService$Receiver" android:exported="true">
 *   <intent-filter>
 *   <action android:name="android.intent.action.BOOT_COMPLETED" />
 *   <action android:name="com.getpebble.action.app.RECEIVE" />
 *   </intent-filter>
 * </receiver>
 * }
 *
 * Create a manifest entry for your service:
 * {@code
 * <service android:name="org.example.yourpackage.YourService" />
 * }
 */
public abstract class CompanionService extends Service {
    static final String TAG = CompanionService.class.getSimpleName();

    protected UUID pebbleAppUUID;

    HandlerThread handlerThread;
    Handler handler;
    BroadcastReceiver dataReceiver;
    BroadcastReceiver ackReceiver;
    BroadcastReceiver nackReceiver;

    OutboxManager outboxManager;

    /**
     * Hack using global (static) variables to determine if
     * a service class has any running instances. For lack of a good
     * way to do this otherwise.
     */
    static class ServiceStateTracker {
        static WeakHashMap<Class<? extends Service>, Service> instances =
                new WeakHashMap<Class<? extends Service>, Service>();

        static synchronized void addService(Service service) {
            instances.put(service.getClass(), service);
        }

        static synchronized void removeService(Service service) {
            instances.remove(service.getClass());
        }

        static synchronized boolean isServiceRunning(Class<? extends Service> cls) {
            return instances.containsKey(cls);
        }
    }

    /**
     * Receiver that forwards broadcast intents to a service if the service is not
     * running. Must override getServiceClass() to return the service class.
     */
    public abstract static class ForwardReceiver extends BroadcastReceiver {
        public abstract Class<? extends Service> getServiceClass();

        @Override
        public void onReceive(Context context, Intent intent) {
            intent.setClass(context, getServiceClass());

            boolean isRunning = ServiceStateTracker.isServiceRunning(getServiceClass());

            if (!isRunning) {
                context.startService(intent);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null) {
            String action = intent.getAction();

            // Since the service is just booting up, check if we've been forwarded
            // a broadcast intent
            if (dataReceiver != null && Constants.INTENT_APP_RECEIVE.equals(action)) {
                dataReceiver.onReceive(this, intent);
            } else if (ackReceiver != null && Constants.INTENT_APP_RECEIVE_ACK.equals(action)) {
                ackReceiver.onReceive(this, intent);
            } else if (nackReceiver != null && Constants.INTENT_APP_RECEIVE_NACK.equals(action)) {
                nackReceiver.onReceive(this, intent);
            }
        }

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    /**
     * Get handler, which can be used to post messages to the event loop asynchronously.
     * Note this will return null if the service hasn't been initialized with onCreate().
     * @return handler
     */
    public Handler getHandler() {
        return handler;
    }

    /**
     * Set Pebble watch app UUID
     */
    public void setAppUUID(UUID uuid) {
        if (pebbleAppUUID != null) {
            throw new IllegalStateException("uuid already configured");
        }
        pebbleAppUUID = uuid;
    }

    protected void setupPebbleKitReceivers () {
        dataReceiver = PebbleKit.registerReceivedDataHandler(this, new PebbleKit.PebbleDataReceiver(pebbleAppUUID) {
            @Override
            public void receiveData(final Context context, final int transactionId, final PebbleDictionary data) {
                //Log.d(TAG, "got data from watch with transactionId " + transactionId);

                // Run in handler thread
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        handleData(transactionId, data);
                    }
                });
            }
        });

        ackReceiver = PebbleKit.registerReceivedAckHandler(this, new PebbleKit.PebbleAckReceiver(pebbleAppUUID) {
            @Override
            public void receiveAck(Context context, final int transactionId) {
                Log.d(TAG, "got ACK for transactionId " + transactionId);

                // Run in handler thread
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        outboxManager.handleAck(transactionId);
                    }
                });
            }
        });

        nackReceiver = PebbleKit.registerReceivedNackHandler(this, new PebbleKit.PebbleNackReceiver(pebbleAppUUID) {
            @Override
            public void receiveNack(Context context, final int transactionId) {
                Log.d(TAG, "got NACK for transactionId " + transactionId);

                // Run in handler thread
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        outboxManager.handleNack(transactionId);
                    }
                });
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (pebbleAppUUID == null) {
            throw new IllegalStateException("pebbleAppUUID not configured");
        }

        handlerThread = new HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        outboxManager = new OutboxManager(getApplicationContext(), pebbleAppUUID);

        setupPebbleKitReceivers();

        ServiceStateTracker.addService(this);
    }

    @Override
    public void onDestroy() {
        ServiceStateTracker.removeService(this);

        if (dataReceiver != null) unregisterReceiver(dataReceiver);
        if (ackReceiver != null) unregisterReceiver(ackReceiver);
        if (nackReceiver != null) unregisterReceiver(nackReceiver);

        dataReceiver = ackReceiver = nackReceiver = null;

        if (handlerThread != null) handlerThread.quit();
        handlerThread = null;
    }

    /**
     * Send a message to the watch. Safe to call from any thread.
     *
     * @param data
     */
    public void asyncSendMessage(final PebbleDictionary data) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                sendMessage(data);
            }
        });
    }

    /**
     * Send data to the watch, with built-in retry handling.
     * Should only be called from the service's handler thread.
     *
     * @param data
     */
    protected void sendMessage(PebbleDictionary data) {
        outboxManager.sendMessage(data);
    }

    /**
     * Send data to the watch, with built-in retry handling.
     * Should only be called from the service's handler thread.
     *
     * @param data
     */
    protected void sendMessage(PebbleDictionary data, OutboxManager.OutgoingMessageCallbacks callbacks) {
        outboxManager.sendMessage(data, callbacks);
    }

    /**
     * Clear outgoing message queue.
     * Should only be called from the service's handler thread.
     */
    protected void clearOutbox() {

    }

    protected void sendAck(int transactionId) {
        PebbleKit.sendAckToPebble(getApplicationContext(), transactionId);
    }

    protected void sendNack(int transactionId) {
        PebbleKit.sendAckToPebble(getApplicationContext(), transactionId);
    }

    /**
     * Developers can override this to handle data messages. Make sure to
     * call sendAck(transactionId).
     *
     * @param transactionId
     * @param data
     */
    protected abstract void handleData(int transactionId, PebbleDictionary data);
}

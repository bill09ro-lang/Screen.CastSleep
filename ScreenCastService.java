package com.screencastsleep.app.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.screencastsleep.app.MainActivity;
import com.screencastsleep.app.R;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Screen Cast Service
 * Captures the screen and streams it to the TV
 * Maintains wake lock to continue casting when screen is off
 */
public class ScreenCastService extends Service {

    private static final String TAG = "ScreenCastService";
    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "ScreenCastChannel";

    // Screen capture settings
    private static final int SCREEN_DENSITY = DisplayMetrics.DENSITY_HIGH;
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int BIT_RATE = 4000000; // 4 Mbps

    // Virtual display
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MediaProjection mediaProjection;
    private int screenWidth;
    private int screenHeight;

    // WiFi Direct
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private WifiP2pInfo wifiP2pInfo;

    // Socket for streaming
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private DataOutputStream dataOutputStream;
    private boolean isStreaming = false;

    // Wake lock
    private PowerManager.WakeLock wakeLock;

    // Handler for threading
    private Handler handler = new Handler(Looper.getMainLooper());

    // Streaming thread
    private Thread streamingThread;

    // Screen capture callback
    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
            reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null && isStreaming) {
                        sendFrame(image);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error capturing frame", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        // Acquire wake lock to keep service running
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ScreenCastSleep::CastingWakeLock"
        );
        wakeLock.acquire();

        // Initialize WiFi Direct
        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(this, Looper.getMainLooper(), null);
        }

        // Get screen dimensions
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealSize(size);
            screenWidth = size.x;
            screenHeight = size.y;
        } else {
            screenWidth = 1080;
            screenHeight = 1920;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Get screen capture intent
        Intent screenCaptureIntent = intent.getParcelableExtra("screenCaptureIntent");
        if (screenCaptureIntent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Create notification
        startForeground(NOTIFICATION_ID, createNotification());

        // Initialize media projection
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager != null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(
                    RESULT_OK, screenCaptureIntent);

            if (mediaProjection != null) {
                initializeVirtualDisplay();
                startServerSocket();
            }
        }

        return START_STICKY;
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Casting en cours")
                .setContentText("Votre écran est casté vers la TV")
                .setSmallIcon(R.drawable.ic_cast_on)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void initializeVirtualDisplay() {
        // Create ImageReader for screen capture
        imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                ImageFormat.JPEG, 2
        );

        imageReader.setOnImageAvailableListener(onImageAvailableListener, handler);

        // Create virtual display
        if (mediaProjection != null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCast",
                    screenWidth, screenHeight,
                    SCREEN_DENSITY,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );
        }
    }

    private void startServerSocket() {
        streamingThread = new Thread(() -> {
            try {
                // Create server socket on a specific port
                serverSocket = new ServerSocket(5000);
                serverSocket.setReuseAddress(true);

                Log.d(TAG, "Server started, waiting for client...");

                // Wait for client connection
                clientSocket = serverSocket.accept();
                Log.d(TAG, "Client connected: " + clientSocket.getInetAddress());

                // Initialize output stream
                dataOutputStream = new DataOutputStream(clientSocket.getOutputStream());
                isStreaming = true;

                // Send screen dimensions to client
                dataOutputStream.writeInt(screenWidth);
                dataOutputStream.writeInt(screenHeight);
                dataOutputStream.flush();

            } catch (IOException e) {
                Log.e(TAG, "Server socket error", e);
            }
        });
        streamingThread.start();
    }

    private void sendFrame(Image image) {
        if (dataOutputStream == null || !isStreaming) {
            return;
        }

        try {
            // Convert image to JPEG bytes
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // Send frame size first
            dataOutputStream.writeInt(bytes.length);

            // Send frame data
            dataOutputStream.write(bytes);
            dataOutputStream.flush();

        } catch (IOException e) {
            Log.e(TAG, "Error sending frame", e);
            isStreaming = false;
        }
    }

    public void setWifiP2pInfo(WifiP2pInfo info) {
        this.wifiP2pInfo = info;
        if (info != null && info.groupFormed) {
            // Connection established via WiFi Direct
            Log.d(TAG, "WiFi Direct connected, group owner: " + info.isGroupOwner);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        isStreaming = false;

        // Release resources
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (imageReader != null) {
            imageReader.close();
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
        }

        // Close sockets
        try {
            if (dataOutputStream != null) {
                dataOutputStream.close();
            }
            if (clientSocket != null) {
                clientSocket.close();
            }
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing sockets", e);
        }

        // Release wake lock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

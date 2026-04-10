package com.screencastsleep.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.slider.Slider;
import com.screencastsleep.app.service.ScreenCastService;
import com.screencastsleep.app.service.WifiDirectService;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Main Activity - Screen Cast with Sleep Timer
 * Cast your screen to Smart TV and set a timer to turn off screen
 * while casting continues
 */
public class MainActivity extends AppCompatActivity {

    // Request codes
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_CODE_MANAGE_OVERLAY = 1002;

    // Notification channel
    private static final String CHANNEL_ID = "ScreenCastChannel";
    private static final int NOTIFICATION_ID = 1;

    // Permissions
    private static final int PERMISSION_REQUEST_CODE = 100;

    // Timer values (in minutes)
    private int selectedMinutes = 5;

    // UI Elements
    private TextView tvTimerValue;
    private TextView tvStatus;
    private TextView tvDevicesFound;
    private Button btnStartCast;
    private Button btnStopCast;
    private Button btnDiscoverDevices;
    private Slider sliderMinutes;
    private ProgressBar progressBar;
    private ImageView ivCastIcon;
    private View deviceListContainer;

    // Services
    private ScreenCastService screenCastService;
    private WifiDirectService wifiDirectService;
    private boolean isServiceBound = false;
    private boolean isScreenCapturing = false;
    private boolean isCasting = false;

    // Timer
    private CountDownTimer countDownTimer;
    private long remainingTimeMillis = 0;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    // WiFi Direct
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private List<WifiP2pDevice> deviceList = new ArrayList<>();
    private WifiP2pDevice selectedDevice;
    private ArrayAdapter<String> deviceArrayAdapter;

    // Screen capture
    private MediaProjectionManager mediaProjectionManager;
    private Intent screenCaptureIntent;

    // Activity result launchers
    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen on while app is in foreground
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize UI elements
        initializeViews();

        // Initialize notification channel
        createNotificationChannel();

        // Initialize WiFi Direct
        initializeWifiDirect();

        // Initialize MediaProjection manager
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Register activity result launchers
        registerActivityResultLaunchers();

        // Update UI state
        updateUIState();
    }

    private void initializeViews() {
        tvTimerValue = findViewById(R.id.tvTimerValue);
        tvStatus = findViewById(R.id.tvStatus);
        tvDevicesFound = findViewById(R.id.tvDevicesFound);
        btnStartCast = findViewById(R.id.btnStartCast);
        btnStopCast = findViewById(R.id.btnStopCast);
        btnDiscoverDevices = findViewById(R.id.btnDiscoverDevices);
        sliderMinutes = findViewById(R.id.sliderMinutes);
        progressBar = findViewById(R.id.progressBar);
        ivCastIcon = findViewById(R.id.ivCastIcon);
        deviceListContainer = findViewById(R.id.deviceListContainer);

        // Set initial timer display
        tvTimerValue.setText(selectedMinutes + " min");

        // Slider listener
        sliderMinutes.addOnChangeListener((slider, value, fromUser) -> {
            selectedMinutes = (int) value;
            tvTimerValue.setText(selectedMinutes + " min");
        });

        // Button click listeners
        btnStartCast.setOnClickListener(v -> startScreenCapture());
        btnStopCast.setOnClickListener(v -> stopCasting());
        btnDiscoverDevices.setOnClickListener(v -> discoverDevices());

        // Timer update runnable
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (remainingTimeMillis > 0) {
                    updateTimerDisplay();
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    private void registerActivityResultLaunchers() {
        // Screen capture permission launcher
        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        screenCaptureIntent = result.getData();
                        requestOverlayPermission();
                    } else {
                        Toast.makeText(this, "Permission de capture d'écran refusée",
                                Toast.LENGTH_SHORT).show();
                        updateUIState();
                    }
                }
        );

        // Overlay permission launcher
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.canDrawOverlays(this)) {
                            startCastingService();
                        } else {
                            Toast.makeText(this, "Permission overlay requise pour le casting",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        startCastingService();
                    }
                }
        );

        // General permissions launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                results -> {
                    boolean allGranted = true;
                    for (Boolean granted : results.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) {
                        startScreenCapture();
                    } else {
                        Toast.makeText(this, "Permissions requises non accordées",
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void initializeWifiDirect() {
        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(this, Looper.getMainLooper(), null);
            registerReceiver(new com.screencastsleep.app.receiver.WifiDirectReceiver(wifiP2pManager, channel, this),
                    com.screencastsleep.app.receiver.WifiDirectReceiver.getIntentFilter());
        }
    }

    private void discoverDevices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Recherche d'appareils...");

        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Toast.makeText(MainActivity.this, "Recherche en cours...",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("Erreur de recherche: " + reason);
                Toast.makeText(MainActivity.this,
                        "Erreur lors de la recherche d'appareils",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void updateDeviceList(List<WifiP2pDevice> devices) {
        this.deviceList = devices;
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            tvDevicesFound.setText(devices.size() + " appareil(s) trouvé(s)");

            if (devices.isEmpty()) {
                Toast.makeText(this, "Aucun appareil trouvé",
                        Toast.LENGTH_SHORT).show();
            } else {
                showDeviceSelectionDialog(devices);
            }
        });
    }

    private void showDeviceSelectionDialog(List<WifiP2pDevice> devices) {
        String[] deviceNames = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            deviceNames[i] = devices.get(i).deviceName + " (" + devices.get(i).deviceAddress + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Sélectionner une TV")
                .setItems(deviceNames, (dialog, which) -> {
                    selectedDevice = devices.get(which);
                    connectToDevice(selectedDevice);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void connectToDevice(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WifiP2pConfig.WPS_PBC;

        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Connexion à " + device.deviceName + "...");

        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Toast.makeText(MainActivity.this,
                        "Connexion en cours...",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this,
                        "Erreur de connexion: " + reason,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void onWifiP2pInfoAvailable(WifiP2pInfo info) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (info.groupFormed) {
                if (info.isGroupOwner) {
                    tvStatus.setText("Connecté en tant que groupe hôte");
                    Toast.makeText(this, "Groupe hôte créé", Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("Connecté au groupe: " + info.groupOwnerAddress);
                    Toast.makeText(this,
                            "Connecté à: " + info.groupOwnerAddress,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.INTERNET);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_WIFI_STATE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toArray(new String[0]));
        } else {
            startScreenCapture();
        }
    }

    private void startScreenCapture() {
        if (mediaProjectionManager != null) {
            Intent intent = mediaProjectionManager.createScreenCaptureIntent();
            screenCaptureLauncher.launch(intent);
        } else {
            Toast.makeText(this, "Capture d'écran non disponible",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                overlayPermissionLauncher.launch(intent);
            } else {
                startCastingService();
            }
        } else {
            startCastingService();
        }
    }

    private void startCastingService() {
        isScreenCapturing = true;
        isCasting = true;

        // Create and start the foreground service
        Intent serviceIntent = new Intent(this, ScreenCastService.class);
        serviceIntent.putExtra("screenCaptureIntent", screenCaptureIntent);
        serviceIntent.putExtra("selectedMinutes", selectedMinutes);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Start the timer
        startSleepTimer();

        // Update UI
        updateUIState();
    }

    private void startSleepTimer() {
        // Convert minutes to milliseconds
        long durationMillis = selectedMinutes * 60 * 1000L;
        remainingTimeMillis = durationMillis;

        // Start countdown timer
        countDownTimer = new CountDownTimer(durationMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                remainingTimeMillis = millisUntilFinished;
                timerHandler.post(timerRunnable);
            }

            @Override
            public void onFinish() {
                remainingTimeMillis = 0;
                turnOffScreen();
            }
        }.start();

        tvStatus.setText("Minuterie démarrée: " + selectedMinutes + " min");
        Toast.makeText(this, "Mise en veille dans " + selectedMinutes + " minutes",
                Toast.LENGTH_LONG).show();
    }

    private void updateTimerDisplay() {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTimeMillis);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(remainingTimeMillis) -
                TimeUnit.MINUTES.toSeconds(minutes);

        String timeString = String.format("%02d:%02d", minutes, seconds);
        tvTimerValue.setText(timeString);
    }

    private void turnOffScreen() {
        runOnUiThread(() -> {
            // Acquire wake lock to keep casting alive
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK |
                            PowerManager.ACQUIRE_CAUSES_WAKEUP |
                            PowerManager.ON_AFTER_RELEASE,
                    "ScreenCastSleep::CastWakeLock"
            );

            try {
                wakeLock.acquire(60 * 60 * 1000L); // 1 hour max
            } catch (SecurityException e) {
                e.printStackTrace();
            }

            // Turn off the screen
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = 0;
            getWindow().setAttributes(params);

            // Simulate sleep by finishing activity
            moveTaskToBack(true);

            Toast.makeText(this,
                    "Écran éteint - Casting en cours!",
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void stopCasting() {
        // Stop timer
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        timerHandler.removeCallbacks(timerRunnable);
        remainingTimeMillis = 0;

        // Stop service
        isScreenCapturing = false;
        isCasting = false;

        Intent serviceIntent = new Intent(this, ScreenCastService.class);
        stopService(serviceIntent);

        // Disconnect WiFi Direct
        if (wifiP2pManager != null && channel != null) {
            wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Toast.makeText(MainActivity.this, "Déconnecté", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(int reason) {
                    // Ignore failures
                }
            });
        }

        // Update UI
        updateUIState();
        tvStatus.setText("Casting arrêté");
        tvTimerValue.setText(selectedMinutes + " min");
    }

    private void updateUIState() {
        if (isScreenCapturing) {
            btnStartCast.setEnabled(false);
            btnStopCast.setEnabled(true);
            sliderMinutes.setEnabled(false);
            btnDiscoverDevices.setEnabled(false);
            ivCastIcon.setImageResource(R.drawable.ic_cast_on);
        } else {
            btnStartCast.setEnabled(true);
            btnStopCast.setEnabled(false);
            sliderMinutes.setEnabled(true);
            btnDiscoverDevices.setEnabled(true);
            ivCastIcon.setImageResource(R.drawable.ic_cast_off);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Cast Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification pour le service de casting d'écran");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Restore UI if returning from settings
        if (isScreenCapturing && remainingTimeMillis > 0) {
            timerHandler.post(timerRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Continue timer in background
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        timerHandler.removeCallbacks(timerRunnable);
    }
}

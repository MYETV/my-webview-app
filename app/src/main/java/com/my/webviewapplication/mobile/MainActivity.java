package com.my.webviewapplication.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.ServiceWorkerClient;
import android.webkit.WebResourceResponse;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Simple callback interface for HTTP warning dialog result
 * Used by checkHTTPSWarningAsync() to handle user choice
 */
interface HTTPSWarningCallback {
    void onResult(boolean proceed);
}

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int NOTIFICATION_PERMISSION_CODE = 101;

    private CustomWebView mainWebView;
    private CustomWebView secondaryWebView;

    private FrameLayout webViewContainer;
    private FrameLayout secondaryWebViewContainer;
    private View offlineView;

    private boolean isOffline = false;
    private String fcmToken = "";
    private Handler networkCheckHandler;
    private Runnable networkCheckRunnable;
    private String currentUrl = "";
    // Pull-to-refresh layout reference
    private SwipeRefreshLayout swipeRefreshLayout;
    private SwipeRefreshLayout secondarySwipeRefreshLayout;
    // Progress bar managers for main and secondary WebView
    private ProgressBarManager mainProgressBarManager;
    private ProgressBarManager secondaryProgressBarManager;
    // Display current URL in secondary WebView (informational, non-editable)
    private TextView secondaryUrlDisplay;
    // Display SSL certificate icon and status for secondary WebView
    private ImageView secondarySslIcon;
    private SslCertificate lastSecondarySSLCertificate;
    // Track if WebView has finished loading (used by splash screen)
    private boolean isWebViewLoaded = false;
    // Swipe gesture detection for navigation
    private GestureDetector gestureDetector;
    private static final int SWIPE_THRESHOLD = 100;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;
    // Back press dispatcher callback
    private OnBackPressedCallback onBackPressedCallback;
    // For permission callbacks
    private PermissionRequest pendingPermissionRequest;
    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;
    private ValueCallback<Uri[]> mFilePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 103;
    // Track Do Not Disturb mode state
    private boolean isDndModeActive = false;
    private int previousInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL;
    private boolean isRestoringDnd = false;
    // SharedPreferences key for DND mode persistence
    private static final String PREF_NAME = "MWAPrefs";
    private static final String PREF_DND_ENABLED = "dnd_mode_enabled";

    private final ActivityResultLauncher<ScanOptions> qrScannerLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) {
                    Log.d(TAG, "QR Scan cancelled");
                    Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show();
                } else {
                    String scannedUrl = result.getContents();
                    Log.d(TAG, "QR Scanned: " + scannedUrl);

                    // verify if url is valid
                    if (scannedUrl.startsWith("http://") || scannedUrl.startsWith("https://")) {
                        mainWebView.loadUrl(scannedUrl);
                    } else if (scannedUrl.contains(".")) {
                        // try to add https if miss
                        mainWebView.loadUrl("https://" + scannedUrl);
                    } else {
                        Toast.makeText(this, "Invalid URL: " + scannedUrl, Toast.LENGTH_LONG).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: START");

        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: After super.onCreate");

        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: After setContentView");

        // Initialize views
        initializeViews();
        Log.d(TAG, "onCreate: After initializeViews");

        mainWebView.addJavascriptInterface(new AndroidInterface(), "AndroidInterface");

        // Setup UI
        setupUI();
        Log.d(TAG, "onCreate: After setupUI");

        // Setup orientation
        setupOrientation();
        Log.d(TAG, "onCreate: After setupOrientation");

        // Check permissions
        checkPermissions();
        Log.d(TAG, "onCreate: After checkPermissions");

        // Initialize Firebase
        if (Config.ENABLE_FIREBASE_PUSH) {
            initializeFirebase();
        }
        Log.d(TAG, "onCreate: After initializeFirebase");

        // Setup Service Worker support
        setupServiceWorker();

        // Check network and load content
        checkNetworkAndLoad();
        Log.d(TAG, "onCreate: After checkNetworkAndLoad");

        // Handle deep links
        handleIntent(getIntent());
        Log.d(TAG, "onCreate: END");

        // Initialize progress bar managers
        initializeProgressBars();
        Log.d(TAG, "onCreate: After initializeProgressBars");

        // Initialize swipe navigation if enabled
        if (Config.ENABLE_SWIPE_NAVIGATION) {
            initializeSwipeNavigation();
            Log.d(TAG, "onCreate: After initializeSwipeNavigation");
        }

        // Initialize back button handling using AndroidX OnBackPressedDispatcher
        initializeBackButtonHandler();
        Log.d(TAG, "onCreate: After initializeBackButtonHandler");

    }

    private long pauseTime = 0;
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: App going to background");

        // Force cookies sync from RAM to persistent storage before app goes to background
        CookieManager.getInstance().flush();

        // Disable DND mode when app goes to background
        if (isDndModeActive) {
            Log.d(TAG, "onPause: Disabling DND mode");
            disableDndMode();
        }

        // Record pause timestamp
        pauseTime = System.currentTimeMillis();

        // Stop network monitoring to prevent false positives when switching apps
        if (networkCheckHandler != null && networkCheckRunnable != null) {
            networkCheckHandler.removeCallbacks(networkCheckRunnable);
            Log.d(TAG, "onPause: Network monitoring STOPPED");
        }

        // Check if we're in PiP mode before pausing WebView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isInPictureInPictureMode()) {
                // Not in PiP mode - pause WebView normally if configured
                if (Config.STOP_APP_IN_BACKGROUND) {
                    if (mainWebView != null) {
                        mainWebView.onPause();
                        mainWebView.pauseTimers();
                    }
                    if (secondaryWebView != null) {
                        secondaryWebView.onPause();
                    }
                }
            } else {
                // In PiP mode - do NOT pause the WebView
                Log.d(TAG, "onPause: In PiP mode - video playback continues");
            }
        } else {
            // Android version < N (7.0) - no PiP support
            if (Config.STOP_APP_IN_BACKGROUND) {
                if (mainWebView != null) {
                    mainWebView.onPause();
                    mainWebView.pauseTimers();
                }
                if (secondaryWebView != null) {
                    secondaryWebView.onPause();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: App returning to foreground");

        // Resume WebView
        if (mainWebView != null) {
            mainWebView.onResume();
            mainWebView.resumeTimers();

            // Reload initial URL only if STOP_APP_IN_BACKGROUND is true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (Config.STOP_APP_IN_BACKGROUND && !isInPictureInPictureMode()) {
                    Log.d(TAG, "onResume: Reloading HOME_URL");
                    loadMainUrl();
                }
            } else {
                if (Config.STOP_APP_IN_BACKGROUND) {
                    Log.d(TAG, "onResume: Reloading HOME_URL");
                    loadMainUrl();
                }
            }
        }

        if (secondaryWebView != null) {
            secondaryWebView.onResume();
        }

        // Restore DND mode if user had it enabled - ONLY if permission granted
        if (Config.ENABLE_DND_MODE && !isRestoringDnd) {
            isRestoringDnd = true; // Prevent re-entry

            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // Only restore if we have permission
            if (notificationManager.isNotificationPolicyAccessGranted()) {
                boolean userWantsDnd = loadDndPreference();
                if (userWantsDnd && !isDndModeActive) {
                    Log.d(TAG, "onResume: Restoring DND mode from user preference");
                    enableDndMode();
                }
            }

            isRestoringDnd = false;
        }

        // Restart network monitoring if not offline
        if (!isOffline) {
            Log.d(TAG, "onResume: Restarting network monitoring");
            startNetworkMonitoring();
        }
    }

    private void initializeViews() {
        // Find container views from layout
        webViewContainer = findViewById(R.id.webview_container);
        FrameLayout secondaryContainer = findViewById(R.id.secondary_container);
        secondaryWebViewContainer = findViewById(R.id.secondary_webview_container);
        offlineView = findViewById(R.id.offline_view);

        // Initialize SwipeRefreshLayout for main WebView pull-to-refresh
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        if (Config.ENABLEPULLTOREFRESH && swipeRefreshLayout != null) {
            initializeSwipeRefresh();
        } else if (swipeRefreshLayout != null) {
            // Disable pull-to-refresh explicitly when disabled in config
            swipeRefreshLayout.setEnabled(false);
            Log.d(TAG, "Main WebView pull-to-refresh disabled");
        }

        // Initialize SwipeRefreshLayout for secondary WebView pull-to-refresh
        // Only initialize if secondary pull-to-refresh is enabled in Config
        secondarySwipeRefreshLayout = findViewById(R.id.secondary_swipe_refresh_layout);
        if (Config.ENABLEPULLTOREFRESHSECONDARY && secondarySwipeRefreshLayout != null) {
            initializeSecondarySwipeRefresh();
        }

        // Initialize main WebView - use CustomWebView for scroll tracking
        mainWebView = new CustomWebView(this);
        setupWebView(mainWebView, true);
        webViewContainer.addView(mainWebView);

        // Setup scroll listener for main WebView to control refresh
        mainWebView.setOnScrollChangeListener((scrollY, isAtTop) -> {
            if (swipeRefreshLayout != null && Config.ENABLEPULLTOREFRESH) {
                // Enable refresh only when at top of page
                swipeRefreshLayout.setEnabled(isAtTop);
                Log.d(TAG, "Main WebView scroll - ScrollY: " + scrollY + " | Refresh enabled: " + isAtTop);
            }
        });

        // Initialize secondary WebView - use CustomWebView for scroll tracking
        secondaryWebView = new CustomWebView(this);
        setupWebView(secondaryWebView, false);
        secondaryWebViewContainer.addView(secondaryWebView);
        // Initialize secondary URL display TextView
        secondaryUrlDisplay = findViewById(R.id.secondary_url_display);
        // Initialize secondary SSL certificate icon
        secondarySslIcon = findViewById(R.id.secondary_ssl_icon);
        if (secondarySslIcon != null) {
            // Make icon clickable to show certificate details
            secondarySslIcon.setOnClickListener(v -> showSSLCertificateDialog());
        }

        // Make entire toolbar clickable
        LinearLayout secondaryTopBar = findViewById(R.id.secondary_top_bar);
        if (secondaryTopBar != null) {
            secondaryTopBar.setOnClickListener(v -> showSSLCertificateDialog());
            Log.d(TAG, "Secondary top bar click listener set");
        }

        // Setup scroll listener for secondary WebView to control refresh
        secondaryWebView.setOnScrollChangeListener((scrollY, isAtTop) -> {
            if (secondarySwipeRefreshLayout != null && Config.ENABLEPULLTOREFRESHSECONDARY) {
                // Enable refresh only when at top of page
                secondarySwipeRefreshLayout.setEnabled(isAtTop);
                Log.d(TAG, "Secondary WebView scroll - ScrollY: " + scrollY + " | Refresh enabled: " + isAtTop);
            }
        });

        secondaryContainer.setVisibility(View.GONE);

        // Setup close button for secondary WebView
        findViewById(R.id.close_secondary_webview).setOnClickListener(v -> closeSecondaryWebView());

        // Apply colors to secondary browser UI
        applySecondaryBrowserColors();
    }

    /**
     * Initialize SwipeRefreshLayout for pull-to-refresh functionality
     * Refresh is controlled by WebView scroll position via setEnabled()
     * Only active when WebView is scrolled to the top
     */
    private void initializeSwipeRefresh() {
        if (swipeRefreshLayout == null) {
            Log.w(TAG, "SwipeRefreshLayout is null");
            return;
        }

        Log.d(TAG, "Initializing main WebView pull-to-refresh");

        // Start disabled - will be enabled by scroll listener when at top
        swipeRefreshLayout.setEnabled(false);

        // Set spinner color (Twitter/X blue)
        swipeRefreshLayout.setColorSchemeColors(Config.PULLTOREFRESHSPINNERCOLOR);

        // Set background color
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(Config.PULLTOREFRESHBACKGROUNDCOLOR);

        // Set distance to trigger refresh (in pixels)
        swipeRefreshLayout.setDistanceToTriggerSync(Config.PULLTOREFRESHDISTANCE);

        // Set the refresh listener - triggered when user swipes down
        swipeRefreshLayout.setOnRefreshListener(() -> {
            Log.d(TAG, "Main pull-to-refresh triggered");

            if (mainWebView == null) {
                Log.w(TAG, "mainWebView is null - cannot refresh");
                swipeRefreshLayout.setRefreshing(false);
                return;
            }

            // Verify we're still at top before refreshing
            if (!mainWebView.isAtTop()) {
                Log.w(TAG, "WebView is not at top - cancelling refresh");
                swipeRefreshLayout.setRefreshing(false);
                return;
            }

            // Get the currently loaded URL
            String currentUrl = mainWebView.getUrl();

            if (currentUrl == null || currentUrl.isEmpty()) {
                Log.w(TAG, "No URL to refresh");
                swipeRefreshLayout.setRefreshing(false);
                return;
            }

            Log.d(TAG, "Reloading main WebView URL: " + currentUrl);

            // Reload the current URL
            mainWebView.reload();

            // Stop spinner after timeout if page doesn't finish loading
            mainWebView.postDelayed(() -> {
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    Log.d(TAG, "Main refresh timeout - stopping spinner");
                    swipeRefreshLayout.setRefreshing(false);
                    swipeRefreshLayout.setEnabled(false);
                }
            }, Config.PULLTOREFRESHDISAPPEARTIMEOUT);
        });

        Log.d(TAG, "Main pull-to-refresh initialized");
    }

    /**
     * Initialize SwipeRefreshLayout for secondary WebView pull-to-refresh
     * Refresh is controlled by WebView scroll position via setEnabled()
     * Only active when WebView is scrolled to the top
     */
    private void initializeSecondarySwipeRefresh() {
        if (secondarySwipeRefreshLayout == null) {
            Log.w(TAG, "Secondary SwipeRefreshLayout is null");
            return;
        }

        Log.d(TAG, "Initializing secondary WebView pull-to-refresh");

        // Start disabled - will be enabled by scroll listener when at top
        secondarySwipeRefreshLayout.setEnabled(false);

        // Set spinner color (Twitter/X blue)
        secondarySwipeRefreshLayout.setColorSchemeColors(Config.PULLTOREFRESHSPINNERCOLOR);

        // Set background color
        secondarySwipeRefreshLayout.setProgressBackgroundColorSchemeColor(Config.PULLTOREFRESHBACKGROUNDCOLOR);

        // Set distance to trigger refresh (in pixels)
        secondarySwipeRefreshLayout.setDistanceToTriggerSync(Config.PULLTOREFRESHDISTANCE);

        // Set the refresh listener - triggered when user swipes down
        secondarySwipeRefreshLayout.setOnRefreshListener(() -> {
            Log.d(TAG, "Secondary pull-to-refresh triggered");

            if (secondaryWebView == null) {
                Log.w(TAG, "secondaryWebView is null - cannot refresh");
                secondarySwipeRefreshLayout.setRefreshing(false);
                return;
            }

            // Verify we're still at top before refreshing
            if (!secondaryWebView.isAtTop()) {
                Log.w(TAG, "Secondary WebView is not at top - cancelling refresh");
                secondarySwipeRefreshLayout.setRefreshing(false);
                return;
            }

            // Get the currently loaded URL
            String currentUrl = secondaryWebView.getUrl();

            if (currentUrl == null || currentUrl.isEmpty()) {
                Log.w(TAG, "No URL to refresh in secondary WebView");
                secondarySwipeRefreshLayout.setRefreshing(false);
                return;
            }

            Log.d(TAG, "Reloading secondary WebView URL: " + currentUrl);

            // Reload the current URL
            secondaryWebView.reload();

            // Stop spinner after timeout if page doesn't finish loading
            secondaryWebView.postDelayed(() -> {
                if (secondarySwipeRefreshLayout != null && secondarySwipeRefreshLayout.isRefreshing()) {
                    Log.d(TAG, "Secondary refresh timeout - stopping spinner");
                    secondarySwipeRefreshLayout.setRefreshing(false);
                    secondarySwipeRefreshLayout.setEnabled(false);
                }
            }, Config.PULLTOREFRESHDISAPPEARTIMEOUT);
        });

        Log.d(TAG, "Secondary pull-to-refresh initialized");
    }

    /**
     * Update SSL certificate icon color based on connection security status
     * Green lock = Valid SSL certificate (HTTPS secure)
     * Yellow warning = Invalid SSL certificate (HTTPS but cert issues)
     * Red warning = No SSL certificate (HTTP insecure)
     *
     * @param url The loaded URL
     * @param sslCertificate The SSL certificate if available
     */
    private void updateSSLCertificateIcon(String url, SslCertificate sslCertificate) {
        if (secondarySslIcon == null) {
            return;
        }

        try {
            if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                lastSecondarySSLCertificate = sslCertificate;

                if (url.startsWith("https://")) {
                    // HTTPS connection
                    if (sslCertificate != null) {
                        // Valid SSL certificate - show GREEN closed lock icon
                        secondarySslIcon.setImageResource(android.R.drawable.ic_secure);
                        secondarySslIcon.setColorFilter(android.graphics.Color.GREEN,
                                android.graphics.PorterDuff.Mode.SRC_IN);
                        secondarySslIcon.setVisibility(View.VISIBLE);
                        Log.d(TAG, "HTTPS with valid certificate - showing GREEN secure icon");
                    } else {
                        // HTTPS but no valid certificate - show YELLOW warning icon
                        secondarySslIcon.setImageResource(android.R.drawable.ic_dialog_alert);
                        secondarySslIcon.setColorFilter(android.graphics.Color.YELLOW,
                                android.graphics.PorterDuff.Mode.SRC_IN);
                        secondarySslIcon.setVisibility(View.VISIBLE);
                        Log.w(TAG, "HTTPS without valid certificate - showing YELLOW warning icon");
                    }
                } else if (url.startsWith("http://")) {
                    // HTTP - no SSL certificate - show RED warning icon
                    secondarySslIcon.setImageResource(android.R.drawable.ic_dialog_alert);
                    secondarySslIcon.setColorFilter(android.graphics.Color.RED,
                            android.graphics.PorterDuff.Mode.SRC_IN);
                    secondarySslIcon.setVisibility(View.VISIBLE);
                    Log.w(TAG, "HTTP insecure connection (no certificate) - showing RED warning icon");
                } else {
                    // Other protocols or blank pages - hide icon
                    secondarySslIcon.setVisibility(View.GONE);
                    lastSecondarySSLCertificate = null;
                }
            } else {
                // Blank or empty URL - hide icon
                secondarySslIcon.setVisibility(View.GONE);
                lastSecondarySSLCertificate = null;
                Log.d(TAG, "Blank URL - hiding SSL icon");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating SSL icon: " + e.getMessage());
            secondarySslIcon.setVisibility(View.GONE);
        }
    }

    /**
     * Check if URL is HTTP (non-secure) and show warning dialog if configured
     * Uses translated strings from resources
     * Uses callback to handle async user choice
     *
     * @param url The URL to check
     * @param callback Callback: proceed=true means load URL, proceed=false means cancel loading
     */
    private void checkHTTPSWarningAsync(String url, HTTPSWarningCallback callback) {
        // If warning is disabled in config, always allow loading
        if (!Config.WARN_IF_NOT_HTTPS) {
            callback.onResult(true);
            return;
        }

        // Only warn about HTTP (non-secure) URLs
        if (url != null && url.startsWith("http://")) {
            Log.w(TAG, "HTTP URL detected - showing warning dialog for: " + url);

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(getString(R.string.http_warning_title))
                    .setMessage(getString(R.string.http_warning_message))
                    .setPositiveButton(getString(R.string.http_warning_continue), (dialog, which) -> {
                        // User chose to continue - load the URL
                        Log.d(TAG, "User chose to continue with HTTP URL: " + url);
                        callback.onResult(true);
                    })
                    .setNegativeButton(getString(R.string.http_warning_cancel), (dialog, which) -> {
                        // User chose to cancel
                        Log.d(TAG, "User cancelled HTTP URL loading: " + url);
                        callback.onResult(false);
                    })
                    .setCancelable(false)
                    .show();
        } else {
            // URL is HTTPS or other protocol - allow loading
            callback.onResult(true);
        }
    }

    /**
     * Show SSL certificate details and URL in an AlertDialog
     * Displays issuer, subject, valid dates, DName information, and full URL (copyable)
     */
    private void showSSLCertificateDialog() {
        // Get current URL from secondary WebView
        String currentUrl = "";
        if (secondaryWebView != null) {
            currentUrl = secondaryWebView.getUrl();
            if (currentUrl == null || currentUrl.isEmpty()) {
                currentUrl = "about:blank";
            }
        }

        final String finalUrl = currentUrl;

        // Build dialog message
        StringBuilder message = new StringBuilder();

        // Add URL section
        message.append("═══ URL ═══\n");
        message.append(finalUrl).append("\n\n");

        // Add certificate information
        if (lastSecondarySSLCertificate == null) {
            message.append("═══ Certificate ═══\n");
            message.append("No certificate information available");
        } else {
            try {
                // Extract certificate information
                String issuedBy = lastSecondarySSLCertificate.getIssuedBy().getDName();
                String issuedTo = lastSecondarySSLCertificate.getIssuedTo().getDName();
                String validNotBefore = lastSecondarySSLCertificate.getValidNotBefore();
                String validNotAfter = lastSecondarySSLCertificate.getValidNotAfter();

                message.append("═══ Certificate ═══\n");
                message.append("Issued To:\n").append(issuedTo).append("\n\n");
                message.append("Issued By:\n").append(issuedBy).append("\n\n");
                message.append("Valid From:\n").append(validNotBefore).append("\n\n");
                message.append("Valid Until:\n").append(validNotAfter);

                Log.d(TAG, "Certificate Details: " + message.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error reading certificate: " + e.getMessage());
                message.append("═══ Certificate ═══\n");
                message.append("Error reading certificate information");
            }
        }

        // Show dialog with Copy URL button
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("Site Information")
                .setMessage(message.toString())
                .setPositiveButton("Copy URL", (dialog, which) -> {
                    // Copy URL to clipboard
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("URL", finalUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "URL copied to clipboard: " + finalUrl);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Detects if dark mode is enabled
     * @return "dark" if dark mode is active, "light" otherwise
     */
    private String getCurrentTheme() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (nightModeFlags) {
            case Configuration.UI_MODE_NIGHT_YES:
                return "dark";
            case Configuration.UI_MODE_NIGHT_NO:
                return "light";
            default:
                return "light"; // Default to light if undefined
        }
    }

    private void applySecondaryBrowserColors() {
        try {
            // Find top bar and title view
            LinearLayout topBar = findViewById(R.id.secondary_top_bar);
            TextView titleView = findViewById(R.id.secondary_title);
            com.google.android.material.button.MaterialButton closeButton =
                    findViewById(R.id.close_secondary_webview);

            // apply colors to top bar
            topBar.setBackgroundColor(android.graphics.Color.parseColor(Config.SECONDARY_BROWSER_BG_COLOR));

            // apply colors to title
            titleView.setTextColor(android.graphics.Color.parseColor(Config.SECONDARY_BROWSER_TEXT_COLOR));

            // apply colors to close button
            closeButton.setTextColor(android.graphics.Color.parseColor(Config.SECONDARY_BROWSER_TEXT_COLOR));
            closeButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor(Config.SECONDARY_BROWSER_BUTTON_COLOR)
                    )
            );

            // Apply height to top bar (optional)
            android.view.ViewGroup.LayoutParams topBarParams = topBar.getLayoutParams();
            topBarParams.height = (int) (Config.SECONDARY_BROWSER_TOP_BAR_HEIGHT *
                    getResources().getDisplayMetrics().density);
            topBar.setLayoutParams(topBarParams);

            // Apply height to close button (optional)
            android.view.ViewGroup.LayoutParams buttonParams = closeButton.getLayoutParams();
            int buttonSize = (int) (Config.SECONDARY_BROWSER_BUTTON_SIZE *
                    getResources().getDisplayMetrics().density);
            buttonParams.width = buttonSize;
            buttonParams.height = buttonSize;
            closeButton.setLayoutParams(buttonParams);

            // Apply title visibility
            titleView.setVisibility(Config.SECONDARY_BROWSER_SHOW_TITLE ? View.VISIBLE : View.GONE);

            // Apply title size
            titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    Config.SECONDARY_BROWSER_TITLE_SIZE);

            // Apply close button text
            closeButton.setText(Config.SECONDARY_BROWSER_BUTTON_TEXT);

            // Apply close button text size
            closeButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    Config.SECONDARY_BROWSER_BUTTON_TEXT_SIZE);

            // Apply elevation
            topBar.setElevation(Config.SECONDARY_BROWSER_TOP_BAR_ELEVATION *
                    getResources().getDisplayMetrics().density);

            // Apply corner radius
            closeButton.setCornerRadius((int) (Config.SECONDARY_BROWSER_BUTTON_CORNER_RADIUS *
                    getResources().getDisplayMetrics().density));

            Log.d(TAG, "Secondary browser colors applied successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error applying secondary browser colors: " + e.getMessage());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView webView, boolean isMain) {
        WebSettings settings = webView.getSettings();

        // --- GESTIONE DINAMICA DEL COLORE DI SFONDO ---
        // Se l'opzione è attiva, usa il tema corrente per decidere lo sfondo
        if (Config.ENHANCE_URL_WITH_THEME) {
            if ("dark".equals(getCurrentTheme())) {
                webView.setBackgroundColor(android.graphics.Color.parseColor("#000000")); // Sfondo nero
            } else {
                webView.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF")); // Sfondo bianco
            }
        } else {
            // Se l'opzione è disattivata, usa trasparente
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
        // ----------------------------------------------

        // Basic settings
        settings.setJavaScriptEnabled(Config.ENABLE_JAVASCRIPT);
        settings.setDomStorageEnabled(Config.ENABLE_DOM_STORAGE);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(Config.ENABLE_ZOOM);
        settings.setBuiltInZoomControls(Config.ENABLE_ZOOM);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Support for fullscreen video and media
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setPluginState(WebSettings.PluginState.ON);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // HTML5 Geolocation API support
        settings.setGeolocationEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setGeolocationDatabasePath(getFilesDir().getPath());
        }

        // Support for fullscreen video
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        // === CACHE MANAGEMENT (only main WebView on start) ===
        if (isMain && Config.CLEAR_CACHE_ON_START) {
            Log.d(TAG, "Clearing cache on start");
            webView.clearCache(true);
        }

        // Cache mode - Enable or disable cache usage
        if (Config.USE_CACHE) {
            Log.d(TAG, "Cache enabled: LOAD_DEFAULT");
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        } else {
            Log.d(TAG, "Cache disabled: LOAD_NO_CACHE");
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        }

        settings.setDatabaseEnabled(true);

        // === COOKIES MANAGEMENT ===
        // Get CookieManager instance and explicitly enable standard cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        if (isMain && Config.CLEAR_COOKIES_ON_START) {
            Log.d(TAG, "Clearing cookies on start");
            cookieManager.removeAllCookies(null);
            cookieManager.flush();
        }

        // Mixed content
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        // User agent - Apply to BOTH main and secondary WebView
        if (!Config.CUSTOM_USER_AGENT.isEmpty()) {
            settings.setUserAgentString(Config.CUSTOM_USER_AGENT);
            Log.d(TAG, "Custom UserAgent applied" + (isMain ? " (Main)" : " (Secondary)"));
        }

        // Hardware acceleration
        if (Config.ENABLE_HARDWARE_ACCELERATION) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        // WebView clients
        webView.setWebViewClient(new MyWebViewClient(isMain));
        webView.setWebChromeClient(new MyWebChromeClient());
    }

    private void setupUI() {
        // Handle FULLSCREEN_MODE and HIDE_STATUS_BAR combinations
        if (Config.FULLSCREEN_MODE) {
            // Hide the custom status bar background in fullscreen modes
            View statusBarBg = findViewById(R.id.statusBarBackground);
            if (statusBarBg != null) {
                statusBarBg.setVisibility(View.GONE);
            }

            // Remove padding from content
            removeStatusBarPadding();

            if (Config.HIDE_STATUS_BAR) {
                // Case 1: FULLSCREEN with HIDDEN status bar
                Log.d(TAG, "Fullscreen mode WITH hidden status bar");
                getWindow().setFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    View decorView = getWindow().getDecorView();
                    decorView.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );
                }
            } else {
                // Case 2: FULLSCREEN but VISIBLE status bar (content extends under status bar)
                Log.d(TAG, "Fullscreen mode WITH visible status bar");

                // Set status bar color from Config (will be visible when user swipes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                    getWindow().setStatusBarColor(android.graphics.Color.parseColor(Config.STATUS_BAR_COLOR));
                    Log.d(TAG, "Status bar color set to: " + Config.STATUS_BAR_COLOR);
                }

                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    View decorView = getWindow().getDecorView();
                    decorView.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    );
                }
            }
        } else {
            // Case 3: NOT fullscreen - normal mode (status bar visible with colored background)
            Log.d(TAG, "Normal mode (not fullscreen)");

            // Clear fullscreen flags completely
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

            // Force the status bar to be OPAQUE with color from Config
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getWindow().setStatusBarColor(android.graphics.Color.parseColor(Config.STATUS_BAR_COLOR));
                Log.d(TAG, "Status bar color set to: " + Config.STATUS_BAR_COLOR);
            }

            // Clear all immersive and layout flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                View decorView = getWindow().getDecorView();
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

            // Show custom colored status bar background and add padding
            setupStatusBarBackground();
        }

        // Handle status bar text color (independent from fullscreen)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decorView = getWindow().getDecorView();
            int currentVisibility = decorView.getSystemUiVisibility();

            if (Config.DARK_STATUS_BAR_TEXT) {
                // Dark text on light background
                decorView.setSystemUiVisibility(currentVisibility | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                Log.d(TAG, "Status bar text: DARK");
            } else {
                // Light text on dark background
                decorView.setSystemUiVisibility(currentVisibility & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                Log.d(TAG, "Status bar text: LIGHT");
            }
        }
    }

    /**
     * Setup status bar background and add padding to avoid overlap
     * Only called when FULLSCREEN_MODE = false
     */
    private void setupStatusBarBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Get status bar height
            int statusBarHeight = 0;
            int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                statusBarHeight = getResources().getDimensionPixelSize(resourceId);
            }

            Log.d(TAG, "Status bar height: " + statusBarHeight + "px");

            // Show and size the custom status bar background for main WebView
            View statusBarBg = findViewById(R.id.statusBarBackground);
            if (statusBarBg != null) {
                statusBarBg.setVisibility(View.VISIBLE);
                statusBarBg.getLayoutParams().height = statusBarHeight;
                statusBarBg.setBackgroundColor(android.graphics.Color.parseColor(Config.STATUS_BAR_COLOR));
                statusBarBg.requestLayout();
                Log.d(TAG, "Main status bar background set to: " + Config.STATUS_BAR_COLOR);
            }

            // Show and size the custom status bar background for secondary WebView
            View secondaryStatusBarBg = findViewById(R.id.secondaryStatusBarBackground);
            if (secondaryStatusBarBg != null) {
                secondaryStatusBarBg.setVisibility(View.VISIBLE);
                secondaryStatusBarBg.getLayoutParams().height = statusBarHeight;
                secondaryStatusBarBg.setBackgroundColor(android.graphics.Color.parseColor(Config.STATUS_BAR_COLOR));
                secondaryStatusBarBg.requestLayout();
                Log.d(TAG, "Secondary status bar background set to: " + Config.STATUS_BAR_COLOR);
            }

            // Add padding to webview container to push content below status bar background
            View webViewContainer = findViewById(R.id.webview_container);
            if (webViewContainer != null) {
                webViewContainer.setPadding(0, statusBarHeight, 0, 0);
                Log.d(TAG, "WebView container padding added: " + statusBarHeight + "px");
            }

            // For secondary container: add top margin to secondary_top_bar
            LinearLayout secondaryTopBar = findViewById(R.id.secondary_top_bar);
            if (secondaryTopBar != null) {
                android.view.ViewGroup.MarginLayoutParams params =
                        (android.view.ViewGroup.MarginLayoutParams) secondaryTopBar.getLayoutParams();
                params.topMargin = statusBarHeight;
                secondaryTopBar.setLayoutParams(params);
                Log.d(TAG, "Secondary top bar margin added: " + statusBarHeight + "px");
            }

            // Update secondary_swipe_refresh_layout margin to account for status bar + top bar
            androidx.swiperefreshlayout.widget.SwipeRefreshLayout secondarySwipeRefresh =
                    findViewById(R.id.secondary_swipe_refresh_layout);
            if (secondarySwipeRefresh != null) {
                android.view.ViewGroup.MarginLayoutParams params =
                        (android.view.ViewGroup.MarginLayoutParams) secondarySwipeRefresh.getLayoutParams();
                // 56dp (top bar height) + status bar height
                int topBarHeightDp = 56;
                int topBarHeightPx = (int) (topBarHeightDp * getResources().getDisplayMetrics().density);
                params.topMargin = topBarHeightPx + statusBarHeight;
                secondarySwipeRefresh.setLayoutParams(params);
                Log.d(TAG, "Secondary swipe refresh margin set to: " + params.topMargin + "px");
            }
        }
    }

    /**
     * Remove status bar padding and hide custom background
     * Called when entering fullscreen mode
     */
    private void removeStatusBarPadding() {
        // Hide main status bar background
        View statusBarBg = findViewById(R.id.statusBarBackground);
        if (statusBarBg != null) {
            statusBarBg.setVisibility(View.GONE);
        }

        // Hide secondary status bar background
        View secondaryStatusBarBg = findViewById(R.id.secondaryStatusBarBackground);
        if (secondaryStatusBarBg != null) {
            secondaryStatusBarBg.setVisibility(View.GONE);
        }

        // Remove padding from main webview container
        View webViewContainer = findViewById(R.id.webview_container);
        if (webViewContainer != null) {
            webViewContainer.setPadding(0, 0, 0, 0);
            Log.d(TAG, "Main WebView container padding removed");
        }

        // Remove top margin from secondary top bar
        LinearLayout secondaryTopBar = findViewById(R.id.secondary_top_bar);
        if (secondaryTopBar != null) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) secondaryTopBar.getLayoutParams();
            params.topMargin = 0;
            secondaryTopBar.setLayoutParams(params);
            Log.d(TAG, "Secondary top bar margin removed");
        }

        // Reset secondary_swipe_refresh_layout margin to original (56dp for top bar only)
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout secondarySwipeRefresh =
                findViewById(R.id.secondary_swipe_refresh_layout);
        if (secondarySwipeRefresh != null) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) secondarySwipeRefresh.getLayoutParams();
            int topBarHeightDp = 56;
            int topBarHeightPx = (int) (topBarHeightDp * getResources().getDisplayMetrics().density);
            params.topMargin = topBarHeightPx;
            secondarySwipeRefresh.setLayoutParams(params);
            Log.d(TAG, "Secondary swipe refresh margin reset to: " + params.topMargin + "px");
        }
    }

    /**
     * Toggle Do Not Disturb mode on/off
     * Saves user preference to persist across app restarts
     */
    private void toggleDndMode() {
        if (!Config.ENABLE_DND_MODE) {
            Log.d(TAG, "DND mode is disabled in Config");
            Toast.makeText(this, R.string.dnd_mode_disabled_config, Toast.LENGTH_SHORT).show();
            return;
        }

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Check if app has DND permission
        if (!notificationManager.isNotificationPolicyAccessGranted()) {
            Log.d(TAG, "DND permission not granted - requesting permission");
            showDndPermissionDialog();
            return; // Exit here - don't try to toggle yet
        }

        // Toggle DND state based on current state
        if (isDndModeActive) {
            // User wants to DISABLE DND
            Log.d(TAG, "User toggling DND OFF");
            disableDndMode();
            saveDndPreference(false); // Save: user wants it OFF
        } else {
            // User wants to ENABLE DND
            Log.d(TAG, "User toggling DND ON");
            enableDndMode();
            saveDndPreference(true); // Save: user wants it ON
        }
    }

    /**
     * Save DND mode preference to SharedPreferences
     * @param enabled true to enable DND on app resume, false otherwise
     */
    private void saveDndPreference(boolean enabled) {
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(PREF_DND_ENABLED, enabled);
            editor.apply();
            Log.d(TAG, "DND preference saved: " + enabled);
        } catch (Exception e) {
            Log.e(TAG, "Error saving DND preference: " + e.getMessage());
        }
    }

    /**
     * Load DND mode preference from SharedPreferences
     * @return true if user wants DND enabled, false otherwise
     */
    private boolean loadDndPreference() {
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            boolean enabled = prefs.getBoolean(PREF_DND_ENABLED, false);
            Log.d(TAG, "DND preference loaded: " + enabled);
            return enabled;
        } catch (Exception e) {
            Log.e(TAG, "Error loading DND preference: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enable Do Not Disturb mode
     * Blocks all notifications from other apps
     */
    private void enableDndMode() {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // Save current interruption filter state
            previousInterruptionFilter = notificationManager.getCurrentInterruptionFilter();

            // Enable DND - block all interruptions
            notificationManager.setInterruptionFilter(
                    NotificationManager.INTERRUPTION_FILTER_NONE);

            isDndModeActive = true;
            Log.d(TAG, "DND mode ENABLED - notifications blocked");
            Toast.makeText(this, R.string.dnd_enabled, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error enabling DND mode: " + e.getMessage());
            Toast.makeText(this, R.string.dnd_error_enable, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Disable Do Not Disturb mode
     * Restores previous notification state
     */
    private void disableDndMode() {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // Restore previous interruption filter
            notificationManager.setInterruptionFilter(previousInterruptionFilter);

            isDndModeActive = false;
            Log.d(TAG, "DND mode DISABLED - notifications restored");
            Toast.makeText(this, R.string.dnd_disabled, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error disabling DND mode: " + e.getMessage());
            Toast.makeText(this, R.string.dnd_error_disable, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Show dialog to request DND permission from user
     * Opens Android Settings to grant permission
     */
    private void showDndPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dnd_permission_title)
                .setMessage(R.string.dnd_permission_message)
                .setPositiveButton(R.string.dnd_permission_open_settings, (dialog, which) -> {
                    try {
                        // Open DND permission settings
                        Intent intent = new Intent(
                                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        startActivity(intent);
                        Log.d(TAG, "Opened DND permission settings");
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening DND settings: " + e.getMessage());
                        Toast.makeText(this, R.string.dnd_error_open_settings,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dnd_permission_cancel, null)
                .show();
    }

    /**
     * Check if DND permission is granted
     * @return true if permission granted, false otherwise
     */
    private boolean isDndPermissionGranted() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return notificationManager.isNotificationPolicyAccessGranted();
    }

    /**
     * Configure Service Worker support for WebView
     * Service Workers allow sites to cache content, handle offline mode,
     * and provide advanced PWA functionality
     * Requires Android 7.0+ (API 24+)
     */
    private void setupServiceWorker() {
        // Service Workers are only supported from Android 7.0 (Nougat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                // Get the Service Worker controller instance
                ServiceWorkerController swController = ServiceWorkerController.getInstance();

                // Get Service Worker settings
                ServiceWorkerWebSettings swSettings = swController.getServiceWorkerWebSettings();

                // Enable cache for Service Workers (respects Config.USE_CACHE setting)
                swSettings.setCacheMode(Config.USE_CACHE ?
                        WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);

                // Allow Service Workers to access content
                swSettings.setAllowContentAccess(true);

                // Allow Service Workers to access files (if needed by your site)
                swSettings.setAllowFileAccess(true);

                // Allow Service Workers to make network requests
                swSettings.setBlockNetworkLoads(false);

                // Optional: Set up a client to intercept Service Worker requests
                // Useful for debugging or custom request handling
                swController.setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                        // Log Service Worker requests for debugging
                        Log.d(TAG, "Service Worker request: " + request.getUrl());

                        // Return null to allow normal request processing
                        // You can return a custom WebResourceResponse here if needed
                        return null;
                    }
                });

                Log.d(TAG, "Service Worker configured successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error configuring Service Worker: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Service Workers require Android 7.0+ (API 24+) - Current SDK: " + Build.VERSION.SDK_INT);
        }
    }

    private void setupOrientation() {
        String orientation = isTablet() ? Config.TABLET_ORIENTATION : Config.PHONE_ORIENTATION;

        switch (orientation.toLowerCase()) {
            case "portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case "landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            case "auto":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
                break;
            case "none":
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
        }
    }

    private boolean isTablet() {
        return (getResources().getConfiguration().screenLayout
                & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK)
                >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    /**
     * Check and request required permissions
     * Uses sequential callback to request notifications after camera permission is granted
     */
    private void checkPermissions() {
        // Always check camera first
        if (Config.REQUIRE_CAMERA && hasPermission(Manifest.permission.CAMERA)) {
            Log.d(TAG, "Requesting CAMERA permission");
            requestPermission(Manifest.permission.CAMERA, PERMISSION_REQUEST_CODE);
        } else {
            // Camera already granted or not required - check notifications
            checkNotificationPermission();
        }
    }

    /**
     * Check and request notification permission
     * Called after camera permission is resolved
     */
    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (Config.REQUIRE_NOTIFICATIONS && hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                Log.d(TAG, "Requesting NOTIFICATION permission");
                requestPermission(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_PERMISSION_CODE);
            } else {
                Log.d(TAG, "Notification permission already granted or not required");
            }
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission(String permission, int requestCode) {
        ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
    }

    private void initializeFirebase() {
        if (Config.ENABLE_FIREBASE_PUSH) {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            fcmToken = task.getResult();
                            Log.d(TAG, "FCM Token: " + fcmToken);
                        }
                    });
        }
    }

    private static final long NETWORK_CHECK_INTERVAL = 3000; // 3 secondi
    private static final int MAX_OFFLINE_RETRY_ATTEMPTS = 3;
    private int offlineRetryCount = 0;

    private void checkNetworkAndLoad() {
        // If using local files, skip network check entirely
        if ("local".equalsIgnoreCase(Config.START_URL_TYPE)) {
            Log.d(TAG, "START_URL_TYPE is 'local' - skipping network check");
            loadMainUrl();
            // Don't start network monitoring for local mode
            return;
        }

        // For internet mode, check network availability FIRST
        if (isNetworkAvailable()) {
            Log.d(TAG, "Network available - loading main URL");
            loadMainUrl();
            startNetworkMonitoring();
        } else {
            Log.w(TAG, "Network NOT available - showing offline mode");
            showOfflineMode();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    private void loadMainUrl() {
        String url;

        // Determine which URL to load based on START_URL_TYPE config
        if ("local".equalsIgnoreCase(Config.START_URL_TYPE)) {
            url = Config.LOCAL_START_URL;
            Log.d(TAG, "Loading local startup URL: " + url);
        } else {
            url = Config.HOME_URL;
            Log.d(TAG, "Loading internet startup URL: " + url);
        }

        // Add FCM token if enabled (only for internet URLs)
        if (!"local".equalsIgnoreCase(Config.START_URL_TYPE) &&
                Config.ENABLE_FIREBASE_PUSH &&
                Config.ENHANCE_URL_WITH_FCM_TOKEN &&
                !fcmToken.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "fcmtoken=" + fcmToken;
        }

        // Add theme parameter if enabled (only for internet URLs)
        if (!"local".equalsIgnoreCase(Config.START_URL_TYPE) &&
                Config.ENHANCE_URL_WITH_THEME) {
            String theme = getCurrentTheme();
            url += (url.contains("?") ? "&" : "?") + "apptheme=" + theme;
            Log.d(TAG, "Adding theme parameter: " + theme);
        }

        mainWebView.loadUrl(url);
        webViewContainer.setVisibility(View.VISIBLE);
        offlineView.setVisibility(View.GONE);
        isOffline = false;
    }

    private void showOfflineMode() {
        if (!Config.ENABLE_OFFLINE_MODE) {
            return;
        }

        if (!isNetworkAvailable()) {
            // Network is actually down - show offline view
            Log.d(TAG, "Showing offline mode - loading: " + Config.OFFLINE_HTML_PATH);
            offlineView.setVisibility(View.VISIBLE);
            webViewContainer.setVisibility(View.GONE);
            isOffline = true;

            // Load offline HTML file
            if (mainWebView != null) {
                mainWebView.loadUrl(Config.OFFLINE_HTML_PATH);
            }
        }
    }

    private void startNetworkMonitoring() {
        // Stop existing monitoring if any
        if (networkCheckHandler != null && networkCheckRunnable != null) {
            networkCheckHandler.removeCallbacks(networkCheckRunnable);
        }

        networkCheckHandler = new Handler(Looper.getMainLooper());
        networkCheckRunnable = new Runnable() {
            private int consecutiveOfflineChecks = 0;
            // if STRICT_OFFLINE_MODE is enabled, check for consecutive failures
            private final int OFFLINE_THRESHOLD = Config.STRICT_OFFLINE_MODE ? 3 : 1;

            @Override
            public void run() {
                boolean networkNowAvailable = isNetworkAvailable();
                if (!networkNowAvailable) {
                    consecutiveOfflineChecks++;
                    Log.d(TAG, "Network check: DOWN (" + consecutiveOfflineChecks + "/" + OFFLINE_THRESHOLD + ")");

                    // Only go offline after multiple consecutive failures (if STRICT mode)
                    if (consecutiveOfflineChecks >= OFFLINE_THRESHOLD && !isOffline) {
                        Log.w(TAG, "Network consistently DOWN - showing offline view");
                        showOfflineMode();
                    }
                } else {
                    consecutiveOfflineChecks = 0; // Reset counter on success
                    if (isOffline && Config.STOP_APP_IN_BACKGROUND) {
                        // Network came back and STOP_APP_IN_BACKGROUND is enabled - reload
                        Log.d(TAG, "Network came back UP - reloading because STOP_APP_IN_BACKGROUND is true");
                        isOffline = false;
                        offlineView.setVisibility(View.GONE);
                        webViewContainer.setVisibility(View.VISIBLE);
                        loadMainUrl();
                    } else if (isOffline) {
                        // Network came back but STOP_APP_IN_BACKGROUND is false - just hide offline view
                        Log.d(TAG, "Network came back UP - hiding offline view");
                        isOffline = false;
                        offlineView.setVisibility(View.GONE);
                        webViewContainer.setVisibility(View.VISIBLE);
                    }
                }

                // Continue monitoring - usa Config.NETWORK_MONITOR_INTERVAL
                networkCheckHandler.postDelayed(this, Config.NETWORK_MONITOR_INTERVAL);
            }
        };
        networkCheckHandler.post(networkCheckRunnable);
        Log.d(TAG, "Network monitoring restarted");
    }

    // WebView Client
    private class MyWebViewClient extends WebViewClient {
        private final boolean isMainWebView;

        public MyWebViewClient(boolean isMain) {
            this.isMainWebView = isMain;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (url.startsWith("file://") || url.startsWith("data:") || url.startsWith("about:")) {
                return;
            }

            // Show progress bar if enabled
            if (Config.SHOW_PROGRESS_BAR) {
                if (isMainWebView) {
                    if (mainProgressBarManager != null) {
                        Log.d(TAG, "Page load started - showing main progress bar: " + url);
                        mainProgressBarManager.show();
                    }
                } else {
                    if (secondaryProgressBarManager != null) {
                        Log.d(TAG, "Page load started - showing secondary progress bar: " + url);
                        secondaryProgressBarManager.show();
                    }
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            // Force flush after page loads to ensure session and banner cookies are saved immediately
            CookieManager.getInstance().flush();

            if (isMainWebView) {
                // Mark WebView as loaded - this will trigger splash screen dismissal if waiting
                isWebViewLoaded = true;
                Log.d(TAG, "Main page loaded - isWebViewLoaded set to true (splash screen will dismiss if configured to wait)");

                // Stop the pull-to-refresh spinner for main WebView
                if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                    Log.d(TAG, "Main page loaded - stopping refresh spinner");
                    swipeRefreshLayout.setRefreshing(false);
                    swipeRefreshLayout.setEnabled(false);
                }

                // Hide main progress bar if enabled
                if (Config.SHOW_PROGRESS_BAR && mainProgressBarManager != null) {
                    Log.d(TAG, "Page load finished - hiding main progress bar: " + url);
                    mainProgressBarManager.hide();
                }

            } else {
                // Update secondary URL display
                updateSecondaryUrlDisplay(url);
                // Update SSL certificate icon
                SslCertificate sslCert = view.getCertificate();
                updateSSLCertificateIcon(url, sslCert);
                // For secondary WebView - same logic but for secondary widgets
                if (Config.ENABLEPULLTOREFRESHSECONDARY && secondarySwipeRefreshLayout != null && secondarySwipeRefreshLayout.isRefreshing()) {
                    Log.d(TAG, "Secondary page loaded - stopping refresh spinner");
                    secondarySwipeRefreshLayout.setRefreshing(false);
                    secondarySwipeRefreshLayout.setEnabled(false);
                }

                // Hide secondary progress bar if enabled
                if (Config.SHOW_PROGRESS_BAR && secondaryProgressBarManager != null) {
                    Log.d(TAG, "Page load finished - hiding secondary progress bar: " + url);
                    secondaryProgressBarManager.hide();
                }
            }
        }

        /**
         * Handle SSL errors - called when certificate validation fails
         * Show warning to user but allow them to proceed if they wish
         */
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            if (!isMainWebView) {
                // Secondary WebView - show warning dialog
                Log.w(TAG, "SSL Error on secondary WebView: " + error.toString());

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(getString(R.string.ssl_error_title))
                        .setMessage(getString(R.string.ssl_error_message) + "\n\n" +
                                "Error: " + error.toString() +
                                "\n\n" + getString(R.string.ssl_error_question))
                        .setPositiveButton(getString(R.string.http_warning_continue), (dialog, which) -> {
                            // User chose to proceed - note this is a security risk
                            handler.proceed();
                            SslCertificate cert = error.getCertificate();
                            updateSSLCertificateIcon(view.getUrl(), cert);
                        })
                        .setNegativeButton(getString(R.string.http_warning_cancel), (dialog, which) -> {
                            // Cancel the request
                            handler.cancel();
                            updateSSLCertificateIcon(view.getUrl(), null);
                        })
                        .show();
            } else {
                // Main WebView - cancel by default for security
                handler.cancel();
            }
        }

        private boolean isCustomScheme(String url) {
            for (String scheme : Config.CUSTOM_SCHEMES) {
                if (url.startsWith(scheme)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            String url = request.getUrl().toString();

            Log.d(TAG, "onReceivedError - URL: " + url + " | Error: " + error.getErrorCode() +
                    " | isMainFrame: " + request.isForMainFrame());

            // Ignore errors from custom schemes
            if (isCustomScheme(url)) {
                Log.d(TAG, "Ignoring expected custom scheme error");
                return;
            }

            // Ignore errors from media files (video, audio)
            if (url.endsWith(".mp4") || url.endsWith(".webm") || url.endsWith(".mkv") ||
                    url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".wav")) {
                Log.d(TAG, "Ignoring media resource error: " + url);
                return;
            }

            // Ignore errors from images and other resources
            if (url.endsWith(".jpg") || url.endsWith(".jpeg") || url.endsWith(".png") ||
                    url.endsWith(".gif") || url.endsWith(".svg") || url.endsWith(".webp")) {
                Log.d(TAG, "Ignoring image resource error: " + url);
                return;
            }

            // Ignore HTTPS-only errors for file:// URLs
            if (url.startsWith("file://")) {
                Log.d(TAG, "Ignoring error for local file: " + url);
                return;
            }

            // === ONLY trigger offline for MAIN FRAME navigation errors AND if network is actually down ===
            if (Config.ENABLE_OFFLINE_MODE && isMainWebView && request.isForMainFrame()) {
                // Check if network is actually down (not just a temporary error)
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "Main frame error AND network is down - showing offline");
                    showOfflineMode();
                } else {
                    // Network is available but page load failed - this might be temporary
                    Log.d(TAG, "Main frame error but network is available - NOT going offline");
                }
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();

            Log.d(TAG, "shouldOverrideUrlLoading - URL: " + url + ", Method: " + request.getMethod());

            // First: Handle special URLs (they bypass all restrictions)
            if (handleSpecialUrl(url)) {
                return true;
            }

            // Second: Check LOAD_ONLY_HTTPS restriction
            if (Config.LOAD_ONLY_HTTPS) {
                if (!url.startsWith("https://") &&
                        !url.startsWith("file://") &&
                        !isCustomScheme(url)) {
                    // HTTP URL detected but HTTPS only is required
                    Log.w(TAG, "SECURITY: HTTP URL blocked - LOAD_ONLY_HTTPS is enabled: " + url);
                    Toast.makeText(MainActivity.this, "Only HTTPS connections are allowed", Toast.LENGTH_SHORT).show();
                    return true; // Block the navigation
                }
            }

            // Third: Check if it's an external domain (main WebView only)
            if (isMainWebView && isExternalDomain(url)) {
                Log.d(TAG, "shouldOverrideUrlLoading - External domain detected");

                // if POST not intercept here
                if ("POST".equalsIgnoreCase(request.getMethod())) {
                    Log.d(TAG, "POST request detected - NOT blocking");
                    return false;
                }

                // Solo GET e altri metodi
                openInSecondaryWebView(url);
                return true;
            }

            return false;
        }

    }

    private boolean handleSpecialUrl(String url) {
        Log.d(TAG, "Checking special URL: " + url);

        // verify if special url
        if (!isSpecialUrl(url)) {
            return false;
        }

        // verify if allowed domain
        if (!isSpecialUrlFromAllowedDomain()) {
            Log.w(TAG, "SECURITY: Special URL blocked - not from allowed domain");
            Log.w(TAG, "SECURITY: Attempted URL: " + url);
            Log.w(TAG, "SECURITY: Current page: " + (mainWebView != null ? mainWebView.getUrl() : "null"));
            Toast.makeText(this, "Action not allowed from this page", Toast.LENGTH_SHORT).show();
            return true; // Block the action
        }

        Log.d(TAG, "Special URL authorized - executing action");

        // Close app
        if (url.startsWith(Config.URL_CLOSE_APP)) {
            Log.d(TAG, "Closing app");
            finish();
            System.exit(0);
            return true;
        }

        // Open QR scanner
        if (url.startsWith(Config.URL_OPEN_QR_SCANNER)) {
            Log.d(TAG, "Opening QR scanner");
            openQRScanner();
            return true; // Block navigation
        }

        // Open app settings
        if (url.startsWith(Config.URL_OPEN_APP_SETTINGS)) {
            Log.d(TAG, "Opening app settings");
            openAppSettings();
            return true; // Block navigation
        }

        // Picture in Picture
        if (url.startsWith(Config.URL_PICTURE_IN_PICTURE)) {
            Log.d(TAG, "Starting Picture in Picture");
            startPictureInPictureMode();
            return true; // Block navigation
        }

        // Share content
        if (url.startsWith(Config.URL_SHARE_CONTENT)) {
            Log.d(TAG, "Sharing content with parameters");
            handleShareUrl(url);
            return true; // Block navigation
        }

        // Toggle Do Not Disturb mode
        if (url.startsWith(Config.URL_TOGGLE_NOTIFICATIONS)) {
            Log.d(TAG, "Toggling Do Not Disturb mode");
            toggleDndMode();
            return true; // Block navigation
        }

        Log.d(TAG, "Not a special URL");
        return false;
    }

    private boolean isSpecialUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        return url.startsWith(Config.URL_CLOSE_APP) ||
                url.startsWith(Config.URL_OPEN_QR_SCANNER) ||
                url.startsWith(Config.URL_OPEN_APP_SETTINGS) ||
                url.startsWith(Config.URL_PICTURE_IN_PICTURE) ||
                url.startsWith(Config.URL_SHARE_CONTENT) ||
                url.startsWith(Config.URL_TOGGLE_NOTIFICATIONS);
    }

    private boolean isSpecialUrlFromAllowedDomain() {
        if (mainWebView == null) {
            Log.d(TAG, "mainWebView is null - rejecting special URL");
            return false;
        }

        String currentUrl = mainWebView.getUrl();
        if (currentUrl == null || currentUrl.isEmpty()) {
            Log.d(TAG, "No current URL in mainWebView - rejecting special URL");
            return false;
        }

        // Extract host from URL
        Uri uri = Uri.parse(currentUrl);
        String host = uri.getHost();

        if (host == null) {
            Log.d(TAG, "Unable to extract host from URL - rejecting special URL");
            return false;
        }

        // normalize the domain name
        host = host.toLowerCase().trim();
        Log.d(TAG, "Checking special URL from host: " + host);

        // verify if special url from allowed domain
        for (String allowedDomain : Config.SPECIAL_URL_ALLOWED_DOMAINS) {
            String domain = allowedDomain.toLowerCase().trim();

            // verify subdomain exactly
            if (host.equals(domain) || host.endsWith("." + domain)) {
                Log.d(TAG, "✓ Special URL allowed from domain: " + domain);
                return true;
            }
        }

        Log.w(TAG, "✗ Special URL blocked - host not in whitelist: " + host);
        return false;
    }

    /**
     * Handles share URL with parameters
     * Format: shareapp://sharetext/TITLE/&url=URL
     * or: shareapp://sharetext=TEXT&url=URL (also supported for compatibility)
     */
    private void handleShareUrl(String url) {
        try {
            Log.d(TAG, "=== SHARE URL DEBUG START ===");
            Log.d(TAG, "Full share URL: " + url);

            // Remove the scheme prefix
            String urlWithoutScheme = url.replace(Config.URL_SHARE_CONTENT, "");
            Log.d(TAG, "URL without scheme: " + urlWithoutScheme);

            String shareText = null;
            String shareUrl = null;

            // Check if it starts with "sharetext" (path format)
            if (urlWithoutScheme.startsWith("sharetext")) {
                // Format: shareapp://sharetext/TITLE/&url=URL
                // or: shareapp://sharetextTITLE&url=URL

                String remaining = urlWithoutScheme.substring("sharetext".length());
                Log.d(TAG, "Remaining after 'sharetext': " + remaining);

                // Split by "&url=" to separate text from URL
                if (remaining.contains("&url=")) {
                    String[] parts = remaining.split("&url=", 2);

                    // Extract shareText (remove leading/trailing slashes)
                    String textPart = parts[0];
                    if (textPart.startsWith("/")) {
                        textPart = textPart.substring(1);
                    }
                    if (textPart.endsWith("/")) {
                        textPart = textPart.substring(0, textPart.length() - 1);
                    }

                    // Decode URL encoding
                    shareText = Uri.decode(textPart);
                    Log.d(TAG, "Extracted shareText: " + shareText);

                    // Extract URL
                    if (parts.length > 1) {
                        shareUrl = Uri.decode(parts[1]);
                        Log.d(TAG, "Extracted shareUrl: " + shareUrl);
                    }
                } else {
                    // No URL parameter, just text
                    String textPart = remaining;
                    if (textPart.startsWith("/")) {
                        textPart = textPart.substring(1);
                    }
                    if (textPart.endsWith("/")) {
                        textPart = textPart.substring(0, textPart.length() - 1);
                    }
                    shareText = Uri.decode(textPart);
                    Log.d(TAG, "Extracted shareText (no URL): " + shareText);
                }
            } else {
                // Fallback: Standard query parameter format
                // Format: shareapp://?sharetext=TEXT&url=URL
                String queryString = urlWithoutScheme;

                if (queryString.startsWith("?")) {
                    queryString = queryString.substring(1);
                }

                Log.d(TAG, "Query string format: " + queryString);

                if (!queryString.isEmpty()) {
                    String[] params = queryString.split("&");
                    for (String param : params) {
                        String[] keyValue = param.split("=", 2);
                        if (keyValue.length == 2) {
                            String key = keyValue[0].trim();
                            String value = Uri.decode(keyValue[1]);

                            if ("sharetext".equalsIgnoreCase(key)) {
                                shareText = value;
                            } else if ("url".equalsIgnoreCase(key)) {
                                shareUrl = value;
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Final shareText: " + shareText);
            Log.d(TAG, "Final shareUrl: " + shareUrl);

            // Build the content to share
            StringBuilder content = new StringBuilder();

            if (shareText != null && !shareText.isEmpty()) {
                content.append(shareText);
            }

            if (shareUrl != null && !shareUrl.isEmpty()) {
                if (content.length() > 0) {
                    content.append("\n\n");
                }
                content.append(shareUrl);
            }

            // If no content, use default message
            if (content.length() == 0) {
                content.append("Check out this content from MWA!");
                Log.d(TAG, "No content found, using default message");
            }

            Log.d(TAG, "Final content to share:\n" + content.toString());
            Log.d(TAG, "=== SHARE URL DEBUG END ===");

            // Share the content
            shareContent(content.toString());

        } catch (Exception e) {
            Log.e(TAG, "Error parsing share URL: " + e.getMessage(), e);
            Toast.makeText(this, "Error sharing content", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isExternalDomain(String url) {
        Log.d(TAG, "=== isExternalDomain START ===");
        Log.d(TAG, "Input URL: " + url);

        // Extract hostname from URL
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        Log.d(TAG, "uri.getHost() returned: " + host);

        // If getHost() returns null, try to extract from URL string
        if (host == null || host.isEmpty()) {
            try {
                // Remove protocol
                String urlWithoutProtocol = url.replaceAll("^https?://", "").replaceAll("^//", "");
                Log.d(TAG, "URL without protocol: " + urlWithoutProtocol);

                // Extract domain (everything before first /, ?, :, or #)
                host = urlWithoutProtocol.split("[/?:#]")[0];
                Log.d(TAG, "Extracted host from string: " + host);
            } catch (Exception e) {
                Log.e(TAG, "Error extracting host: " + e.getMessage());
                return true; // Treat as external if we can't parse
            }
        }

        host = host.toLowerCase().trim();
        Log.d(TAG, "Final host (lowercase): '" + host + "'");

        // Check if in SECONDARY_WEBVIEW_DOMAINS
        for (String domain : Config.SECONDARY_WEBVIEW_DOMAINS) {
            domain = domain.toLowerCase().trim();
            Log.d(TAG, "  Comparing '" + host + "' with SECONDARY domain '" + domain + "'");
            if (host.equals(domain) || host.endsWith("." + domain)) {
                Log.d(TAG, "MATCH! Found in SECONDARY_WEBVIEW_DOMAINS: " + domain);
                Log.d(TAG, "=== isExternalDomain END (returning TRUE) ===");
                return true;
            }
        }

        // Check if in ALLOWED_DOMAINS
        for (String domain : Config.ALLOWED_DOMAINS) {
            domain = domain.toLowerCase().trim();
            Log.d(TAG, "  Comparing '" + host + "' with ALLOWED domain '" + domain + "'");
            if (host.equals(domain) || host.endsWith("." + domain)) {
                Log.d(TAG, "MATCH! Found in ALLOWED_DOMAINS (internal): " + domain);
                Log.d(TAG, "=== isExternalDomain END (returning FALSE) ===");
                return false;
            }
        }

        Log.d(TAG, "NOT FOUND in any list, treating as external");
        Log.d(TAG, "=== isExternalDomain END (returning TRUE) ===");
        return true;
    }

    /**
     * Open URL in secondary WebView browser
     * Checks for HTTP URLs first if warning is enabled in config
     * Sets up scroll listener for pull-to-refresh functionality
     */
    private void openInSecondaryWebView(String url) {
        FrameLayout secondaryContainer = findViewById(R.id.secondary_container);

        // Check if URL is HTTP and user wants to proceed
        checkHTTPSWarningAsync(url, proceed -> {
            if (!proceed) {
                Log.d(TAG, "User cancelled HTTP URL loading");
                // IMPORTANT: Close the secondary WebView if user cancels HTTP warning
                if (secondaryContainer.getVisibility() == View.VISIBLE) {
                    closeSecondaryWebView();
                }
                return; // User cancelled - don't load the URL
            }

            // Only proceed if user confirmed
            if (secondaryWebView == null) {
                secondaryWebView = new CustomWebView(MainActivity.this);
                setupWebView(secondaryWebView, false);
                FrameLayout secondaryWebViewContainer = findViewById(R.id.secondary_webview_container);
                secondaryWebViewContainer.addView(secondaryWebView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));

                // Setup scroll listener for secondary WebView
                secondaryWebView.setOnScrollChangeListener((scrollY, isAtTop) -> {
                    if (secondarySwipeRefreshLayout != null && Config.ENABLEPULLTOREFRESHSECONDARY) {
                        secondarySwipeRefreshLayout.setEnabled(isAtTop);
                        Log.d(TAG, "Secondary WebView scroll - ScrollY: " + scrollY + " | Refresh enabled: " + isAtTop);
                    }
                });
            }

            // Show container and load URL
            secondaryContainer.setVisibility(View.VISIBLE);

            // Update URL display
            updateSecondaryUrlDisplay(url);
            Log.d(TAG, "Loading in secondary: " + url);
            secondaryWebView.loadUrl(url);
        });
    }

    private void closeSecondaryWebView() {
        Log.d(TAG, "Closing secondary WebView");

        try {
            if (secondaryWebView != null) {
                FrameLayout secondaryWebViewContainer = findViewById(R.id.secondary_webview_container);

                // Stop any ongoing refresh if secondary pull-to-refresh is enabled
                if (Config.ENABLEPULLTOREFRESHSECONDARY && secondarySwipeRefreshLayout != null) {
                    if (secondarySwipeRefreshLayout.isRefreshing()) {
                        Log.d(TAG, "Closing secondary WebView - stopping refresh spinner");
                        secondarySwipeRefreshLayout.setRefreshing(false);
                    }
                }

                // Remove from container
                if (secondaryWebViewContainer != null) {
                    secondaryWebViewContainer.removeView(secondaryWebView);
                }

                // Destroy WebView
                secondaryWebView.destroy();
                secondaryWebView = null;

                // Hide container
                FrameLayout secondaryContainer = findViewById(R.id.secondary_container);
                if (secondaryContainer != null) {
                    secondaryContainer.setVisibility(View.GONE);
                }

                Log.d(TAG, "Secondary WebView closed successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing secondary WebView: " + e.getMessage());
            // Force cleanup even if error occurs
            secondaryWebView = null;
            FrameLayout secondaryContainer = findViewById(R.id.secondary_container);
            if (secondaryContainer != null) {
                secondaryContainer.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Update the current URL displayed in secondary WebView header
     * The URL is truncated automatically by TextView with ellipsize="end"
     * This is informational only - not editable
     *
     * @param url The URL to display
     */
    private void updateSecondaryUrlDisplay(String url) {
        if (secondaryUrlDisplay == null) {
            Log.w(TAG, "secondaryUrlDisplay is null");
            return;
        }

        try {
            if (url == null || url.isEmpty() || url.equals("about:blank")) {
                secondaryUrlDisplay.setText("about:blank");
                return;
            }

            // Remove protocol (https:// or http://) for cleaner display
            String displayUrl = url;
            if (url.startsWith("https://")) {
                displayUrl = url.substring(8);
            } else if (url.startsWith("http://")) {
                displayUrl = url.substring(7);
            }

            // Set text - ellipsize="end" in XML will automatically add "..." if too long
            secondaryUrlDisplay.setText(displayUrl);
            Log.d(TAG, "Secondary URL display updated: " + displayUrl);

        } catch (Exception e) {
            Log.e(TAG, "Error updating secondary URL display: " + e.getMessage());
            secondaryUrlDisplay.setText("URL");
        }
    }

    private void openQRScanner() {
        Log.d(TAG, "openQRScanner called");

        if (!Config.ENABLE_QR_SCANNER) {
            Log.d(TAG, "QR Scanner disabled in config");
            return;
        }

        // Verify camera permission
        if (hasPermission(Manifest.permission.CAMERA)) {
            Log.d(TAG, "Camera permission not granted, requesting...");
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            requestPermission(Manifest.permission.CAMERA, PERMISSION_REQUEST_CODE);
            return;
        }

        Log.d(TAG, "Camera permission granted, launching scanner");

        try {
            // Configure the scanner
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Scan a QR code");
            options.setCameraId(0);
            options.setBeepEnabled(true);
            options.setBarcodeImageEnabled(false);
            options.setOrientationLocked(false);

            // Launch the scanner
            qrScannerLauncher.launch(options);
            Log.d(TAG, "QR Scanner launched");

        } catch (Exception e) {
            Log.e(TAG, "QR Scanner error: " + e.getMessage(), e);
            Toast.makeText(this, "Error opening scanner: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        Log.d(TAG, "onRequestPermissionsResult - requestCode: " + requestCode);

        boolean isGranted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        switch (requestCode) {
            case 100: // Camera permission
                Log.d(TAG, "Camera permission: " + (isGranted ? "GRANTED" : "DENIED"));
                if (pendingPermissionRequest != null) {
                    if (isGranted) {
                        pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    } else {
                        pendingPermissionRequest.deny();
                    }
                    pendingPermissionRequest = null;
                }
                break;

            case 101: // Audio permission
                Log.d(TAG, "Audio permission: " + (isGranted ? "GRANTED" : "DENIED"));
                if (pendingPermissionRequest != null) {
                    if (isGranted) {
                        pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    } else {
                        pendingPermissionRequest.deny();
                    }
                    pendingPermissionRequest = null;
                }
                break;

            case 102: // Geolocation permission
                Log.d(TAG, "Geolocation permission: " + (isGranted ? "GRANTED" : "DENIED"));
                if (geolocationCallback != null) {
                    geolocationCallback.invoke(geolocationOrigin, isGranted, false);
                    geolocationCallback = null;
                }
                break;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d(TAG, "onActivityResult - requestCode: " + requestCode + ", resultCode: " + resultCode);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && mFilePathCallback != null) {
                Uri[] results = null;
                if (data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                    Log.d(TAG, "File selected: " + data.getData().toString());
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
                Log.d(TAG, "File chooser callback executed");
            } else if (mFilePathCallback != null) {
                Log.d(TAG, "File chooser cancelled or no file selected");
                mFilePathCallback.onReceiveValue(null);
                mFilePathCallback = null;
            }
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void startPictureInPictureMode() {
        Log.d(TAG, "startPictureInPictureMode called");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.app.PictureInPictureParams params =
                        new android.app.PictureInPictureParams.Builder().build();

                boolean success = enterPictureInPictureMode(params);
                Log.d(TAG, "PiP success: " + success);

                if (!success) {
                    Toast.makeText(this, "Picture in Picture not supported", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "PiP error: " + e.getMessage());
                Toast.makeText(this, "PiP error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Picture in Picture requires Android 8.0+", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        Log.d(TAG, "PiP mode changed: " + isInPictureInPictureMode);

        if (isInPictureInPictureMode) {
            // Entering PiP mode
            Log.d(TAG, "Entered PiP mode - video continues playing");

            // Hide secondary WebView if visible
            if (secondaryWebViewContainer != null) {
                secondaryWebViewContainer.setVisibility(View.GONE);
            }

            // Make sure WebView is not paused
            if (mainWebView != null) {
                mainWebView.onResume();
                mainWebView.resumeTimers();
            }
        } else {
            // Exiting PiP mode
            Log.d(TAG, "Exited PiP mode");

            // Resume WebView if needed
            if (mainWebView != null) {
                mainWebView.onResume();
                mainWebView.resumeTimers();
            }
        }
    }

    private void shareContent(String content) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)));
    }

    /**
     * Initialize progress bar managers for both main and secondary WebView
     * Creates appropriate progress bar style based on Config settings
     */
    private void initializeProgressBars() {
        if (!Config.SHOW_PROGRESS_BAR) {
            Log.d(TAG, "Progress bar disabled in configuration");
            return;
        }

        // Determine progress bar style from config
        ProgressBarManager.ProgressBarStyle style;
        switch (Config.PROGRESS_BAR_STYLE.toLowerCase()) {
            case "spinner_center":
                style = ProgressBarManager.ProgressBarStyle.SPINNER_CENTER;
                break;
            case "spinner_corner":
                style = ProgressBarManager.ProgressBarStyle.SPINNER_CORNER;
                break;
            case "linear_progress":
                style = ProgressBarManager.ProgressBarStyle.LINEAR_PROGRESS;
                break;
            default:
                Log.w(TAG, "Unknown progress bar style: " + Config.PROGRESS_BAR_STYLE + ", using spinner_corner");
                style = ProgressBarManager.ProgressBarStyle.SPINNER_CORNER;
                break;
        }

        // Initialize progress bar for main WebView
        mainProgressBarManager = new ProgressBarManager(
                this,
                webViewContainer,
                style,
                Config.PROGRESS_BAR_COLOR
        );

        // Initialize progress bar for secondary WebView
        secondaryProgressBarManager = new ProgressBarManager(
                this,
                secondaryWebViewContainer,
                style,
                Config.PROGRESS_BAR_COLOR
        );

        Log.d(TAG, "Progress bar managers initialized - Style: " + style.name());
    }

    /**
     * Initialize swipe gesture detection for navigation
     * Allows user to swipe right to go back in WebView history
     */
    private void initializeSwipeNavigation() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                try {
                    float diffY = e2.getY() - e1.getY();
                    float diffX = e2.getX() - e1.getX();

                    // Check if horizontal swipe is dominant
                    if (Math.abs(diffX) > Math.abs(diffY)) {
                        // Check if swipe is significant enough
                        if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                            // Swipe right (back gesture)
                            if (diffX > 0) {
                                Log.d(TAG, "Swipe right detected - navigating back");

                                // Check which WebView is visible
                                if (secondaryWebViewContainer.getVisibility() == View.VISIBLE) {
                                    // Secondary WebView is visible
                                    if (secondaryWebView != null && secondaryWebView.canGoBack()) {
                                        secondaryWebView.goBack();
                                    } else {
                                        closeSecondaryWebView();
                                    }
                                    return true;
                                } else {
                                    // Main WebView is visible
                                    if (mainWebView != null && mainWebView.canGoBack()) {
                                        mainWebView.goBack();
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in swipe gesture: " + e.getMessage());
                }
                return false;
            }
        });

        Log.d(TAG, "Swipe navigation initialized");
    }

    /**
     * Initialize back button handler using AndroidX OnBackPressedDispatcher
     * Respects Config options for EXIT_ON_BACK_PRESSED_HOME and SHOW_EXIT_DIALOG
     */
    private void initializeBackButtonHandler() {
        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Check secondary WebView first
                if (secondaryWebViewContainer != null &&
                        secondaryWebViewContainer.getVisibility() == View.VISIBLE) {

                    if (secondaryWebView != null && secondaryWebView.canGoBack()) {
                        // Go back in secondary WebView history
                        Log.d(TAG, "Back pressed - navigating back in secondary WebView");
                        secondaryWebView.goBack();
                        return;
                    } else {
                        // Close secondary WebView
                        Log.d(TAG, "Back pressed - closing secondary WebView");
                        closeSecondaryWebView();
                        return;
                    }
                }

                // Handle main WebView back navigation
                if (mainWebView != null && mainWebView.canGoBack()) {
                    // Check if we should exit on back press instead of navigating back
                    if (Config.EXIT_ON_BACK_PRESSED_HOME) {
                        // Exit the app on back press
                        Log.d(TAG, "Back pressed - EXIT_ON_BACK_PRESSED_HOME is true - showing exit");
                        handleAppExit();
                        return;
                    } else {
                        // Navigate back in WebView history
                        Log.d(TAG, "Back pressed - EXIT_ON_BACK_PRESSED_HOME is false - navigating back");
                        mainWebView.goBack();
                        return;
                    }
                }

                // If we can't go back in WebView, try to exit
                Log.d(TAG, "Back pressed - cannot go back in WebView - showing exit");
                handleAppExit();
            }
        };

        // Register the back press callback with the dispatcher
        getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
        Log.d(TAG, "Back button handler initialized - EXIT_ON_BACK_PRESSED_HOME=" + Config.EXIT_ON_BACK_PRESSED_HOME);
    }

    /**
     * Handle app exit with or without dialog based on Config
     * Respects Config.SHOW_EXIT_DIALOG setting
     */
    private void handleAppExit() {
        if (Config.SHOW_EXIT_DIALOG) {
            Log.d(TAG, "Showing exit dialog - SHOW_EXIT_DIALOG is true");
            showExitDialog();
        } else {
            Log.d(TAG, "Exiting app directly - SHOW_EXIT_DIALOG is false");
            finish();
        }
    }

    /**
     * Show exit confirmation dialog when user presses back or wants to exit
     * Only shown if Config.SHOW_EXIT_DIALOG is true
     */
    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_app)
                .setMessage(R.string.exit_app_message)
                .setCancelable(false)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    Log.d(TAG, "User confirmed exit");
                    finish();
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    Log.d(TAG, "User cancelled exit");
                    dialog.dismiss();
                })
                .show();
    }

    // WebChrome Client
    private class MyWebChromeClient extends WebChromeClient {
        private View customView;
        private WebChromeClient.CustomViewCallback customViewCallback;
        private int originalSystemUiVisibility;

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            Log.d(TAG, "onCreateWindow called - isDialog: " + isDialog + ", isUserGesture: " + isUserGesture);

            FrameLayout secondaryContainer = findViewById(R.id.secondary_container);
            secondaryContainer.setVisibility(View.VISIBLE);

            if (secondaryWebView == null) {
                Log.d(TAG, "Creating new secondary WebView");
                // Create CustomWebView for scroll tracking
                secondaryWebView = new CustomWebView(MainActivity.this);
                setupWebView(secondaryWebView, false);

                // WebView client per il secondary - NON intercettare gli URL
                secondaryWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        // Nel secondary, carica tutto normalmente
                        return false;
                    }
                });

                secondaryWebView.setWebChromeClient(new WebChromeClient());

                FrameLayout secondaryWebViewContainer = findViewById(R.id.secondary_webview_container);
                secondaryWebViewContainer.addView(secondaryWebView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));

                // Setup scroll listener for secondary WebView to control refresh
                // This enables/disables the refresh based on scroll position
                secondaryWebView.setOnScrollChangeListener((scrollY, isAtTop) -> {
                    if (secondarySwipeRefreshLayout != null && Config.ENABLEPULLTOREFRESHSECONDARY) {
                        // Enable refresh only when at top of page
                        secondarySwipeRefreshLayout.setEnabled(isAtTop);
                        Log.d(TAG, "Secondary WebView scroll (onCreateWindow) - ScrollY: " + scrollY + " | Refresh enabled: " + isAtTop);
                    }
                });
            }

            // Collega il WebView al transport
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(secondaryWebView);
            resultMsg.sendToTarget();

            return true;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (Config.SHOW_PROGRESS_BAR) {
                // Update progress bar
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                onHideCustomView();
                return;
            }

            customView = view;
            originalSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            customViewCallback = callback;

            FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
            decorView.addView(customView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        @Override
        public void onHideCustomView() {
            FrameLayout decorView = (FrameLayout) getWindow().getDecorView();
            decorView.removeView(customView);
            customView = null;

            getWindow().getDecorView().setSystemUiVisibility(originalSystemUiVisibility);

            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            Log.d(TAG, "Console: " + consoleMessage.message() +
                    " -- From line " + consoleMessage.lineNumber() +
                    " of " + consoleMessage.sourceId());
            return true;
        }

        // ============================================
        // PERMISSION HANDLING FOR HTML5 APIs
        // ============================================

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            Log.d(TAG, "onPermissionRequest called");

            String[] resources = request.getResources();

            for (String resource : resources) {
                if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    // Handle camera
                    Log.d(TAG, "Camera permission requested");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (checkSelfPermission(Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {
                            // Store request for later callback
                            pendingPermissionRequest = request;
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, 100);
                            return;
                        }
                    }
                    request.grant(resources);
                    return;
                }
                else if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    // Handle microphone
                    Log.d(TAG, "Microphone permission requested");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            // Store request for later callback
                            pendingPermissionRequest = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 101);
                            return;
                        }
                    }
                    request.grant(resources);
                    return;
                }
            }

            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            Log.d(TAG, "Geolocation permission requested from: " + origin);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    // Store callback and origin for later use
                    geolocationCallback = callback;
                    geolocationOrigin = origin;
                    requestPermissions(
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            102
                    );
                } else {
                    callback.invoke(origin, true, false);
                }
            } else {
                callback.invoke(origin, true, false);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            Log.d(TAG, "File chooser requested");

            // Store callback for later use
            mFilePathCallback = filePathCallback;

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            startActivityForResult(
                    Intent.createChooser(intent, "Select File"),
                    FILE_CHOOSER_REQUEST_CODE
            );

            return true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Pass touch events to gesture detector if swipe navigation is enabled
        if (Config.ENABLE_SWIPE_NAVIGATION && gestureDetector != null) {
            if (gestureDetector.onTouchEvent(event)) {
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        Uri data = intent.getData();

        // Handle deep links
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            String url = data.toString();
            Log.d(TAG, "Deep link URL: " + url);

            // filter out custom scheme URLs
            if (url.startsWith("http://") || url.startsWith("https://")) {
                mainWebView.loadUrl(url);
            } else {
                Log.d(TAG, "Ignoring custom scheme URL in deep link: " + url);
            }
        }

        // Handle notification clicks
        if (intent.hasExtra("notification_url")) {
            String url = intent.getStringExtra("notification_url");

            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                mainWebView.loadUrl(url);
            }
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: Cleaning up");

        // Remove back press callback
        if (onBackPressedCallback != null) {
            onBackPressedCallback.remove();
            onBackPressedCallback = null;
        }

        // Cleanup gesture detector
        if (gestureDetector != null) {
            gestureDetector = null;
        }

        // Cleanup progress bar managers
        if (mainProgressBarManager != null) {
            mainProgressBarManager.hide();
            mainProgressBarManager = null;
        }
        if (secondaryProgressBarManager != null) {
            secondaryProgressBarManager.hide();
            secondaryProgressBarManager = null;
        }

        // Stop network monitoring
        if (networkCheckHandler != null && networkCheckRunnable != null) {
            networkCheckHandler.removeCallbacks(networkCheckRunnable);
        }

        // === CACHE CLEANUP (if enabled) ===
        if (Config.CLEAR_CACHE_ON_EXIT) {
            Log.d(TAG, "Clearing cache on exit");
            if (mainWebView != null) {
                mainWebView.clearCache(true);
            }
            if (secondaryWebView != null) {
                secondaryWebView.clearCache(true);
            }
        }

        // === COOKIES CLEANUP ===
        if (Config.CLEAR_COOKIES_ON_EXIT) {
            Log.d(TAG, "Clearing cookies on exit");
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();
        }

        // Destroy WebViews
        if (mainWebView != null) {
            mainWebView.destroy();
        }

        if (secondaryWebView != null) {
            secondaryWebView.destroy();
        }

        super.onDestroy();
    }

    // AndroidInterface for JavaScript communication
    private class AndroidInterface {
        @android.webkit.JavascriptInterface
        public void openFormInSecondaryWebView(String action, String postData) {
            Log.d(TAG, "openFormInSecondaryWebView called - Action: " + action);

            // Execute on Main Thread (UI Thread)
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Check if URL is HTTP and user wants to proceed
                        checkHTTPSWarningAsync(action, proceed -> {
                            if (!proceed) {
                                Log.d(TAG, "User cancelled HTTP form posting");
                                return; // User cancelled - don't post the form
                            }

                            // Show secondary container
                            FrameLayout secondaryContainer = findViewById(R.id.secondary_container);
                            if (secondaryContainer == null) {
                                Log.e(TAG, "ERROR: secondary_container not found!");
                                return;
                            }

                            secondaryContainer.setVisibility(View.VISIBLE);
                            Log.d(TAG, "Secondary container visibility set to VISIBLE");

                            if (secondaryWebView == null) {
                                Log.d(TAG, "Creating new secondary WebView");
                                secondaryWebView = new CustomWebView(MainActivity.this);
                                setupWebView(secondaryWebView, false);
                                FrameLayout secondaryWebViewContainer = findViewById(R.id.secondary_webview_container);
                                if (secondaryWebViewContainer != null) {
                                    secondaryWebViewContainer.addView(secondaryWebView, new FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                    ));
                                    Log.d(TAG, "Secondary WebView created and added to container");
                                }

                                // Setup scroll listener for secondary WebView to control refresh
                                secondaryWebView.setOnScrollChangeListener((scrollY, isAtTop) -> {
                                    if (secondarySwipeRefreshLayout != null && Config.ENABLEPULLTOREFRESHSECONDARY) {
                                        secondarySwipeRefreshLayout.setEnabled(isAtTop);
                                        Log.d(TAG, "Secondary WebView scroll (AndroidInterface) - ScrollY: " + scrollY + " | Refresh enabled: " + isAtTop);
                                    }
                                });
                            }

                            // Update URL display
                            updateSecondaryUrlDisplay(action);

                            // Post the form to secondary WebView
                            Log.d(TAG, "Posting to: " + action + " with data: " + postData);
                            secondaryWebView.postUrl(action, postData.getBytes());
                            Log.d(TAG, "postUrl called successfully");
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "Exception in openFormInSecondaryWebView: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void setKeepScreenOn(boolean keepOn) {
            Log.d(TAG, "setKeepScreenOn called from JS: " + keepOn);

            // Execute on Main Thread (UI Thread)
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (keepOn) {
                        // screen not blocking
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        Log.d(TAG, "Screen lock ENABLED");
                    } else {
                        // screen will block
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        Log.d(TAG, "Screen lock DISABLED");
                    }
                }
            });
        }
    }
}

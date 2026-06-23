package com.my.webviewapplication.mobile;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import pl.droidsonroids.gif.GifImageView;

/**
 * Splash Screen Activity
 * Displays animated splash screen on app startup
 * Duration controlled by Config.SPLASH_TIMEOUT_MS
 * Can optionally wait for MainActivity/WebView to load if Config.SPLASH_WAIT_FOR_WEBVIEW is true
 */
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";

    // Handler for delayed splash screen transition
    private Handler splashHandler;

    // Flag to track if splash should wait for WebView
    private boolean shouldWaitForWebView;

    // Timeout for waiting for WebView to load
    private static final long WEBVIEW_LOAD_TIMEOUT = 30000; // 30 seconds max wait

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate: Splash screen starting");

        // Check if splash screen is enabled in config
        if (!Config.ENABLE_SPLASH_SCREEN) {
            Log.d(TAG, "onCreate: Splash screen disabled in config - going directly to MainActivity");
            goToMainActivity();
            return;
        }

        setContentView(R.layout.activity_splash);

        // Force the status bar to use the specific color defined in Config.java
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            try {
                getWindow().setStatusBarColor(android.graphics.Color.parseColor(Config.STATUS_BAR_COLOR));
            } catch (Exception e) {
                Log.e(TAG, "Invalid color format in Config.STATUS_BAR_COLOR", e);
            }
        }

        // 1. Force the layout to read the background color from Config.java
        View rootLayout = findViewById(R.id.splash_root_layout);
        if (rootLayout != null) {
            try {
                rootLayout.setBackgroundColor(android.graphics.Color.parseColor(Config.SPLASH_BG_COLOR));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid color format in Config.SPLASH_BG_COLOR", e);
            }
        }

        // 2. Handle the media type (GIF vs Video)
        GifImageView gifImageView = findViewById(R.id.splash_gif);
        VideoView videoView = findViewById(R.id.splash_video);

        if ("video".equalsIgnoreCase(Config.SPLASH_MEDIA_TYPE)) {
            if (gifImageView != null) gifImageView.setVisibility(View.GONE);
            if (videoView != null) {
                videoView.setVisibility(View.VISIBLE);

                // Keep the video overlay on top of the layout background
                videoView.setZOrderMediaOverlay(true);

                String videoPath = "android.resource://" + getPackageName() + "/raw/" + Config.SPLASH_VIDEO_NAME;
                videoView.setVideoURI(android.net.Uri.parse(videoPath));
                videoView.setOnPreparedListener(mp -> mp.setLooping(Config.SPLASH_VIDEO_LOOP));
                videoView.start();
                Log.d(TAG, "onCreate: Splash Video started");
            }
        } else {
            if (videoView != null) videoView.setVisibility(View.GONE);
            if (gifImageView != null) {
                gifImageView.setVisibility(View.VISIBLE);
                int gifResId = getResources().getIdentifier(Config.SPLASH_GIF_NAME, "drawable", getPackageName());
                if (gifResId != 0) {
                    gifImageView.setImageResource(gifResId);
                }
                Log.d(TAG, "onCreate: Splash GIF loaded");
            }
        }

        // Check if we should wait for WebView to load
        shouldWaitForWebView = Config.SPLASH_WAIT_FOR_WEBVIEW;
        Log.d(TAG, "onCreate: SPLASH_WAIT_FOR_WEBVIEW = " + shouldWaitForWebView);

        splashHandler = new Handler(Looper.getMainLooper());

        if (shouldWaitForWebView) {
            // Wait for MainActivity to signal that WebView is loaded
            Log.d(TAG, "onCreate: Waiting for WebView to load (max " + WEBVIEW_LOAD_TIMEOUT + "ms)");
            waitForWebViewToLoad();
        } else {
            // Just wait for the configured timeout
            Log.d(TAG, "onCreate: Waiting for configured timeout: " + Config.SPLASH_TIMEOUT_MS + "ms");
            scheduleMainActivityTransition(Config.SPLASH_TIMEOUT_MS);
        }
    }

    /**
     * Schedule transition to MainActivity after timeout
     * @param delayMs Delay in milliseconds
     */
    private void scheduleMainActivityTransition(long delayMs) {
        splashHandler.postDelayed(() -> {
            Log.d(TAG, "scheduleMainActivityTransition: Timeout reached - transitioning to MainActivity");
            goToMainActivity();
        }, delayMs);
    }

    /**
     * Wait for MainActivity to load WebView
     * Will transition after WebView loads or after timeout
     */
    private void waitForWebViewToLoad() {
        // Set a maximum timeout even when waiting for WebView
        splashHandler.postDelayed(() -> {
            Log.d(TAG, "waitForWebViewToLoad: WebView load timeout reached - transitioning anyway");
            goToMainActivity();
        }, WEBVIEW_LOAD_TIMEOUT);

        Log.d(TAG, "waitForWebViewToLoad: Splash will dismiss when MainActivity signals WebView is loaded");
    }

    /**
     * Transition to MainActivity and close splash screen
     */
    private void goToMainActivity() {
        Log.d(TAG, "goToMainActivity: Starting MainActivity");

        // Remove any pending callbacks
        if (splashHandler != null) {
            splashHandler.removeCallbacksAndMessages(null);
        }

        // Start MainActivity
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);

        // Close SplashActivity with animation
        finish();

        // Optional: Add transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: Cleaning up splash screen");

        // Clean up handler
        if (splashHandler != null) {
            splashHandler.removeCallbacksAndMessages(null);
            splashHandler = null;
        }

        super.onDestroy();
    }
}

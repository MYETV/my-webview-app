package com.my.webviewapplication.mobile;

public class Config {
    
    // ============================================
    // MAIN CONFIGURATION
    // ============================================

    // START_URL_TYPE: "internet" (load HOME_URL) or "local" (load offline HTML from assets)
    public static final String START_URL_TYPE = "internet"; // Options: "internet", "local"

    // Local HTML path (used only if START_URL_TYPE = "local")
    // Example: "file:///android_asset/index.html"
    public static final String LOCAL_START_URL = "file:///android_asset/app/index.html";

    // Main URL to load in WebView (used only if START_URL_TYPE = "internet")
    public static final String HOME_URL = "https://example.oskarcosimo.com/?apptype=androidmobile";

    // ============================================
    // SECURITY SETTINGS
    // ============================================

    // LOAD_ONLY_HTTPS: if true, only allow HTTPS URLs (HTTP will be rejected); false = allow both HTTP and HTTPS
    // Special URLs and local URLs are always exempted from this restriction
    public static final boolean LOAD_ONLY_HTTPS = false;

    // Warn user before loading non-HTTPS URLs in secondary WebView
    // If true, show confirmation dialog before loading HTTP URLs
    // If false, load HTTP URLs without warning
    public static final boolean WARN_IF_NOT_HTTPS = true;
    
    // Allowed domains (internal links - all these url can be opened inside the main WebView, all other links will be opened in secondary webview if enabled)
    public static final String[] ALLOWED_DOMAINS = {
        "oskarcosimo.com",
        "www.oskarcosimo.com",
        "example.oskarcosimo.com"
    };

    // Only web pages loaded from these domains can perform special actions such as closetheapp://
    public static final String[] SPECIAL_URL_ALLOWED_DOMAINS = {
            "oskarcosimo.com",
            "www.oskarcosimo.com",
            "example.oskarcosimo.com"
    };

    // External domains to open ALWAYS in secondary WebView
    public static final String[] SECONDARY_WEBVIEW_DOMAINS = {
        "link1.oskarcosimo.com",
        "link2.oskarcosimo.com"
    };

    // ============================================
    // SECONDARY BROWSER ADVANCED CONFIGURATION
    // ============================================
    // Secondary browser title in strings.xml

    // Secondary Browser Colors (Hex format: #RRGGBB)
    public static final String SECONDARY_BROWSER_BG_COLOR = "#DAA520";        // Goldenrod
    public static final String SECONDARY_BROWSER_BUTTON_COLOR = "#B8860B";    // Dark Goldenrod
    public static final String SECONDARY_BROWSER_TEXT_COLOR = "#FFFFFF";      // White

    // Secondary Browser Top Bar Height (in dp)
    public static final int SECONDARY_BROWSER_TOP_BAR_HEIGHT = 56;

    // Secondary Browser Button Size (in dp)
    public static final int SECONDARY_BROWSER_BUTTON_SIZE = 48;
    
    // Show/Hide Secondary Browser Title
    public static final boolean SECONDARY_BROWSER_SHOW_TITLE = true;

    // Secondary Browser Title Font Size (in sp)
    public static final int SECONDARY_BROWSER_TITLE_SIZE = 18;

    // Secondary Browser Button Text
    public static final String SECONDARY_BROWSER_BUTTON_TEXT = "✕";

    // Secondary Browser Button Text Size (in sp)
    public static final int SECONDARY_BROWSER_BUTTON_TEXT_SIZE = 24;

    // Secondary Browser Top Bar Elevation (in dp)
    public static final int SECONDARY_BROWSER_TOP_BAR_ELEVATION = 4;

    // Secondary Browser Corner Radius for Button (in dp)
    public static final int SECONDARY_BROWSER_BUTTON_CORNER_RADIUS = 4;

// ============================================
    // SPLASH SCREEN CONFIGURATION
    // ============================================

    public static final boolean ENABLE_SPLASH_SCREEN = true;
    public static final int SPLASH_TIMEOUT_MS = 3000; // 3 seconds
    public static final boolean SPLASH_WAIT_FOR_WEBVIEW = false; // Wait until WebView loads

    // Options: "gif" or "video"
    public static final String SPLASH_MEDIA_TYPE = "video";

    // Video filename without extension (must be placed in res/raw folder)
    // e.g., "intro_video" for res/raw/intro_video.mp4
    public static final String SPLASH_VIDEO_NAME = "intro_video";

    // If true, the video will repeat continuously. If false, it will pause on the last frame.
    public static final boolean SPLASH_VIDEO_LOOP = false;

    // Splash screen background color (Hex format: #RRGGBB)
    public static final String SPLASH_BG_COLOR = "#000000";
    
    // ============================================
    // WEBVIEW CONFIGURATION
    // ============================================
    
    public static final boolean ENABLE_JAVASCRIPT = true;
    public static final boolean ENABLE_DOM_STORAGE = true;
    public static final boolean ENABLE_HARDWARE_ACCELERATION = true;

    // Cache management
    public static final boolean CLEAR_CACHE_ON_START = true; // Clear the webview cache on app start
    public static final boolean CLEAR_CACHE_ON_EXIT = true; // Clear the webview cache when app closes
    public static final boolean USE_CACHE = true; // Enable/disable WebView cache

    // Cookie management
    public static final boolean CLEAR_COOKIES_ON_START = false; // Clear cookies on app start
    public static final boolean CLEAR_COOKIES_ON_EXIT = false; // Clear cookies when app closes

    public static final boolean ENABLE_ZOOM = false;
    
    // Custom UserAgent (leave empty for default)
    public static final String CUSTOM_USER_AGENT = "";

    // Stop and reload WebView (with the initial url) when app goes to background
    public static final boolean STOP_APP_IN_BACKGROUND = false;

    // Add theme parameter to URL (light/dark)
    public static final boolean ENHANCE_URL_WITH_THEME = true;

    // ============================================
    // ORIENTATION SETTINGS
    // ============================================
    
    // Options: "portrait", "landscape", "auto", "none"
    public static final String PHONE_ORIENTATION = "none";
    public static final String TABLET_ORIENTATION = "none";
    
    // ============================================
    // QR CODE SCANNER
    // ============================================
    
    public static final boolean ENABLE_QR_SCANNER = true;
    
    // ============================================
    // FIREBASE PUSH NOTIFICATIONS
    // ============================================
    
    public static final boolean ENABLE_FIREBASE_PUSH = true;
    // this will add the param fcmtoken to the querystring with the firebase user id to use it with api
    public static final boolean ENHANCE_URL_WITH_FCM_TOKEN = true;
    
    // ============================================
    // OFFLINE MODE
    // ============================================

    // offline mode is when the app try to load an url over the internet that is not available; it will show an offline html as a notice
    public static final boolean ENABLE_OFFLINE_MODE = true;
    public static final String OFFLINE_HTML_PATH = "file:///android_asset/offline/index.html";

    // Network check interval (milliseconds) - how often to check if network is back
    public static final long NETWORK_MONITOR_INTERVAL = 3000; // 3 seconds

    // If true, check for 3 times the connection before goes offline
    // This prevents false offline triggers
    public static final boolean STRICT_OFFLINE_MODE = true;

    // ============================================
    // SPECIAL URL HANDLERS
    // ============================================
    // special urls can be opened only by the url specified in ALLOWED_DOMAINS
    // URL triggers for app actions
    public static final String URL_CLOSE_APP = "closetheapp://";
    public static final String URL_OPEN_QR_SCANNER = "qrcode://";
    public static final String URL_OPEN_APP_SETTINGS = "openappsettings://";
    public static final String URL_SHARE_CONTENT = "shareapp://";
    public static final String URL_PICTURE_IN_PICTURE = "pictureinpicture://";
    // Special URL to toggle Do Not Disturb mode on/off
    public static final String URL_TOGGLE_NOTIFICATIONS = "togglenotifications://";
    // Custom URL schemes used in the app
    public static final String[] CUSTOM_SCHEMES = {
            "pictureinpicture://",
            "shareapp://",
            "closetheapp://",
            "qrcode://",
            "openappsettings://",
            "togglenotifications://"
    };

    // ============================================
    // PERMISSIONS
    // ============================================
    
    public static final boolean REQUIRE_CAMERA = true;
    public static final boolean REQUIRE_NOTIFICATIONS = true;

    // ============================================
    // PULL-TO-REFRESH SETTINGS
    // ============================================

    // Enable or disable pull-to-refresh functionality for the main webview
    public static final boolean ENABLEPULLTOREFRESH = false;

    // Enable or disable pull-to-refresh functionality for secondary WebView
    // Set to false to disable refresh only for secondary browser
    public static final boolean ENABLEPULLTOREFRESHSECONDARY = true;

    // Refresh spinner color (hex RGB format - blue)
    public static final int PULLTOREFRESHSPINNERCOLOR = 0xFF1DA1F2;

    // Refresh spinner background color (white)
    public static final int PULLTOREFRESHBACKGROUNDCOLOR = 0xFFFFFFFF;

    // Distance in pixels required to trigger refresh (lower = more sensitive)
    public static final int PULLTOREFRESHDISTANCE = 100;

    // Refresh timeout in milliseconds (stop spinner if page doesn't finish loading)
    public static final long PULLTOREFRESHDISAPPEARTIMEOUT = 5000; // 5 seconds

    // ============================================
    // PROGRESS BAR / LOADING INDICATOR CONFIGURATION
    // ============================================

    // Enable or disable progress bar during page loading
    public static final boolean SHOW_PROGRESS_BAR = true;

    // Progress bar style options: "spinner_center", "spinner_corner", "linear_progress"
    // spinner_center: Large spinning circle in the center of the screen with semi-transparent background
    // spinner_corner: Smaller spinning circle in the top-right corner (non-intrusive)
    // linear_progress: Linear progress bar at the top of the screen (minimal design)
    public static final String PROGRESS_BAR_STYLE = "linear_progress";

    // Progress bar color (hex RGB format - dark goldenrod by default: 0xFFB8860B)
    // 0xFF1DA1F2 (blue), 0xFFFF6B6B (Red), 0xFF4CAF50 (Green), 0xFFFFA500 (Orange), 0xFF9C27B0 (Purple)
    public static final int PROGRESS_BAR_COLOR = 0xFFB8860B;

    // ============================================
    // UI SETTINGS
    // ============================================
    
    public static final boolean FULLSCREEN_MODE = false;
    // hide the statusbar (immersive fullscreen)
    public static final boolean HIDE_STATUS_BAR = false;
    // Status bar background color (hex format: #RRGGBB)
    public static final String STATUS_BAR_COLOR = "#DAA520";
    public static final boolean ENABLE_SWIPE_NAVIGATION = false;

    // Status bar text color (true = dark text, false = light text)
    public static final boolean DARK_STATUS_BAR_TEXT = false;

    // ============================================
    // DO NOT DISTURB MODE (NOTIFICATION BLOCKING)
    // ============================================

    // Enable Do Not Disturb mode when app is in foreground
    // This blocks all notifications from other apps while your app is open
    // works with URL_TOGGLE_NOTIFICATIONS (the user must enable it by navigating into the special url)
    public static final boolean ENABLE_DND_MODE = true;

    // ============================================
    // BACK BUTTON BEHAVIOR
    // ============================================
    
    public static final boolean EXIT_ON_BACK_PRESSED_HOME = true;
    public static final boolean SHOW_EXIT_DIALOG = true;
}

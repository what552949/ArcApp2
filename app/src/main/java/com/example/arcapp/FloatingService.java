package com.example.arcapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class FloatingService extends Service {

    private WindowManager wm;
    private WebView webView;
    private ImageView lockButton;
    private WindowManager.LayoutParams webParams;
    private WindowManager.LayoutParams lockParams;
    private boolean locked = false;
    private boolean viewAdded = false;

    private ValueCallback<Uri[]> filePathCallback;
    private static final int LOCK_SIZE_DP = 56;

    private final Handler lockHandler = new Handler(Looper.getMainLooper());
    private long lastClickTime = 0;
    private int clickCount = 0;
    private static final long MULTI_CLICK_WINDOW = 400L;

    private final Runnable clickResolver = new Runnable() {
        @Override
        public void run() {
            if (clickCount == 1 || clickCount == 2) {
                toggleLock();
            }
            clickCount = 0;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        createNotification();
        createWebView();
        createLockButton();
        viewAdded = true;
    }

    private int getWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "arc_channel", "抛物线悬浮窗", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Notification n = new NotificationCompat.Builder(this, "arc_channel")
                .setContentTitle("抛物线悬浮窗运行中")
                .setContentText("点击锁按钮可穿透操作")
                .setSmallIcon(R.drawable.ic_lock_closed)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(0x00000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(Intent.createChooser(intent, "选择图片"));
                } catch (Exception ignored) {}
                return true;
            }
        });

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void requestLoadImage() {
                lockHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadLatestImageFromGallery();
                    }
                });
            }
        }, "AndroidBridge");

        webView.loadUrl("file:///android_asset/index.html?mode=floating");

        webParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        webParams.gravity = Gravity.TOP | Gravity.START;

        wm.addView(webView, webParams);
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void notifyJsError(String msg) {
        if (webView == null) return;
        final String safe = msg.replace("'", "\\'").replace("\n", " ");
        webView.post(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                webView.evaluateJavascript(
                        "window.__onImageError && window.__onImageError('" + safe + "');", null);
            }
        });
    }

    private void loadLatestImageFromGallery() {
        if (!hasMediaPermission()) {
            notifyJsError("请先打开 App，授予相册权限");
            return;
        }

        try {
            Uri collection;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            } else {
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            }

            String[] projection = new String[]{ MediaStore.Images.Media._ID };

            ContentResolver cr = getContentResolver();
            Cursor cursor = cr.query(
                    collection,
                    projection,
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
            );

            if (cursor == null || !cursor.moveToFirst()) {
                if (cursor != null) cursor.close();
                notifyJsError("没读到最新截图");
                return;
            }

            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
            cursor.close();

            Uri imageUri = ContentUris.withAppendedId(collection, id);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream is1 = cr.openInputStream(imageUri);
            BitmapFactory.decodeStream(is1, null, bounds);
            if (is1 != null) is1.close();

            int w0 = bounds.outWidth;
            int h0 = bounds.outHeight;
            if (w0 <= 0 || h0 <= 0) {
                notifyJsError("图片解码失败");
                return;
            }

            int sampleSize = 1;
            while (w0 / sampleSize > 2000) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            InputStream is2 = cr.openInputStream(imageUri);
            Bitmap bmp = BitmapFactory.decodeStream(is2, null, opts);
            if (is2 != null) is2.close();

            if (bmp == null) {
                notifyJsError("图片解码失败");
                return;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            bmp.recycle();
            byte[] bytes = baos.toByteArray();
            baos.close();

            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            final String dataUri = "data:image/jpeg;base64," + base64;

            if (webView == null) return;
            webView.post(new Runnable() {
                @Override
                public void run() {
                    if (webView == null) return;
                    webView.evaluateJavascript(
                            "window.__onImageLoaded && window.__onImageLoaded('" + dataUri + "');",
                            null);
                }
            });

        } catch (Exception e) {
            notifyJsError("读取失败：" + e.getClass().getSimpleName());
        }
    }

    private void createLockButton() {
        int size = (int) (LOCK_SIZE_DP * getResources().getDisplayMetrics().density);

        lockButton = new ImageView(this);
        lockButton.setImageResource(R.drawable.ic_lock_open);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#CC21262D"));
        bg.setStroke((int) (2 * getResources().getDisplayMetrics().density),
                Color.parseColor("#F0883E"));
        lockButton.setBackground(bg);
        lockButton.setAlpha(0.6f);
        lockButton.setPadding(size / 5, size / 5, size / 5, size / 5);
        lockButton.setScaleType(ImageView.ScaleType.FIT_CENTER);

        lockParams = new WindowManager.LayoutParams(
                size,
                size,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lockParams.gravity = Gravity.TOP | Gravity.START;
        lockParams.x = 60;
        lockParams.y = 300;

        lockButton.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean moved;
            long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = lockParams.x;
                        startY = lockParams.y;
                        moved = false;
                        downTime = System.currentTimeMillis();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                        lockParams.x = startX + (int) dx;
                        lockParams.y = startY + (int) dy;
                        wm.updateViewLayout(lockButton, lockParams);
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved && System.currentTimeMillis() - downTime < 600) {
                            onLockClick();
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(lockButton, lockParams);
    }

    private void onLockClick() {
        long now = System.currentTimeMillis();

        if (now - lastClickTime > MULTI_CLICK_WINDOW) {
            clickCount = 0;
            lockHandler.removeCallbacks(clickResolver);
        }
        lastClickTime = now;
        clickCount++;

        if (clickCount >= 3) {
            clickCount = 0;
            lockHandler.removeCallbacks(clickResolver);
            closeFloating();
            return;
        }

        lockHandler.removeCallbacks(clickResolver);
        lockHandler.postDelayed(clickResolver, MULTI_CLICK_WINDOW);
    }

    private void closeFloating() {
        stopSelf();
    }

    private void toggleLock() {
        locked = !locked;

        if (lockButton != null) {
            lockButton.setImageResource(locked ? R.drawable.ic_lock_closed : R.drawable.ic_lock_open);
        }

        if (locked) {
            webParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            webParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (viewAdded && webView != null) {
            try {
                wm.updateViewLayout(webView, webParams);
            } catch (Exception ignored) {}
        }

        if (webView != null) {
            String js = "window.__setPanelCollapsed && window.__setPanelCollapsed(" + locked + ");";
            webView.evaluateJavascript(js, null);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        viewAdded = false;
        lockHandler.removeCallbacks(clickResolver);

        if (webView != null) {
            try { webView.stopLoading(); } catch (Exception ignored) {}
            try { webView.loadUrl("about:blank"); } catch (Exception ignored) {}
            try { wm.removeView(webView); } catch (Exception ignored) {}
            webView.destroy();
            webView = null;
        }
        if (lockButton != null) {
            try { wm.removeView(lockButton); } catch (Exception ignored) {}
            lockButton = null;
        }
    }
}

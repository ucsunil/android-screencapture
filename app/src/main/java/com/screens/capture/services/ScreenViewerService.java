package com.screens.capture.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;
import com.screens.capture.MainActivity;
import com.screens.capture.R;
import com.screens.capture.utils.ScreenProvider;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicReference;

public class ScreenViewerService extends AbstractScreenCaptureServerService {

    public static final String RESULT_CODE = "resultCode";
    public static final String RESULT_INTENT = "resultIntent";
    public static final int DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ScreenProvider screenProvider;

    private final HandlerThread handlerThread = new HandlerThread(getClass().getSimpleName(),
                                                    Process.THREAD_PRIORITY_BACKGROUND);
    private Handler handler;
    private AtomicReference<byte[]> latestPic = new AtomicReference<byte[]>();
    private MediaProjectionManager manager;
    private WindowManager windowManager;

    public ScreenViewerService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        projection = manager.getMediaProjection(intent.getIntExtra(RESULT_CODE, -1),
                (Intent)intent.getParcelableExtra(RESULT_INTENT));
        screenProvider = new ScreenProvider(this);

        MediaProjection.Callback callback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                virtualDisplay.release();
            }
        };

        virtualDisplay = projection.createVirtualDisplay("screencapture", screenProvider.getWidth(),
                screenProvider.getHeight(), getResources().getDisplayMetrics().densityDpi,
                DISPLAY_FLAGS, screenProvider.getSurface(), null, handler);
        projection.registerCallback(callback, handler);
        return START_NOT_STICKY;
    }

    @Override
    protected void buildForegroundNotification(Notification.Builder builder) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setContentTitle(getString(R.string.app_name))
                .setContentIntent(pi)
                .setSmallIcon(R.mipmap.ic_launcher);
    }

    @Override
    public void onDestroy() {
        projection.stop();
        super.onDestroy();
    }

    @Override
    protected boolean configureRoutes(AsyncHttpServer server) {
        serveWebSockets("/ss", null);
        server.get(getRootPath() + "/screen/.*", new ScreenshotRequestCallback());
        return true;
    }

    @Override
    protected int getPort() {
        return 8080;
    }

    @Override
    protected int getMaxIdleTimeSeconds() {
        return 120;
    }

    @Override
    protected int getMaxSequentialInvalidRequests() {
        return 10;
    }

    public WindowManager getWindowManager() {
        return windowManager;
    }

    public Handler getHandler() {
        return handler;
    }


    public void updateImage(byte[] newPic) {
        latestPic.set(newPic);

        for(WebSocket socket : getWebsockets()) {
            socket.send("screen/" + Long.toString(SystemClock.uptimeMillis()));
        }
    }

    private class ScreenshotRequestCallback implements HttpServerRequestCallback {
        @Override
        public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
            response.setContentType("image/png");
            byte[] pic = latestPic.get();
            ByteArrayInputStream bais = new ByteArrayInputStream(pic);
            response.sendStream(bais, pic.length);
        }
    }

}

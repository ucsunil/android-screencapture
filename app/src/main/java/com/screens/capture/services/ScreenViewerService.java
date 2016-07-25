package com.screens.capture.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
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

    public static final String EXTRA_RESULT_CODE="resultCode";
    public static final String EXTRA_RESULT_INTENT="resultIntent";
    static final int VIRT_DISPLAY_FLAGS=
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    final private HandlerThread handlerThread=new HandlerThread(getClass().getSimpleName(),
            android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private Handler handler;
    private AtomicReference<byte[]> latestPng=new AtomicReference<byte[]>();
    private MediaProjectionManager mgr;
    private WindowManager wmgr;
    private ScreenProvider it;

    @Override
    public void onCreate() {
        super.onCreate();

        mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        wmgr=(WindowManager)getSystemService(WINDOW_SERVICE);

        handlerThread.start();
        handler=new Handler(handlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        projection=
                mgr.getMediaProjection(i.getIntExtra(EXTRA_RESULT_CODE, -1),
                        (Intent)i.getParcelableExtra(EXTRA_RESULT_INTENT));

        it=new ScreenProvider(this);

        MediaProjection.Callback cb=new MediaProjection.Callback() {
            @Override
            public void onStop() {
                vdisplay.release();
            }
        };

        vdisplay=projection.createVirtualDisplay("andprojector",
                it.getWidth(), it.getHeight(),
                getResources().getDisplayMetrics().densityDpi,
                VIRT_DISPLAY_FLAGS, it.getSurface(), null, handler);
        projection.registerCallback(cb, handler);

        return(START_NOT_STICKY);
    }

    @Override
    public void onDestroy() {
        projection.stop();

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        ScreenProvider newIt=new ScreenProvider(this);

        if (newIt.getWidth()!=it.getWidth() ||
                newIt.getHeight()!=it.getHeight()) {
            ScreenProvider oldIt=it;

            it=newIt;
            vdisplay.resize(it.getWidth(), it.getHeight(),
                    getResources().getDisplayMetrics().densityDpi);
            vdisplay.setSurface(it.getSurface());

            oldIt.close();
        }
    }

    @Override
    protected boolean configureRoutes(AsyncHttpServer server) {
        serveWebSockets("/ss", null);

        server.get(getRootPath()+"/screen/.*",
                new ScreenshotRequestCallback());

        return(true);
    }

    @Override
    protected int getPort() {
        return(4999);
    }
/*
    @Override
    protected int getMaxIdleTimeSeconds() {
        return(120);
    }
*/
    @Override
    protected int getMaxSequentialInvalidRequests() {
        return(10);
    }

    public WindowManager getWindowManager() {
        return(wmgr);
    }

    public Handler getHandler() {
        return(handler);
    }

    public void updateImage(byte[] newPng) {
        latestPng.set(newPng);

        for (WebSocket socket : getWebSockets()) {
            socket.send("screen/"+Long.toString(SystemClock.uptimeMillis()));
        }
    }

    @Override
    protected void buildForegroundNotification(NotificationCompat.Builder builder) {
        Intent iActivity=new Intent(this, MainActivity.class);
        PendingIntent piActivity=PendingIntent.getActivity(this, 0,
                iActivity, 0);

        builder.setContentTitle(getString(R.string.app_name))
                .setContentIntent(piActivity)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setTicker(getString(R.string.app_name));
    }

    private class ScreenshotRequestCallback
            implements HttpServerRequestCallback {
        @Override
        public void onRequest(AsyncHttpServerRequest request,
                              AsyncHttpServerResponse response) {
            response.setContentType("image/png");

            byte[] png=latestPng.get();
            ByteArrayInputStream bais=new ByteArrayInputStream(png);

            response.sendStream(bais, png.length);
        }
    }
}

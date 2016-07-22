package com.screens.capture.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.util.Log;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.AbstractTemplateLoader;
import com.github.jknack.handlebars.io.StringTemplateSource;
import com.github.jknack.handlebars.io.TemplateSource;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;
import com.screens.capture.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.greenrobot.event.EventBus;
import com.screens.capture.receivers.StopReceiver;

/**
 * Created by Sunil on 7/20/2016.
 */
public abstract class AbstractScreenCaptureServerService extends Service {

    private static final int NOTIFICATION_ID = 1001;
    private AsyncHttpServer server;
    private SecureRandom random = new SecureRandom();
    private String rootPath;
    private ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeoutFuture;
    private int invalidRequestCount = 0;
    private CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<WebSocket>();
    private Handlebars handlebars;

    @Override
    public void onCreate() {
        super.onCreate();

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = connectivityManager.getActiveNetworkInfo();

        if(ni == null || ni.getType() == ConnectivityManager.TYPE_MOBILE) {
            EventBus.getDefault().post(new ServerStartRejectedEvent());
            stopSelf();
        } else {
            handlebars = new Handlebars(new AssetTemplateLoader(getAssets()));
            rootPath = "/" + new BigInteger(20, random);

            server = new AsyncHttpServer();
            if(configureRoutes(server)) {
                server.get("/.*", new AssetRequestCallback());
            }
            server.listen(getPort());

            raiseReadyEvent();
            foregroundify();
            timeoutFuture = timer.schedule(onTimeout, getMaxIdleTimeSeconds(), TimeUnit.SECONDS);
        }
    }

    protected void serveWebSockets(String relPath, AsyncHttpServer.WebSocketRequestCallback callback)  {
        StringBuilder route = new StringBuilder(relPath);
        if(!relPath.startsWith("/")) {
            route.append('/');
        }
        route.append(relPath);
        if(callback == null) {
            callback = new WebSocketClientCallback();
        }
        server.websocket(route.toString(), callback);
    }

    private void raiseReadyEvent() {
        ServerStartedEvent event = new ServerStartedEvent();
        try {
            for(Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements(); ) {
                NetworkInterface networkInterface = interfaces.nextElement();

                for(Enumeration<InetAddress> addresses = networkInterface.getInetAddresses(); addresses.hasMoreElements(); ) {
                    InetAddress address = addresses.nextElement();
                    if(address instanceof InetAddress) {
                        event.addUrl("http://" + address.getHostAddress()+":8080" + rootPath + "/");
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        EventBus.getDefault().removeAllStickyEvents();
        EventBus.getDefault().postSticky(event);
    }

    protected String getRootPath() {
        return rootPath;
    }

    private void foregroundify() {
        Notification.Builder builder = new Notification.Builder(this);
        Intent receiver = new Intent(this, StopReceiver.class);
        PendingIntent piReceiver = PendingIntent.getBroadcast(this, 0, receiver, 0);
        builder.setAutoCancel(true).setDefaults(Notification.DEFAULT_ALL);
        buildForegroundNotification(builder);
        builder.addAction(new Notification.Action.Builder(R.mipmap.ic_launcher, "Hello", piReceiver).build());
        startForeground(NOTIFICATION_ID, builder.build());
    }

    protected Collection<WebSocket> getWebsockets() {
        return  sockets;
    }

    private class AssetRequestCallback implements HttpServerRequestCallback {

        private final AssetManager assets;

        AssetRequestCallback() {
            assets = getAssets();
        }

        private void handle404(AsyncHttpServerResponse response, String path, Exception ex) {
            Log.e(getClass().getSimpleName(), "Invalid URL: " + path, ex);
            response.code(404);
            response.end();
            trackInvalidRequests();
        }

        @Override
        public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
            String path = request.getPath();
            try {
                if(path.startsWith(rootPath)) {
                    path = path.substring(rootPath.length()+1);
                } else {
                    handle404(response, path, null);
                    return;
                }
                if(path.length() == 0 || "/".equals(path)) {
                    path = "index.html";
                } else if(path.startsWith("/")) {
                    path = path.substring(1);
                }
                if(path.endsWith(".hbs")) {
                    Template template = handlebars.compile(path);
                    Context context = getContextForPath(path);
                    response.send(template.apply(context));
                    response.setContentType("text/html");
                    context.destroy();
                } else {
                    AssetFileDescriptor afd = assets.openFd(path);
                    response.sendStream(afd.createInputStream(), afd.getLength());
                }
                resetTimeout();
                invalidRequestCount = 0;
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("This method is not implemented");
    }

    public static class ServerStartedEvent {
        private ArrayList<String> urls = new ArrayList<>();

        public void addUrl(String url) {
            urls.add(url);
        }
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().removeAllStickyEvents();
        EventBus.getDefault().postSticky(new ServerStoppedEvent());

        if(server != null) {
            server.stop();
            AsyncServer.getDefault().stop();
        }
        super.onDestroy();
    }

    protected void resetTimeout() {
        timeoutFuture.cancel(false);
        timeoutFuture = timer.schedule(onTimeout, getMaxIdleTimeSeconds(), TimeUnit.SECONDS);
    }

    protected void trackInvalidRequests() {
        invalidRequestCount++;
        if(invalidRequestCount > getMaxSequentialInvalidRequests()) {
            stopSelf();
        }
    }

    protected Context getContextForPath(String relpath) {
        throw new IllegalArgumentException("You need to override this if using Handlebars!");
    }

    private Runnable onTimeout = new Runnable() {
        @Override
        public void run() {
            stopSelf();
        }
    };

    public static class ServerStoppedEvent {}

    public static class ServerStartRejectedEvent {}

    public static String slurp(final InputStream is) throws IOException {
        final char[] buffer = new char[1024];
        final StringBuilder out = new StringBuilder();
        final InputStreamReader insr = new InputStreamReader(is, "UTF-8");

        while(true) {
            int rsz = insr.read(buffer, 0, buffer.length);
            if(rsz < 0) {
                break;
            }
            out.append(buffer, 0, rsz);
        }
        return out.toString();
    }

    private class WebSocketClientCallback implements AsyncHttpServer.WebSocketRequestCallback {

        @Override
        public void onConnected(final WebSocket webSocket, AsyncHttpServerRequest request) {
            sockets.add(webSocket);
            webSocket.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    if(ex != null) {
                        Log.e(getClass().getSimpleName(), "Exception with WebSocket", ex);
                    }
                    sockets.remove(webSocket);
                }
            });
        }
    }

    private static class AssetTemplateLoader extends AbstractTemplateLoader {
        private final AssetManager manager;

        public AssetTemplateLoader(AssetManager manager) {
            this.manager = manager;
        }

        @Override
        public TemplateSource sourceAt(String location) throws IOException {
            return new StringTemplateSource(location, slurp(manager.open(location)));
        }
    }

    protected abstract void buildForegroundNotification(Notification.Builder builder);

    protected abstract boolean configureRoutes(AsyncHttpServer server);

    protected abstract int getPort();

    protected abstract int getMaxIdleTimeSeconds();

    protected abstract int getMaxSequentialInvalidRequests();
}

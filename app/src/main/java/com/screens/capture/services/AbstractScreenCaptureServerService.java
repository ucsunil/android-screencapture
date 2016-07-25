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
import android.support.v4.app.NotificationCompat;
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
import com.screens.capture.receivers.StopReceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArrayList;
;

import de.greenrobot.event.EventBus;

abstract public class AbstractScreenCaptureServerService extends Service {
    abstract protected void buildForegroundNotification(NotificationCompat.Builder b);
    abstract protected boolean configureRoutes(AsyncHttpServer server);
    abstract protected int getPort();
    abstract protected int getMaxSequentialInvalidRequests();

    private static final int NOTIFY_ID=42408;
    private AsyncHttpServer server;
    private String rootPath;
    private int invalidRequestCount=0;
    private CopyOnWriteArrayList<WebSocket> sockets=
            new CopyOnWriteArrayList<WebSocket>();
    private Handlebars handlebars;

    @Override
    public void onCreate() {
        super.onCreate();

        ConnectivityManager connectivityManager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni=connectivityManager.getActiveNetworkInfo();

        if (ni==null || ni.getType()==ConnectivityManager.TYPE_MOBILE) {
            EventBus.getDefault().post(new ServerStartRejectedEvent());
            stopSelf();
        }
        else {
            handlebars=new Handlebars(new AssetTemplateLoader(getAssets()));
            rootPath = "/device";
            server=new AsyncHttpServer();

            if (configureRoutes(server)) {
                server.get("/.*", new AssetRequestCallback());
            }

            server.listen(getPort());

            raiseReadyEvent();
            foregroundify();
        }
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        return(START_NOT_STICKY);
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().removeAllStickyEvents();
        EventBus.getDefault().postSticky(new ServerStoppedEvent());

        if (server!=null) {
            server.stop();
            AsyncServer.getDefault().stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Go away");
    }

    protected void serveWebSockets(String relpath,
                                   AsyncHttpServer.WebSocketRequestCallback cb) {
        StringBuilder route=new StringBuilder(rootPath);

        if (!relpath.startsWith("/")) {
            route.append('/');
        }

        route.append(relpath);

        if (cb==null) {
            cb=new WebSocketClientCallback();
        }

        server.websocket(route.toString(), cb);
    }

    private void raiseReadyEvent() {
        ServerStartedEvent event=new ServerStartedEvent();

        try {
            for (Enumeration<NetworkInterface> enInterfaces=
                NetworkInterface.getNetworkInterfaces();
                 enInterfaces.hasMoreElements(); ) {
                NetworkInterface ni=enInterfaces.nextElement();

                for (Enumeration<InetAddress> enAddresses=
                     ni.getInetAddresses();
                     enAddresses.hasMoreElements(); ) {
                    InetAddress addr=enAddresses.nextElement();

                    if (addr instanceof Inet4Address) {
                        event.addUrl(
                                "http://"+addr.getHostAddress()+":4999"+rootPath+"/");
                    }
                }
            }
        }
        catch (SocketException ex) {
            ex.printStackTrace();
        }

        EventBus.getDefault().removeAllStickyEvents();
        EventBus.getDefault().postSticky(event);
    }

    private void foregroundify() {
        NotificationCompat.Builder builder=
                new NotificationCompat.Builder(this);

        Intent iReceiver=new Intent(this, StopReceiver.class);
        PendingIntent piReceiver=
                PendingIntent.getBroadcast(this, 0, iReceiver, 0);

        builder.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL);

        buildForegroundNotification(builder);

        builder.addAction(R.mipmap.ic_launcher,
                getString(R.string.notify_stop),
                piReceiver);

        startForeground(NOTIFY_ID, builder.build());
    }

    protected String getRootPath() {
        return(rootPath);
    }

    protected Collection<WebSocket> getWebSockets() {
        return(sockets);
    }

    protected void trackInvalidRequests() {
        invalidRequestCount++;

        if (invalidRequestCount > getMaxSequentialInvalidRequests()) {
            stopSelf();
        }
    }

    protected Context getContextForPath(String path) {
        throw new IllegalStateException("Implementing this for use with Handlebars");
    }

    private class AssetRequestCallback
            implements HttpServerRequestCallback {
        private final AssetManager assets;

        AssetRequestCallback() {
            assets=getAssets();
        }

        @Override
        public void onRequest(AsyncHttpServerRequest request,
                              AsyncHttpServerResponse response) {
            String path=request.getPath();
            try {
                if (path.startsWith(rootPath)) {
                    path=path.substring(rootPath.length()+1);
                }
                else {
                    handle404(response, path, null);
                    return;
                }
                if (path.length()==0 || "/".equals(path)) {
                    path="index.html";
                }
                else if (path.startsWith("/")) {
                    path=path.substring(1);
                }

                if (path.endsWith(".hbs")) {
                    Template t=handlebars.compile(path);
                    Context ctxt = getContextForPath(path);

                    response.send(t.apply(ctxt));
                    response.setContentType("text/html");
                    ctxt.destroy();
                }
                else {
                    AssetFileDescriptor afd=assets.openFd(path);

                    response.sendStream(afd.createInputStream(),
                            afd.getLength());
                }
                invalidRequestCount = 0;
            }
            catch (IOException e) {
                handle404(response, path, e);
            }
        }

        private void handle404(AsyncHttpServerResponse response,
                               String path, Exception e) {
            Log.e(getClass().getSimpleName(), "Invalid URL: "+path, e);
            response.code(404);
            response.end();
            trackInvalidRequests();
        }
    }

    private class WebSocketClientCallback
            implements AsyncHttpServer.WebSocketRequestCallback {
        @Override
        public void onConnected(final WebSocket ws,
                                AsyncHttpServerRequest request) {
            sockets.add(ws);

            ws.setClosedCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    if (ex!=null) {
                        Log.e(getClass().getSimpleName(),
                                "Exception with WebSocket", ex);
                    }

                    sockets.remove(ws);
                }
            });
        }
    }

    private static class AssetTemplateLoader
            extends AbstractTemplateLoader {
        private final AssetManager mgr;

        AssetTemplateLoader(AssetManager mgr) {
            this.mgr=mgr;
        }

        @Override
        public TemplateSource sourceAt(String s) throws IOException {
            return(new StringTemplateSource(s, convertStreamToStringOutput(mgr.open(s))));
        }
    }

    public static String convertStreamToStringOutput(final InputStream is) throws IOException {
        final char[] buffer=new char[1024];
        final StringBuilder out=new StringBuilder();
        final InputStreamReader in=new InputStreamReader(is, "UTF-8");

        while (true) {
            int rsz=in.read(buffer, 0, buffer.length);
            if (rsz < 0)
                break;
            out.append(buffer, 0, rsz);
        }

        return(out.toString());
    }

    public static class ServerStartedEvent {
        private ArrayList<String> urls=new ArrayList<String>();

        void addUrl(String url) {
            urls.add(url);
        }

        public ArrayList<String> getUrls() {
            return (urls);
        }
    }

    public static class ServerStoppedEvent {

    }

    public static class ServerStartRejectedEvent {

    }
}

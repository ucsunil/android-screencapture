package com.screens.capture;

import android.app.ListActivity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;

import com.screens.capture.services.AbstractScreenCaptureServerService;
import com.screens.capture.services.ScreenViewerService;

import de.greenrobot.event.EventBus;

public class MainActivity extends ListActivity {

    private static final int REQUEST_SCREENSHOT=59706;
    private MenuItem start, stop;
    private MediaProjectionManager mgr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window=getWindow();

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.setStatusBarColor(
                getResources().getColor(R.color.colorPrimaryDark));

        mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        EventBus.getDefault().registerSticky(this);
    }

    @Override
    protected void onPause() {
        EventBus.getDefault().unregister(this);

        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.actions, menu);

        start=menu.findItem(R.id.start);
        stop=menu.findItem(R.id.stop);

        AbstractScreenCaptureServerService.ServerStartedEvent event=
                EventBus.getDefault().getStickyEvent(AbstractScreenCaptureServerService.ServerStartedEvent.class);

        if (event!=null) {
            handleStartEvent(event);
        }

        return(super.onCreateOptionsMenu(menu));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId()==R.id.start) {
            startActivityForResult(mgr.createScreenCaptureIntent(),
                    REQUEST_SCREENSHOT);
        }
        else {
            stopService(new Intent(this, ScreenViewerService.class));
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        if (requestCode==REQUEST_SCREENSHOT) {
            if (resultCode==RESULT_OK) {
                Intent intent=
                        new Intent(this, ScreenViewerService.class)
                                .putExtra(ScreenViewerService.RESULT_CODE,
                                        resultCode)
                                .putExtra(ScreenViewerService.RESULT_INTENT,
                                        data);

                startService(intent);
            }
        }
    }

    public void onEventMainThread(AbstractScreenCaptureServerService.ServerStartedEvent event) {
        if (start!=null) {
            handleStartEvent(event);
        }
    }

    public void onEventMainThread(AbstractScreenCaptureServerService.ServerStoppedEvent event) {
        if (start != null) {
            start.setVisible(true);
            stop.setVisible(false);
            setListAdapter(null);
        }
    }

    private void handleStartEvent(AbstractScreenCaptureServerService.ServerStartedEvent event) {
        start.setVisible(false);
        stop.setVisible(true);

        setListAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, event.getUrls()));
    }
}

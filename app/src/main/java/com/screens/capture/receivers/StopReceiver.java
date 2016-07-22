package com.screens.capture.receivers;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.screens.capture.R;

public class StopReceiver extends BroadcastReceiver {
    public StopReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context.getString(R.string.service_action))
                .setPackage(context.getPackageName());

        PackageManager manager = context.getPackageManager();
        for(ResolveInfo ri : manager.queryIntentServices(i, 0)) {
            ComponentName name = new ComponentName(ri.serviceInfo.applicationInfo.packageName,
                    ri.serviceInfo.name);
            Intent stop = new Intent().setComponent(name);
            context.stopService(stop);
        }
    }
}

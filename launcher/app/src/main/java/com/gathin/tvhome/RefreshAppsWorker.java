package com.gathin.tvhome;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Simple worker that refreshes the app list in the background. */
public class RefreshAppsWorker extends Worker {
    public RefreshAppsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Trigger a reload of the app list. The MainActivity registers a broadcast receiver for this.
        // For now we just send a broadcast that the MainActivity can listen to.
        Context ctx = getApplicationContext();
        ctx.sendBroadcast(new android.content.Intent("com.gathin.tvhome.ACTION_REFRESH_APPS"));
        return Result.success();
    }
}

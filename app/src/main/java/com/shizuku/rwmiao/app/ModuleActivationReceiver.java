package com.shizuku.rwmiao.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.shizuku.rwmiao.config.SettingsContract;

public final class ModuleActivationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SettingsContract.ACTION_MODULE_HEARTBEAT.equals(intent.getAction())) {
            return;
        }
        context.getSharedPreferences(SettingsContract.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(SettingsContract.KEY_MODULE_LAST_ACTIVE, System.currentTimeMillis())
                .putString(
                        SettingsContract.KEY_MODULE_LAST_PACKAGE,
                        intent.getStringExtra(SettingsContract.ARG_MODULE_PACKAGE))
                .apply();
        ModuleStatusBus.dispatch();
    }
}

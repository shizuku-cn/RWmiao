package com.shizuku.rwmiao.module;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.shizuku.rwmiao.BuildConfig;
import com.shizuku.rwmiao.config.SettingsContract;

public final class ModuleActivationReporter {
    private static final ComponentName RECEIVER = new ComponentName(
            BuildConfig.APPLICATION_ID,
            BuildConfig.APPLICATION_ID + ".app.ModuleActivationReceiver");

    private ModuleActivationReporter() {
    }

    public static void report(Context context, String targetPackage) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(SettingsContract.ACTION_MODULE_HEARTBEAT)
                    .setComponent(RECEIVER)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(SettingsContract.ARG_MODULE_PACKAGE, targetPackage);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}

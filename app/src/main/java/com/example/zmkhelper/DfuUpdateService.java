package com.example.zmkhelper;

import android.app.Activity;

import no.nordicsemi.android.dfu.DfuBaseService;

public final class DfuUpdateService extends DfuBaseService {
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return MainActivity.class;
    }

    @Override
    protected boolean isDebug() {
        return false;
    }
}

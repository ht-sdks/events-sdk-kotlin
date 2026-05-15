package com.hightouch.analytics.javaandroidapp;

import android.app.Application;

import com.hightouch.analytics.kotlin.android.AndroidAnalyticsKt;
import com.hightouch.analytics.kotlin.core.Analytics;

import kotlin.Unit;

public class MainApplication extends Application {
    private Analytics mainAnalytics;

    @Override
    public void onCreate() {
        super.onCreate();
        mainAnalytics = AndroidAnalyticsKt.Analytics(BuildConfig.SEGMENT_WRITE_KEY, getApplicationContext(), configuration -> {
            configuration.setFlushAt(1);
            return Unit.INSTANCE;
        });
    }
}

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.axolotl.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class DigitalWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface THIN_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Engine> mWeakReference;

        public EngineHandler(DigitalWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            DigitalWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        public static final String TAG = "WearClient";
        private static final String WEATHER_PATH = "/weather";
        private static final String INITIAL_PATH = "/initial";
        private static final String RES_ID = "resId";
        private static final String MIN_TEMP = "minTemp";
        private static final String MAX_TEMP = "maxTemp";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mIconPaint;
        Paint mMaxPaint;
        Paint mMinPaint;

        private Bitmap mIconBitmap;
        private Bitmap mGrayIconBitmap;
        private GoogleApiClient mGoogleApiClient;

        private String mMinTem;
        private String mMaxTem;
        private int mResId = -1;

        boolean mAmbient;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;

        float mTimeHight;

        SimpleDateFormat mShortenedDateFormat;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            /* initialize your watch face */
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(DigitalWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(DigitalWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = DigitalWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mTimeHight = resources.getDimension(R.dimen.time_string_hight);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createTextPaint(resources.getColor(R.color.date_text));
            mDatePaint.setTypeface(THIN_TYPEFACE);
            mLinePaint = createLinePaint(resources.getColor(R.color.digital_text));
            mMaxPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMinPaint = createTempPaint(resources.getColor(R.color.date_text));
            mIconPaint = createIconPaint();


            if (mResId != -1) {
                mIconBitmap = BitmapFactory.decodeResource(getResources(), mResId);
            }

            mTime = new Time();
            mShortenedDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");

        }


        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (mIconBitmap != null) {
                int iconSize = (int) getResources().getDimension(R.dimen.icon_size);
                mIconBitmap = Bitmap.createScaledBitmap(mIconBitmap, iconSize, iconSize, true);
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createIconPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            return paint;
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTempPaint(int textColor) {
            Paint paint = new Paint();
            paint.setTypeface(THIN_TYPEFACE);
            paint.setColor(textColor);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLinePaint(int lineColor) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(lineColor);
            paint.setStrokeWidth(0.2f);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            /* the watch face became visible or invisible */
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            DigitalWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            DigitalWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = DigitalWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
//            float textSize = resources.getDimension(isRound
//                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textSize = resources.getDimension(R.dimen.digital_text_size);
            float tempMaxSize = resources.getDimension(R.dimen.temp_text_size);
            float tempMinSize = resources.getDimensionPixelSize(R.dimen.temp_min_size);
            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mMaxPaint.setTextSize(tempMaxSize);
            mMinPaint.setTextSize(tempMinSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            /* get device features (burn-in, low-bit ambient) */
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            /* the time changed */
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            /* the wearable switched between modes */
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mLinePaint.setAntiAlias(!inAmbientMode);
                    mMinPaint.setAntiAlias(!inAmbientMode);
                    mMaxPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            /* draw your watch face */
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                mDatePaint.setColor(Color.WHITE);
                mLinePaint.setColor(Color.WHITE);
                mMinPaint.setColor(Color.WHITE);
            } else {
                int color = getResources().getColor(R.color.date_text);
                mDatePaint.setColor(color);
                mLinePaint.setColor(color);
                mMinPaint.setColor(color);
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
//            String text = mAmbient
//                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
//                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, bounds.centerX() - (mTextPaint.measureText(text)) / 2, bounds.centerY() - 55, mTextPaint);

            //draw date string
            float currentHigh = bounds.centerY() - 17;
            String date = mShortenedDateFormat.format(mTime.toMillis(true));
            float halfDateWidth = mDatePaint.measureText(date) / 2;
            canvas.drawText(date, bounds.centerX() - halfDateWidth, currentHigh, mDatePaint);

            currentHigh = bounds.centerY() + 10;
            canvas.drawLine(bounds.centerX() - 25, currentHigh, bounds.centerX() + 25, currentHigh, mLinePaint);

            currentHigh = bounds.centerY() + 23;
            if (!isInAmbientMode() && mIconBitmap != null) {
                float iconX = bounds.centerX() - halfDateWidth - 12;
                canvas.drawBitmap(mIconBitmap, iconX, currentHigh, mIconPaint);
            }

            currentHigh += 44;
            if (!TextUtils.isEmpty(mMaxTem) && !TextUtils.isEmpty(mMinTem)) {
                float halfMaxWidth = mMaxPaint.measureText(mMaxTem) / 2;
                canvas.drawText(mMaxTem, bounds.centerX() - halfMaxWidth + 3, currentHigh, mMaxPaint);
                canvas.drawText(mMinTem, bounds.centerX() + halfMaxWidth + 8, currentHigh, mMinPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.i(TAG, "client connected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            requestInitialData();
        }

        private void requestInitialData() {
            PutDataMapRequest dataMap = PutDataMapRequest.create(INITIAL_PATH);
            dataMap.getDataMap().putLong("timestamp", System.currentTimeMillis());
            PutDataRequest request = dataMap.asPutDataRequest();
            request.setUrgent();
            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            Log.i(TAG, "Sending init signal was successful: " + dataItemResult.getStatus()
                                    .isSuccess());
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i(TAG, "onConnectionSuspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.i(TAG, "onConnectionFailed: " + connectionResult);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.i(TAG, "receive data");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if (WEATHER_PATH.equals(path)) {
                        mResId = Utility.getArtResourceForWeatherCondition(dataMap.getInt(RES_ID));
                        if (mResId != -1) {
                            mIconBitmap = BitmapFactory.decodeResource(getResources(), mResId);
                            int iconSize = (int) getResources().getDimension(R.dimen.icon_size);
                            mIconBitmap = Bitmap.createScaledBitmap(mIconBitmap, iconSize, iconSize, true);
                        }
                        mMaxTem = dataMap.getString(MAX_TEMP);
                        mMinTem = dataMap.getString(MIN_TEMP);
                        Log.i(TAG, "receive data max:" + mMaxTem);
                        invalidate();
                    }
                }
            }
        }


    }
}

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

package com.example.android.sunshine.app;

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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResolvingResultCallbacks;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
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
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        private static final String WEATHER_PATH = "/weather";

        private static final String COLON_STRING = ":";

        private final GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;
        private Paint mBackgroundPaint;
        private Paint mHourPaint;
        private Paint mMinuteSecondPaint;
        private Paint mSecondaryTextPaint;
        private boolean mAmbient;

        private Date mDate;
        private Calendar mCalendar;
        private double mHighTemperature;
        private double mLowTemperature;
        private Bitmap mWeatherImage;

        private SimpleDateFormat mDayOfWeekFormat;
        private java.text.DateFormat mDateFormat;

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
//                mTime.clear(intent.getStringExtra("time-zone"));
//                mTime.setToNow();
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        float timeLineHeight;
        float dateLineHeight;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mHourPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

            timeLineHeight = mHourPaint.getFontSpacing();
            mMinuteSecondPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMinuteSecondPaint.setTypeface(Typeface.SANS_SERIF);
            mSecondaryTextPaint = createTextPaint(resources.getColor(R.color.primary_light));
            dateLineHeight = mSecondaryTextPaint.getFontSpacing();

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = DateFormat.getMediumDateFormat(SunshineWatchFace.this);
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            final float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            final float secondaryTextSize = resources.getDimension(isRound
                    ? R.dimen.secondary_text_size_round : R.dimen.secondary_text_size);

            mHourPaint.setTextSize(textSize);
            timeLineHeight = mHourPaint.getFontMetrics().leading;
            mMinuteSecondPaint.setTextSize(textSize);
            timeLineHeight = Math.max(timeLineHeight, mMinuteSecondPaint.getFontMetrics().leading);
            mSecondaryTextPaint.setTextSize(secondaryTextSize);
            dateLineHeight = mSecondaryTextPaint.getFontSpacing();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mSecondaryTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            final long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            float x = 0;
            float yOffset = mYOffset;

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            final boolean shouldDrawColons = (now % 1000) < 500;

            // Let's create one string and draw it all at once to minimize umber of draw calls
            // except for hours - it's bold
            final String hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
//            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);


            final StringBuilder timeString = new StringBuilder();
            if (isInAmbientMode() || shouldDrawColons) {
                timeString.append(COLON_STRING);
            } else {
                x += mMinuteSecondPaint.measureText(COLON_STRING);
            }
            timeString.append(formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE)));
            // Draw seconds only in interactive mode
            if (!isInAmbientMode()) {
                if (shouldDrawColons) {
                    timeString.append(COLON_STRING);
                }
                timeString.append(formatTwoDigitNumber(mCalendar.get(Calendar.SECOND)));
            }
            final float fullTextWidth = mHourPaint.measureText(hourString)
                    + mMinuteSecondPaint.measureText(timeString.toString());
            final float startOffset = (canvas.getWidth() - fullTextWidth) / 2;
            canvas.drawText(hourString, startOffset, yOffset, mHourPaint);
            canvas.drawText(timeString.toString(), startOffset + x, yOffset, mMinuteSecondPaint);

            final String date = mDayOfWeekFormat.format(mDate) + ", " + mDateFormat.format(mDate);
            final float fullDateTextWidth = mSecondaryTextPaint.measureText(date);
            final float startDateTextOffset = (canvas.getWidth() - fullDateTextWidth) / 2;
            yOffset = mYOffset + timeLineHeight + dateLineHeight;
            canvas.drawText(date,
                    startDateTextOffset,
                    yOffset,
                    mSecondaryTextPaint);

            final float lineWidth = canvas.getWidth() / 3.f;
            yOffset += dateLineHeight;
            canvas.drawLine(canvas.getWidth() / 2 - lineWidth / 2, yOffset, canvas.getWidth() / 2 + lineWidth / 2, yOffset, mSecondaryTextPaint);
            yOffset += dateLineHeight;
            x = canvas.getWidth() / 2;
            canvas.drawText(String.format("%1.0f", mHighTemperature), x, yOffset, mHourPaint);
            x += mHourPaint.measureText(String.format("%1.0f", mHighTemperature));
            canvas.drawText(String.format("%1.0f", mLowTemperature), x, yOffset, mHourPaint);
            if (mWeatherImage != null) {
                canvas.drawBitmap(mWeatherImage, canvas.getWidth() / 2 - mWeatherImage.getWidth(), yOffset, mHourPaint);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
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

        private void updateConfigDataItemAndUiOnStartup() {
            final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString("uuid", UUID.randomUUID().toString());
            final PutDataRequest request = putDataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d("SunshineWatchFace", "Failed asking phone for weather data");
                            } else {
                                Log.d("SunshineWatchFace", "Successfully asked for weather data");
                            }
                        }
                    });
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d("SunshineWatch", "onDataChanged");
            for (final DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    final DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    final String path = dataEvent.getDataItem().getUri().getPath();
                    if (path.equals("weather-info")) {
                        mHighTemperature = dataMap.getDouble("high");
                        mLowTemperature = dataMap.getDouble("low");
                        final Asset weatherIconAsset = dataMap.getAsset("weatherImage");
                        mWeatherImage = loadBitmapFromAsset(weatherIconAsset);
                    }
                }
            }
            invalidate();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.d("SunshineWatch", "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateConfigDataItemAndUiOnStartup();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("SunshineWatch", "onConnectionSuspended " + i);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d("SunshineWatch", "onConnectionFailed");
        }

        private final long TIMEOUT_MS = 1000;
        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
//                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }
    }
}

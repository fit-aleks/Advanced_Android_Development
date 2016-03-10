package com.example.android.sunshine.app.wear;

import android.util.Log;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;
/**
 * Created by alexander on 10.03.16.
 */
public class WearWeatherService extends WearableListenerService {


    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d("WearWeatherService", "onDataChanged");
        for (DataEvent dataEvent : dataEvents) {
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                final String path = dataEvent.getDataItem().getUri().getPath();
                if (path.equals("/weather")) {
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}

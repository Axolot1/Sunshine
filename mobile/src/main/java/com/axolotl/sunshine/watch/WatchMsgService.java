package com.axolotl.sunshine.watch;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.axolotl.sunshine.R;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;

public class WatchMsgService extends WearableListenerService {

    private static final String INITIAL_PATH = "/initial";
    private static final String TAG = "syncWatch";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WatchMsgService onCreate");
    }


    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.i(TAG, "onDataChanged");
        for(DataEvent dataEvent : dataEvents){
            if(dataEvent.getType() == DataEvent.TYPE_CHANGED){
                String path = dataEvent.getDataItem().getUri().getPath();
                if(INITIAL_PATH.equals(path)){
                    Log.i(TAG, "receive initial signal");
                    startService(new Intent(getApplicationContext(), SyncDataService.class));
                }
            }
        }
    }

    @Override
    public void onPeerConnected(Node peer) {
        super.onPeerConnected(peer);
        String id = peer.getId();
        String name = peer.getDisplayName();

        Log.d(TAG, "Connected peer name & ID: " + name + "|" + id);
    }
}

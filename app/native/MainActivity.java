package com.daglas.usblink;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HandsFreePlugin.class);
        super.onCreate(savedInstanceState);
    }
}

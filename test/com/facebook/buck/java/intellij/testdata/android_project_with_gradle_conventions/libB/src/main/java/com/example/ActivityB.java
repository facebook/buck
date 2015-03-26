package com.example;

import android.app.Activity;
import android.os.Bundle;

public class ActivityB extends Activity {

    public static final String dummyString = "testB";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }
}

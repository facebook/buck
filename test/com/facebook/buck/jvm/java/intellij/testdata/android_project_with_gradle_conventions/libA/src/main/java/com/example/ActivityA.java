package com.example;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public class ActivityA extends Activity {

    public static final String dummyString = "testA";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }
}

package com.example;

import android.app.Activity;
import android.os.Bundle;

import android.view.View;
import com.example.ActivityA;
import com.example.ActivityB;
import android.widget.Button;
import android.widget.Toast;

public class MyActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        initButton(ActivityA.dummyString, R.id.buttonToastA);
        initButton(ActivityB.dummyString, R.id.buttonToastB);
    }

    private void initButton(final String text, int buttonId) {
        Button button = (Button) findViewById(buttonId);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
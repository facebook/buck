package com.sample.lib

import com.sample.R

import android.app.Activity
import android.os.Bundle

class Sample : Activity() {
    override fun onCreate(savedInstanceState: Bundle) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.top_layout)
    }
}

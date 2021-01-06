package com.sample.lib

import com.sample.R

import android.app.Activity
import android.os.Bundle

// Inline function, which is called by a depending library, makes sure that our ABI jars are at
// least somewhat Kotlin-aware (or else disabled for Kotlin rules)
inline fun getActivity(): Activity {
    return Sample()
}

class Sample : Activity() {
    override fun onCreate(savedInstanceState: Bundle) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.top_layout)
        getActivity()
    }
}

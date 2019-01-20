package com.sample.depending

import android.app.Activity
import com.sample.lib.getActivity

fun test(): Activity {
    return getActivity()
}

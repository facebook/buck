/*
 * Copyright 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.buck.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class App extends Activity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setTitle(R.string.app_name);
    setContentView(R.layout.hello);
    TextView textView = (TextView) findViewById(R.id.hello_text);
    try {
      String message = new Hello().getHelloString();
      textView.setText(message);
    } catch (Exception e) {
      textView.setText(String.format("Unable to load jni library! %s", e.getMessage()));
    }
  }
}

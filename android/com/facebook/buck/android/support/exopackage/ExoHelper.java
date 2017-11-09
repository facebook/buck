/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android.support.exopackage;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

/** Support and Utility Functions for Exopackage, including hotswap helper functions */
public class ExoHelper {
  private static final List<OnModulesChangedCallback> sCallbacks =
      new ArrayList<OnModulesChangedCallback>();

  /**
   * Restart the given activity. The same intent will be used to launch the new activity, preserving
   * actions, extras, etc. Must be run on the main thread.
   *
   * @param activity the activity to restart
   */
  public static void restartActivity(Activity activity) {
    assertIsUiThread();
    final Intent intent = activity.getIntent();
    activity.startActivity(intent);
    activity.finish();
  }

  /**
   * Create an Intent to launch the component with the given classname. This is a simple wrapper
   * around Intent#setComponent with the given params.
   *
   * @param context the current context
   * @param className the name of the class targeted by the Intent
   * @return an Intent suitable for launching the component with the given className
   */
  public static Intent intentForClassName(Context context, String className) {
    final Intent intent = new Intent();
    intent.setComponent(new ComponentName(context, className));
    return intent;
  }

  /**
   * Add a callback which will fire whenever code definitions have changed in the app. This callback
   * will be executed on the main thread.
   *
   * @param callback the callback to add
   */
  public static void addOnModulesChangedCallback(OnModulesChangedCallback callback) {
    synchronized (sCallbacks) {
      sCallbacks.add(callback);
    }
  }

  /**
   * Remove a previously added callback
   *
   * @param callback the callback to remove
   */
  public static void removeOnModulesChangedCallback(OnModulesChangedCallback callback) {
    synchronized (sCallbacks) {
      sCallbacks.remove(callback);
    }
  }

  /**
   * Trigger all callbacks which have registered to receive notifications when the module
   * definitions have changed on disk
   */
  static void triggerCallbacks() {
    synchronized (sCallbacks) {
      for (OnModulesChangedCallback mCallback : sCallbacks) {
        mCallback.onModulesChanged();
      }
    }
  }

  private static void assertIsUiThread() {
    if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
      throw new RuntimeException("This method must be called from the UI thread");
    }
  }
}

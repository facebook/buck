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

import java.util.ArrayList;
import java.util.List;

/** Support and Utility Functions for Exopackage, including hotswap helper functions */
public class ExoHelper {
  private static final List<OnModulesChangedCallback> sCallbacks =
      new ArrayList<OnModulesChangedCallback>();

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
}

/*
 * Copyright 2014-present Facebook, Inc.
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

import android.app.Application;
import android.content.res.Configuration;

/**
 * Empty implementation of {@link ApplicationLike}.
 */
public class DefaultApplicationLike implements ApplicationLike {
  public DefaultApplicationLike() {}

  @SuppressWarnings("unused")
  public DefaultApplicationLike(Application application) {}

  @Override
  public void onCreate() {}

  @Override
  public void onLowMemory() {}

  @Override
  public void onTrimMemory(int level) {}

  @Override
  public void onTerminate() {}

  @Override
  public void onConfigurationChanged(Configuration newConfig) {}
}

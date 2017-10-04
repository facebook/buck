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

package com.facebook.buck.android.toolchain;

import com.facebook.buck.toolchain.BaseToolchain;
import java.util.Optional;

/** Provides access to Android toolchain components such as Android SDK and Android NDK */
public abstract class AndroidToolchain extends BaseToolchain {

  public static final String DEFAULT_NAME = "android";

  public abstract AndroidSdk getAndroidSdk();

  public abstract Optional<AndroidNdk> getAndroidNdk();
}

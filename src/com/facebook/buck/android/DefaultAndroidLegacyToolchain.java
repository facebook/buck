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

package com.facebook.buck.android;

import com.google.common.base.Supplier;

public class DefaultAndroidLegacyToolchain implements AndroidLegacyToolchain {

  private final Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier;
  private final AndroidDirectoryResolver androidDirectoryResolver;

  public DefaultAndroidLegacyToolchain(
      Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier,
      AndroidDirectoryResolver androidDirectoryResolver) {
    this.androidPlatformTargetSupplier = androidPlatformTargetSupplier;
    this.androidDirectoryResolver = androidDirectoryResolver;
  }

  @Override
  public Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier() {
    return androidPlatformTargetSupplier;
  }

  @Override
  public AndroidDirectoryResolver getAndroidDirectoryResolver() {
    return androidDirectoryResolver;
  }

  @Override
  public AndroidPlatformTarget getAndroidPlatformTarget() {
    return androidPlatformTargetSupplier.get();
  }
}

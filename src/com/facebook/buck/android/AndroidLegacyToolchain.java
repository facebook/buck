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

import com.facebook.buck.toolchain.Toolchain;
import com.google.common.base.Supplier;

/**
 * Toolchain that provides access to existing Android classes.
 *
 * <p>It only is used during transition from adhoc approach of using random classes to using
 * toolchains.
 *
 * <p>This toolchain should be used when access to {@code AndroidDirectoryResolver} or {@code
 * AndroidPlatformTarget} is needed.
 *
 * <p>This class is intended to be used only to get access to mentioned classes hence "legacy" in
 * name. Eventually this toolchain and its members will be replaced by {@code AndroidSdk} and {@code
 * AndroidNdk} toolchains.
 */
public interface AndroidLegacyToolchain extends Toolchain {

  String DEFAULT_NAME = "legacy-android";

  Supplier<AndroidPlatformTarget> getAndroidPlatformTargetSupplier();

  AndroidDirectoryResolver getAndroidDirectoryResolver();

  AndroidPlatformTarget getAndroidPlatformTarget();
}

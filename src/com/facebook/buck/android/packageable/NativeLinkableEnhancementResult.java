/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.packageable;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

/**
 * A simple data object to hold mapping from {@link APKModule} to {@link NativeLinkable} for both
 * normal libs and asset libs. Used for AndroidBinary graph enhancement.
 */
@BuckStyleValue
public interface NativeLinkableEnhancementResult {

  static NativeLinkableEnhancementResult of(
      Multimap<? extends APKModule, ? extends NativeLinkable> nativeLinkables,
      Multimap<? extends APKModule, ? extends NativeLinkable> nativeLinkableAssets) {
    return ImmutableNativeLinkableEnhancementResult.of(nativeLinkables, nativeLinkableAssets);
  }

  /** Native libraries by platform. */
  ImmutableMultimap<APKModule, NativeLinkable> getNativeLinkables();

  /** Native libraries to be packaged as assets by platform. */
  ImmutableMultimap<APKModule, NativeLinkable> getNativeLinkableAssets();
}

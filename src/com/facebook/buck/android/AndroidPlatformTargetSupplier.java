/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.log.Logger;
import com.google.common.base.Supplier;
import java.util.Optional;
import javax.annotation.Nullable;

// TODO(mbolin): Only one such Supplier should be created per Cell per Buck execution.
// Currently, only one Supplier is created per Buck execution because Main creates the Supplier
// and passes it from above all the way through, but it is not parameterized by Cell.
//
// TODO(mbolin): Every build rule that uses AndroidPlatformTarget must include the result
// of its getCacheName() method in its RuleKey.

public class AndroidPlatformTargetSupplier implements Supplier<AndroidPlatformTarget> {
  private static final Logger LOG = Logger.get(AndroidPlatformTargetSupplier.class);
  private final AndroidDirectoryResolver androidDirectoryResolver;
  private final AndroidBuckConfig androidBuckConfig;

  @Nullable private AndroidPlatformTarget androidPlatformTarget;
  @Nullable private RuntimeException exception;

  public AndroidPlatformTargetSupplier(
      final AndroidDirectoryResolver androidDirectoryResolver,
      final AndroidBuckConfig androidBuckConfig) {
    this.androidDirectoryResolver = androidDirectoryResolver;
    this.androidBuckConfig = androidBuckConfig;
  }

  @Override
  public AndroidPlatformTarget get() {
    if (androidPlatformTarget != null) {
      return androidPlatformTarget;
    } else if (exception != null) {
      throw exception;
    }

    String androidPlatformTargetId;
    Optional<String> target = androidBuckConfig.getAndroidTarget();
    if (target.isPresent()) {
      androidPlatformTargetId = target.get();
    } else {
      androidPlatformTargetId = AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET;
      LOG.debug("No Android platform target specified. Using default: %s", androidPlatformTargetId);
    }

    try {
      androidPlatformTarget =
          AndroidPlatformTarget.getTargetForId(
              androidPlatformTargetId,
              androidDirectoryResolver,
              androidBuckConfig.getAaptOverride(),
              androidBuckConfig.getAapt2Override());
      return androidPlatformTarget;
    } catch (RuntimeException e) {
      exception = e;
      throw e;
    }
  }
}

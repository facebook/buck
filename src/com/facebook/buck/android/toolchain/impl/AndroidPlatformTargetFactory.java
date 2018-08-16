/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.AndroidBuildToolsLocation;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.environment.Platform;
import java.util.Optional;

public class AndroidPlatformTargetFactory implements ToolchainFactory<AndroidPlatformTarget> {

  private final Logger LOG = Logger.get(AndroidPlatformTargetFactory.class);

  @Override
  public Optional<AndroidPlatformTarget> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    AndroidBuckConfig androidBuckConfig =
        new AndroidBuckConfig(context.getBuckConfig(), Platform.detect());

    String androidPlatformTargetId;
    Optional<String> target = androidBuckConfig.getAndroidTarget();
    if (target.isPresent()) {
      androidPlatformTargetId = target.get();
    } else {
      androidPlatformTargetId = AndroidPlatformTarget.DEFAULT_ANDROID_PLATFORM_TARGET;
      LOG.debug("No Android platform target specified. Using default: %s", androidPlatformTargetId);
    }

    try {
      return Optional.of(
          AndroidPlatformTargetProducer.getTargetForId(
              androidPlatformTargetId,
              toolchainProvider.getByName(
                  AndroidBuildToolsLocation.DEFAULT_NAME, AndroidBuildToolsLocation.class),
              toolchainProvider.getByName(
                  AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class),
              androidBuckConfig.getAaptOverride(),
              androidBuckConfig.getAapt2Override()));
    } catch (HumanReadableException e) {
      throw ToolchainInstantiationException.wrap(e);
    }
  }
}

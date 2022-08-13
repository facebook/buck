/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.DefaultAndroidInstallerPrinter;
import com.facebook.buck.android.device.TargetDeviceOptions;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.step.AdbOptions;
import java.util.Optional;
import java.util.function.Supplier;

public class AndroidDevicesHelperFactory {
  protected AndroidDevicesHelperFactory() {}

  public static AndroidDevicesHelper get(
      ToolchainProvider toolchainProvider,
      Supplier<ExecutionContext> contextSupplier,
      BuckConfig buckConfig,
      AdbOptions adbOptions,
      TargetDeviceOptions targetDeviceOptions) {
    AdbConfig adbConfig = buckConfig.getView(AdbConfig.class);
    Optional<AndroidPlatformTarget> target =
        toolchainProvider.getByNameIfPresent(
            AndroidPlatformTarget.DEFAULT_NAME,
            UnconfiguredTargetConfiguration.INSTANCE,
            AndroidPlatformTarget.class);
    Optional<String> adbExecutable = Optional.empty();
    if (target.isPresent()) {
      adbExecutable = Optional.of(target.get().getAdbExecutable().toString());
    }
    return new AdbHelper(
        adbOptions,
        targetDeviceOptions,
        contextSupplier,
        new DefaultAndroidInstallerPrinter(contextSupplier),
        adbExecutable,
        adbConfig.getRestartAdbOnFailure(),
        adbConfig.getSkipInstallMetadata(),
        adbConfig.getAlwaysUseJavaAgent(),
        adbConfig.isZstdCompressionEnabled(),
        adbConfig.getAgentPortBase());
  }
}

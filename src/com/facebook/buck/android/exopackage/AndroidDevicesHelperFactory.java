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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;

public class AndroidDevicesHelperFactory {
  protected AndroidDevicesHelperFactory() {}

  public static AndroidDevicesHelper get(ExecutionContext context, boolean restartOnFailure) {
    Preconditions.checkArgument(context.getAdbOptions().isPresent());
    Preconditions.checkArgument(context.getTargetDeviceOptions().isPresent());
    return new AdbHelper(
        context.getAdbOptions().get(),
        context.getTargetDeviceOptions().get(),
        () -> context,
        restartOnFailure);
  }
}

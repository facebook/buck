/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import javax.annotation.Nullable;

public abstract class KotlinTestAssumptions {
  public static void assumeUnixLike() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
  }

  public static void assumeCompilerAvailable(@Nullable BuckConfig config) {
    Throwable exception = null;
    try {
      new KotlinBuckConfig(config == null ? FakeBuckConfig.builder().build() : config);
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }
}

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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;

import java.io.IOException;

public abstract class KotlinTestAssumptions {
  public static void assumeUnixLike() {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
  }

  public static void assumeCompilerAvailable() throws IOException {
    Throwable exception = null;
    try {
      new KotlinBuckConfig(FakeBuckConfig.builder().build()).getPathToCompilerJar();
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }
}

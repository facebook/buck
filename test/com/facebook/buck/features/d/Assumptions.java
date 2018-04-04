/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.d;

import static org.junit.Assume.assumeNoException;

import com.facebook.buck.config.FakeBuckConfig;

abstract class Assumptions {
  public static void assumeDCompilerUsable() {
    // The methods that we use to figure out how to invoke the D compiler throw when
    // they cannot determine how to invoke the compiler. Conversely, if they don't throw,
    // the compiler should be usable. So we check that none of them throw.
    Throwable exception = null;
    try {
      DBuckConfig dBuckConfig = new DBuckConfig(FakeBuckConfig.builder().build());
      dBuckConfig.getDCompiler();
      dBuckConfig.getBaseCompilerFlags();
      dBuckConfig.getLinkerFlags();
    } catch (Exception e) {
      exception = e;
    }
    assumeNoException(exception);
  }
}

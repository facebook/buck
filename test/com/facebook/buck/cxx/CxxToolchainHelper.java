/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import com.facebook.buck.testutil.AbstractWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;

public class CxxToolchainHelper {

  /**
   * Copies a custom toolchain into {@code root/cxx_toolchain/toolchain} with tools in {@code
   * cxx_toolchain/tools}. This toolchain just appends inputs together to form outputs (see @{link
   * CxxToolchainIntegrationTest} for examples).
   *
   * <p>There's a toolchain with "working" tools with name "good" and one with broken tools named
   * "bad".
   */
  public static void addCxxToolchainToWorkspace(AbstractWorkspace workspace) throws IOException {
    workspace.addTemplateToWorkspace(
        TestDataHelper.getTestDataScenario(new CxxToolchainHelper(), "cxx_toolchain"));
  }
}

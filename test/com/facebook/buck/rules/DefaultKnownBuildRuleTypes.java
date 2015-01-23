/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.python.ImmutablePythonVersion;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ImmutableProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Paths;

public class DefaultKnownBuildRuleTypes {

  private DefaultKnownBuildRuleTypes() {
    // Utility class.
  }

  private static final ProcessExecutorParams XCODE_SELECT_PARAMS =
      ImmutableProcessExecutorParams.builder()
          .setCommand(ImmutableList.of("xcode-select", "--print-path"))
          .build();
  private static final FakeProcess XCODE_SELECT_PROCESS = new FakeProcess(0, "/path/to/xcode", "");

  public static KnownBuildRuleTypes getDefaultKnownBuildRuleTypes(ProjectFilesystem filesystem)
      throws InterruptedException, IOException {
    BuckConfig config = new FakeBuckConfig(filesystem);

    return KnownBuildRuleTypes.createInstance(
        config,
        new FakeProcessExecutor(ImmutableMap.of(XCODE_SELECT_PARAMS, XCODE_SELECT_PROCESS)),
        new FakeAndroidDirectoryResolver(),
        new PythonEnvironment(Paths.get("fake_python"), ImmutablePythonVersion.of("Python 2.7")));
  }

}

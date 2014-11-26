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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.python.PythonVersion;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.FakeProcessExecutor;

import java.nio.file.Paths;

public class DefaultKnownBuildRuleTypes {

  private DefaultKnownBuildRuleTypes() {
    // Utility class.
  }

  public static KnownBuildRuleTypes getDefaultKnownBuildRuleTypes(ProjectFilesystem filesystem)
      throws InterruptedException {
    BuckConfig config = new FakeBuckConfig(filesystem);

    return KnownBuildRuleTypes.createInstance(
        config,
        new FakeProcessExecutor(),
        new FakeAndroidDirectoryResolver(),
        new PythonEnvironment(Paths.get("fake_python"), new PythonVersion("Python 2.7")));
  }

}

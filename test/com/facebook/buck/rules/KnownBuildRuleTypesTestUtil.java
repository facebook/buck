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
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KnownBuildRuleTypesTestUtil {

  private KnownBuildRuleTypesTestUtil() {
    // Utility class.
  }

  private static final ProcessExecutorParams XCODE_SELECT_PARAMS =
      ProcessExecutorParams.builder()
          .setCommand(ImmutableList.of("xcode-select", "--print-path"))
          .build();
  private static final FakeProcess XCODE_SELECT_PROCESS = new FakeProcess(0, "/path/to/xcode", "");

  private static final ImmutableMap<String, String> PYTHONS =
      ImmutableMap.of(
          "python", "2.6",
          "python2", "2.6",
          "python3", "3.5");

  protected static ImmutableMap<ProcessExecutorParams, FakeProcess> getPythonProcessMap(
      List<String> paths) {
    Set<String> uniquePaths = new HashSet<>(paths);
    ImmutableMap.Builder<ProcessExecutorParams, FakeProcess> processMap = ImmutableMap.builder();
    for (Map.Entry<String, String> python : PYTHONS.entrySet()) {
      for (String path : uniquePaths) {
        for (String extension : new String[] {"", ".exe", ".EXE"}) {
          processMap.put(
              ProcessExecutorParams.builder()
                  .setCommand(
                      ImmutableList.of(path + File.separator + python.getKey() + extension, "-"))
                  .build(),
              new FakeProcess(0, "CPython " + python.getValue().replace('.', ' '), ""));
        }
      }
    }
    return processMap.build();
  }

  @VisibleForTesting
  public static List<String> getPaths(ImmutableMap<String, String> environemnt) {
    String pathEnv = environemnt.get("PATH");
    if (pathEnv == null) {
      return Collections.emptyList();
    }

    return Arrays.asList(pathEnv.split(File.pathSeparator));
  }

  public static KnownBuildRuleTypes getDefaultKnownBuildRuleTypes(
      ProjectFilesystem filesystem, ImmutableMap<String, String> environment)
      throws InterruptedException, IOException {
    BuckConfig config = FakeBuckConfig.builder().setFilesystem(filesystem).build();
    List<String> paths = getPaths(environment);

    return KnownBuildRuleTypes.createInstance(
        config,
        filesystem,
        new FakeProcessExecutor(
            ImmutableMap.<ProcessExecutorParams, FakeProcess>builder()
                .put(XCODE_SELECT_PARAMS, XCODE_SELECT_PROCESS)
                .putAll(getPythonProcessMap(paths))
                .build()),
        new FakeAndroidDirectoryResolver());
  }
}

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

package com.facebook.buck.core.rules.knowntypes;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KnownRuleTypesTestUtil {

  private static final String FAKE_XCODE_DEV_PATH = "/Fake/Path/To/Xcode.app/Contents/Developer";
  static final ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());

  private KnownRuleTypesTestUtil() {
    // Utility class.
  }

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
              new FakeProcess(0, "CPython " + python.getValue(), ""));
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

  static ProcessExecutor createExecutor(TemporaryPaths temporaryFolder) throws IOException {
    Path javac = temporaryFolder.newExecutableFile();
    return createExecutor(javac.toString(), "");
  }

  static ProcessExecutor createExecutor(String javac, String version) {
    Map<ProcessExecutorParams, FakeProcess> processMap = new HashMap<>();

    FakeProcess process = new FakeProcess(0, "", version);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder().setCommand(ImmutableList.of(javac, "-version")).build();
    processMap.put(params, process);

    addXcodeSelectProcess(processMap, FAKE_XCODE_DEV_PATH);

    processMap.putAll(getPythonProcessMap(getPaths(environment)));

    return new FakeProcessExecutor(processMap);
  }

  private static void addXcodeSelectProcess(
      Map<ProcessExecutorParams, FakeProcess> processMap, String xcodeSelectPath) {

    FakeProcess xcodeSelectOutputProcess = new FakeProcess(0, xcodeSelectPath, "");
    ProcessExecutorParams xcodeSelectParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    processMap.put(xcodeSelectParams, xcodeSelectOutputProcess);
  }
}

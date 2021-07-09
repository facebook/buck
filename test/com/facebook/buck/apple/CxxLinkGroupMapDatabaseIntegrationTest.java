/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.apple;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CxxLinkGroupMapDatabaseIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setupWorkspace() {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
  }

  private void runCreateLinkGroupMapDatabaseTestForScenario(
      String scenario, Map<String, String> expectedValues) throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    for (String library : expectedValues.keySet()) {
      BuildTarget target = BuildTargetFactory.newInstance(library);
      Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());
      String json = new String(Files.readAllBytes(compilationDatabase), StandardCharsets.UTF_8);
      assertThat(json, equalTo(expectedValues.get(library)));
    }
  }

  @Test
  public void testCreateLinkGroupMapDatabaseForAppleLibraryInAppWithOneDylib() throws IOException {
    runCreateLinkGroupMapDatabaseTestForScenario(
        "apple_binary_with_link_groups_single_dylib",
        ImmutableMap.of(
            "//Apps/TestApp:ExhaustiveDylib#link-group-map-database,iphonesimulator-x86_64",
            "[\"//Apps/Libs:B\",\"//Apps/Libs:C\"]"));
  }

  @Test
  public void testCreateLinkGroupMapDatabaseForAppleLibraryWithNoDeps() throws IOException {
    runCreateLinkGroupMapDatabaseTestForScenario(
        "apple_binary_with_link_groups_single_dylib",
        ImmutableMap.of("//Apps/Libs:C#link-group-map-database,iphonesimulator-x86_64", "[]"));
  }

  @Test
  public void testCreateLinkGroupMapDatabaseForAppleLibraryInAppWithMultipleDylibs()
      throws IOException {
    runCreateLinkGroupMapDatabaseTestForScenario(
        "apple_binary_with_link_groups_multiples_dylibs",
        ImmutableMap.of(
            "//Apps/TestApp:Dylib1#link-group-map-database,iphonesimulator-x86_64",
            "[\"//Apps/Libs:B\"]",
            "//Apps/TestApp:Dylib2#link-group-map-database,iphonesimulator-x86_64",
            "[\"//Apps/Libs:A\",\"//Apps/Libs:C\"]"));
  }

  @Test
  public void testCreateLinkGroupMapDatabaseForAppleBinaryInAppWithOneUngroupedLinkable()
      throws IOException {
    runCreateLinkGroupMapDatabaseTestForScenario(
        "apple_binary_with_link_groups_single_dylib",
        ImmutableMap.of(
            "//Apps/TestApp:CatchAllApp#link-group-map-database,iphonesimulator-x86_64",
            "[\"//Apps/Libs:Root\",\"//Apps/Libs:A\",\"//Apps/TestApp:CatchAllDylib\"]"));
  }

  @Test
  public void testCreateLinkGroupMapDatabaseForAppleBinaryInAppWithNoUngroupedLinkables()
      throws IOException {
    runCreateLinkGroupMapDatabaseTestForScenario(
        "apple_binary_with_link_groups_multiples_dylibs",
        ImmutableMap.of(
            "//Apps/TestApp:TestApp#link-group-map-database,iphonesimulator-x86_64",
            "[\"//Apps/TestApp:Dylib2\",\"//Apps/TestApp:Dylib1\"]"));
  }
}

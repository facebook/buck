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

package com.facebook.buck.features.supermodule;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SupermoduleTargetGraphIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "graph-validation", tmp);
    workspace.setUp();
  }

  private void validateAttributeMap(JsonNode actual, Map<String, ?> expected) throws IOException {
    JsonNode expectedReread =
        ObjectMappers.READER.readTree(ObjectMappers.WRITER.writeValueAsString(expected));
    assertEquals(expectedReread, actual);
  }

  @Test
  public void jsonOutputForDepGraphIsCorrect() throws IOException {
    Path targetGraphJson = workspace.buildAndReturnOutput("//Apps/TestApp:TestAppSupermoduleGraph");
    JsonNode targetGraph =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(targetGraphJson));
    assertEquals(
        Sets.newHashSet(targetGraph.fieldNames()),
        ImmutableSet.of("//Apps/Libs:A", "//Apps/Libs:B", "//Apps/Libs:C", "//Apps/Libs:Root"));

    validateAttributeMap(
        targetGraph.get("//Apps/Libs:A"),
        ImmutableMap.of(
            "name",
            "A",
            "deps",
            ImmutableSet.of("//Apps/Libs:C"),
            "labels",
            ImmutableSet.of("A_label")));
    validateAttributeMap(
        targetGraph.get("//Apps/Libs:B"),
        ImmutableMap.of(
            "name",
            "B",
            "deps",
            ImmutableSet.of("//Apps/Libs:C"),
            "labels",
            ImmutableSet.of("B_label")));
    validateAttributeMap(
        targetGraph.get("//Apps/Libs:C"),
        ImmutableMap.of(
            "name", "C", "deps", ImmutableSet.of(), "labels", ImmutableSet.of("C_label")));
    validateAttributeMap(
        targetGraph.get("//Apps/Libs:Root"),
        ImmutableMap.of(
            "name",
            "Root",
            "deps",
            ImmutableSet.of("//Apps/Libs:A", "//Apps/Libs:B"),
            "labels",
            ImmutableSet.of("Root_label")));
  }

  @Test
  public void jsonOutputFiltersOutLabels() throws IOException {
    Path targetGraphJson =
        workspace.buildAndReturnOutput("//Apps/TestApp:TestAppSupermoduleGraphWithLabelPattern");
    JsonNode targetGraph =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(targetGraphJson));
    assertEquals(
        Sets.newHashSet(targetGraph.fieldNames()),
        ImmutableSet.of("//Apps/Libs:A", "//Apps/Libs:B", "//Apps/Libs:C", "//Apps/Libs:Root"));

    validateAttributeMap(
        targetGraph.get("//Apps/Libs:A"),
        ImmutableMap.of(
            "name",
            "A",
            "deps",
            ImmutableSet.of("//Apps/Libs:C"),
            "labels",
            ImmutableSet.of("A_label")));
    validateAttributeMap(
        targetGraph.get("//Apps/Libs:B"),
        ImmutableMap.of(
            "name",
            "B",
            "deps",
            ImmutableSet.of("//Apps/Libs:C"),
            "labels",
            ImmutableSet.of("B_label")));
    validateAttributeMap(
        targetGraph.get("//Apps/Libs:C"),
        ImmutableMap.of(
            "name", "C", "deps", ImmutableSet.of(), "labels", ImmutableSet.of("C_label")));
    validateAttributeMap(
        targetGraph.get("//Apps/Libs:Root"),
        ImmutableMap.of(
            "name",
            "Root",
            "deps",
            ImmutableSet.of("//Apps/Libs:A", "//Apps/Libs:B"),
            "labels",
            ImmutableSet.of()));
  }
}

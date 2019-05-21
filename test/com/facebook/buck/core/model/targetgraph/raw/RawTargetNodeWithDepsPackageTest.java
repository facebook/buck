/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph.raw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.model.ImmutableUnconfiguredBuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;

public class RawTargetNodeWithDepsPackageTest {

  private RawTargetNodeWithDepsPackage getData() {
    ImmutableMap<String, Object> rawAttributes1 =
        ImmutableMap.of(
            "name",
            "target1",
            "buck.type",
            "java_library",
            "buck.base_path",
            "base",
            "deps",
            ImmutableSet.of(":target2"));

    UnconfiguredBuildTarget unconfiguredBuildTarget1 =
        ImmutableUnconfiguredBuildTarget.of(
            "", "//base", "target1", UnconfiguredBuildTarget.NO_FLAVORS);
    RawTargetNode rawTargetNode1 =
        ImmutableRawTargetNode.of(
            unconfiguredBuildTarget1,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes1,
            ImmutableSet.of(),
            ImmutableSet.of());

    ImmutableMap<String, Object> rawAttributes2 =
        ImmutableMap.of("name", "target2", "buck.type", "java_library", "buck.base_path", "base");

    UnconfiguredBuildTarget unconfiguredBuildTarget2 =
        ImmutableUnconfiguredBuildTarget.of(
            "", "//base", "target2", UnconfiguredBuildTarget.NO_FLAVORS);
    RawTargetNode rawTargetNode2 =
        ImmutableRawTargetNode.of(
            unconfiguredBuildTarget2,
            RuleType.of("java_library", RuleType.Kind.BUILD),
            rawAttributes2,
            ImmutableSet.of(),
            ImmutableSet.of());

    RawTargetNodeWithDeps rawTargetNodeWithDeps1 =
        ImmutableRawTargetNodeWithDeps.of(
            rawTargetNode1, ImmutableSet.of(unconfiguredBuildTarget2));
    RawTargetNodeWithDeps rawTargetNodeWithDeps2 =
        ImmutableRawTargetNodeWithDeps.of(rawTargetNode2, ImmutableSet.of());

    RawTargetNodeWithDepsPackage rawTargetNodeWithDepsPackage =
        new ImmutableRawTargetNodeWithDepsPackage(
            Paths.get("base"),
            ImmutableMap.of("target1", rawTargetNodeWithDeps1, "target2", rawTargetNodeWithDeps2));

    return rawTargetNodeWithDepsPackage;
  }

  @Test
  public void canSerializeAndDeserializeJson() throws IOException {

    RawTargetNodeWithDepsPackage rawTargetNodeWithDepsPackage = getData();

    byte[] data = ObjectMappers.WRITER_WITH_TYPE.writeValueAsBytes(rawTargetNodeWithDepsPackage);

    RawTargetNodeWithDepsPackage rawTargetNodeWithDepsPackageDeserialized =
        ObjectMappers.READER_WITH_TYPE
            .forType(ImmutableRawTargetNodeWithDepsPackage.class)
            .readValue(data);

    assertEquals(rawTargetNodeWithDepsPackage, rawTargetNodeWithDepsPackageDeserialized);
  }

  @Test
  public void canSerializeWithoutTypeAndFlatten() throws IOException {
    RawTargetNodeWithDepsPackage rawTargetNodeWithDepsPackage = getData();

    String data = ObjectMappers.WRITER.writeValueAsString(rawTargetNodeWithDepsPackage);

    ObjectMapper mapper = new ObjectMapper();
    JsonNode node = mapper.readTree(data);

    // Validate property from RawTargetNode ("buildTarget") is flattened so it is at the same level
    // as non-flattened property ("deps")
    assertNotNull(node.get("nodes").get("target1").get("buildTarget"));
    assertNotNull(node.get("nodes").get("target1").get("deps"));
  }
}

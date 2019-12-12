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

package com.facebook.buck.features.apple.projectV2;

import static org.junit.Assert.fail;

import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

public class PBXTestUtils {
  public static PBXFileReference assertHasFileReferenceWithNameAndReturnIt(
      PBXGroup group, String fileName) {
    ImmutableList<PBXFileReference> candidates =
        FluentIterable.from(group.getChildren())
            .filter(input -> input.getName().equals(fileName))
            .filter(PBXFileReference.class)
            .toList();
    if (candidates.size() != 1) {
      fail("Could not find a unique subgroup by its name");
    }
    return candidates.get(0);
  }

  public static PBXGroup assertHasSubgroupAndReturnIt(PBXGroup group, String subgroupName) {
    ImmutableList<PBXGroup> candidates =
        FluentIterable.from(group.getChildren())
            .filter(input -> input.getName().equals(subgroupName))
            .filter(PBXGroup.class)
            .toList();
    if (candidates.size() != 1) {
      fail("Could not find a unique subgroup by its name");
    }
    return candidates.get(0);
  }

  public static PBXGroup assertHasSubgroupPathAndReturnLast(
      PBXGroup root, ImmutableList<String> components) {
    PBXGroup group = root;
    for (String component : components) {
      group = assertHasSubgroupAndReturnIt(group, component);
    }
    return group;
  }
}

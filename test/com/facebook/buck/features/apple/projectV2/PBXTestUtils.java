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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

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

  public static void assertHasSingleSourcesPhaseWithSourcesAndFlags(
      PBXTarget target,
      ImmutableMap<String, Optional<String>> sourcesAndFlags,
      ProjectFilesystem projectFilesystem,
      Path outputDirectory) {

    PBXSourcesBuildPhase sourcesBuildPhase =
        ProjectGeneratorTestUtils.getSingleBuildPhaseOfType(target, PBXSourcesBuildPhase.class);

    assertEquals(
        "Sources build phase should have correct number of sources",
        sourcesAndFlags.size(),
        sourcesBuildPhase.getFiles().size());

    // map keys to absolute paths
    ImmutableMap.Builder<String, Optional<String>> absolutePathFlagMapBuilder =
        ImmutableMap.builder();
    for (Map.Entry<String, Optional<String>> name : sourcesAndFlags.entrySet()) {
      absolutePathFlagMapBuilder.put(
          projectFilesystem.getRootPath().resolve(name.getKey()).normalize().toString(),
          name.getValue());
    }
    ImmutableMap<String, Optional<String>> absolutePathFlagMap = absolutePathFlagMapBuilder.build();

    for (PBXBuildFile file : sourcesBuildPhase.getFiles()) {
      String filePath =
          assertFileRefIsRelativeAndResolvePath(
              file.getFileRef(), projectFilesystem, outputDirectory);
      Optional<String> flags = absolutePathFlagMap.get(filePath);
      assertNotNull(String.format("Unexpected file ref '%s' found", filePath), flags);
      if (flags.isPresent()) {
        assertTrue("Build file should have settings dictionary", file.getSettings().isPresent());

        NSDictionary buildFileSettings = file.getSettings().get();
        NSString compilerFlags = (NSString) buildFileSettings.get("COMPILER_FLAGS");

        assertNotNull("Build file settings should have COMPILER_FLAGS entry", compilerFlags);
        assertEquals(
            "Build file settings should be expected value",
            flags.get(),
            compilerFlags.getContent());
      } else {
        assertFalse(
            "Build file should not have settings dictionary", file.getSettings().isPresent());
      }
    }
  }

  public static String assertFileRefIsRelativeAndResolvePath(
      PBXReference fileRef, ProjectFilesystem projectFilesystem, Path outputDirectory) {
    assert (!fileRef.getPath().startsWith("/"));
    assertEquals(
        "file path should be relative to project directory",
        PBXReference.SourceTree.SOURCE_ROOT,
        fileRef.getSourceTree());
    return projectFilesystem
        .resolve(outputDirectory)
        .resolve(fileRef.getPath())
        .normalize()
        .toString();
  }
}

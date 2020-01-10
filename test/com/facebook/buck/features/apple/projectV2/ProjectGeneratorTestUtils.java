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

import static org.hamcrest.Matchers.equalToObject;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFrameworksBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;

public final class ProjectGeneratorTestUtils {

  /** Utility class should not be instantiated. */
  private ProjectGeneratorTestUtils() {}

  public static PBXTarget assertTargetExistsAndReturnTarget(
      PBXProject generatedProject, String name) {
    PBXTarget target = getTargetByName(generatedProject, name);
    assertNotNull("No generated target with name: " + name, target);
    return target;
  }

  private static PBXTarget getTargetByName(PBXProject generatedProject, String name) {
    Objects.requireNonNull(name);
    for (PBXTarget target : generatedProject.getTargets()) {
      if (target.getName().equals(name)) {
        return target;
      }
    }
    return null;
  }

  public static void assertTargetExists(PBXProject project, String name) {
    assertNotNull("No generated target with name: " + name, getTargetByName(project, name));
  }

  public static void assertTargetDoesNotExist(PBXProject project, String name) {
    assertNull("There is generated target with name: " + name, getTargetByName(project, name));
  }

  public static void assertHasDependency(
      PBXProject generatedProject, PBXTarget target, String name) {
    for (PBXTargetDependency dependency : target.getDependencies()) {
      PBXTarget dependencyTarget = getTargetByName(generatedProject, name);
      assertNotNull(dependencyTarget);
      assertThat(
          dependency.getTargetProxy().getRemoteGlobalIDString(),
          equalToObject(dependencyTarget.getGlobalID()));
    }
  }

  public static void assertHasSingleFrameworksPhaseWithFrameworkEntries(
      PBXTarget target, ImmutableList<String> frameworks) {
    assertHasSinglePhaseWithEntries(target, PBXFrameworksBuildPhase.class, frameworks);
  }

  public static void assertHasSingleCopyFilesPhaseWithFileEntries(
      PBXTarget target, ImmutableList<String> files) {
    assertHasSinglePhaseWithEntries(target, PBXCopyFilesBuildPhase.class, files);
  }

  public static <T extends PBXBuildPhase> void assertHasSinglePhaseWithEntries(
      PBXTarget target, Class<T> cls, ImmutableList<String> entries) {
    PBXBuildPhase buildPhase = getSingleBuildPhaseOfType(target, cls);
    assertThat(
        "Phase should have right number of entries",
        buildPhase.getFiles(),
        hasSize(entries.size()));

    for (PBXBuildFile file : buildPhase.getFiles()) {
      PBXReference.SourceTree sourceTree = file.getFileRef().getSourceTree();
      switch (sourceTree) {
        case GROUP:
          fail("Should not emit entries with sourceTree <group>");
          break;
        case ABSOLUTE:
          fail("Should not emit entries with sourceTree <absolute>");
          break;
          // $CASES-OMITTED$
        default:
          String serialized = "$" + sourceTree + "/" + file.getFileRef().getPath();
          assertThat(
              "Source tree prefixed file references should exist in list of expected entries.",
              entries,
              hasItem(serialized));
          break;
      }
    }
  }

  public static <T extends PBXBuildPhase> T getSingleBuildPhaseOfType(
      PBXTarget target, Class<T> cls) {
    Iterable<T> buildPhases = getExpectedBuildPhasesByType(target, cls, 1);
    @SuppressWarnings("unchecked")
    T element = (T) Iterables.getOnlyElement(buildPhases);
    return element;
  }

  public static <T extends PBXBuildPhase> Iterable<T> getExpectedBuildPhasesByType(
      PBXTarget target, Class<T> cls, int expectedCount) {
    Iterable<PBXBuildPhase> buildPhases =
        Iterables.filter(target.getBuildPhases(), cls::isInstance);
    int actualCount = Iterables.size(buildPhases);
    assertEquals(
        String.format(
            "Expected exactly %d build phases of type %s, found %d",
            expectedCount, cls.getName(), actualCount),
        expectedCount,
        actualCount);
    return (Iterable<T>) buildPhases;
  }

  public static ImmutableMap<String, String> getBuildSettings(
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      PBXTarget target,
      String config) {
    XCBuildConfiguration configuration =
        target.getBuildConfigurationList().getBuildConfigurationsByName().asMap().get(config);
    assertEquals(configuration.getBuildSettings().count(), 0);

    PBXFileReference xcconfigReference = configuration.getBaseConfigurationReference();
    Path xcconfigPath =
        buildTarget
            .getCellRelativeBasePath()
            .getPath()
            .toPath(projectFilesystem.getFileSystem())
            .resolve(xcconfigReference.getPath());
    String contents = projectFilesystem.readFileIfItExists(xcconfigPath).get();

    // Use a HashMap to allow for duplicate keys.
    HashMap<String, String> builder = new HashMap<String, String>();
    for (String line : contents.split("\n")) {
      String[] parts = line.split(" = ");
      builder.put(parts[0], parts[1]);
    }

    return ImmutableMap.copyOf(builder);
  }
}

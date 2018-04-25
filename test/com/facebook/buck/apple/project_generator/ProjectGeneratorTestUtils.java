/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.apple.project_generator;

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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.HashMap;

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
    Preconditions.checkNotNull(name);
    for (PBXTarget target : generatedProject.getTargets()) {
      if (target.getName().equals(name)) {
        return target;
      }
    }
    return null;
  }

  public static void assertTargetExists(ProjectGenerator generator, String name) {
    assertNotNull(
        "No generated target with name: " + name,
        getTargetByName(generator.getGeneratedProject(), name));
  }

  public static void assertTargetDoesNotExists(ProjectGenerator generator, String name) {
    assertNull(
        "There is generated target with name: " + name,
        getTargetByName(generator.getGeneratedProject(), name));
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

  public static void assertHasSingletonFrameworksPhaseWithFrameworkEntries(
      PBXTarget target, ImmutableList<String> frameworks) {
    assertHasSingletonPhaseWithEntries(target, PBXFrameworksBuildPhase.class, frameworks);
  }

  public static void assertHasSingletonCopyFilesPhaseWithFileEntries(
      PBXTarget target, ImmutableList<String> files) {
    assertHasSingletonPhaseWithEntries(target, PBXCopyFilesBuildPhase.class, files);
  }

  public static <T extends PBXBuildPhase> void assertHasSingletonPhaseWithEntries(
      PBXTarget target, Class<T> cls, ImmutableList<String> entries) {
    PBXBuildPhase buildPhase = getSingletonPhaseByType(target, cls);
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

  public static <T extends PBXBuildPhase> T getSingletonPhaseByType(
      PBXTarget target, Class<T> cls) {
    Iterable<PBXBuildPhase> buildPhases =
        Iterables.filter(target.getBuildPhases(), cls::isInstance);
    assertEquals("Build phase should be singleton", 1, Iterables.size(buildPhases));
    @SuppressWarnings("unchecked")
    T element = (T) Iterables.getOnlyElement(buildPhases);
    return element;
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
    Path xcconfigPath = buildTarget.getBasePath().resolve(xcconfigReference.getPath());
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

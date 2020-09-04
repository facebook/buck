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

import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link AppleResourceProcessing}. */
public class AppleResourceProcessingTest {

  private ProjectFilesystem filesystem;
  private SourcePathResolverAdapter resolver;
  private AppleBundleResources.Builder resourcesBuilder;

  @Before
  public void setupWorkspace() {
    filesystem = new FakeProjectFilesystem();
    resolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(new TestActionGraphBuilder()));
    resourcesBuilder = AppleBundleResources.builder();
  }

  @Test
  public void
      test_givenResourcesWithSameNameCopiedToDifferentDestinations_givenDestinationsAreMappedToTheSameDirectory_whenVerifyConflicts_thenExceptionIsThrown() {
    SourcePath path1 = PathSourcePath.of(filesystem, Paths.get("test1"));
    resourcesBuilder.addResourceFiles(
        SourcePathWithAppleBundleDestination.of(path1, AppleBundleDestination.RESOURCES));

    SourcePath path2 = PathSourcePath.of(filesystem, Paths.get("test1"));
    ImmutableList<AppleBundlePart> bundleParts =
        ImmutableList.of(
            FileAppleBundlePart.of(path2, AppleBundleDestination.METADATA, Optional.empty()));

    try {
      AppleResourceProcessing.verifyResourceConflicts(
          resourcesBuilder.build(),
          bundleParts,
          resolver,
          AppleBundleDestinations.OSX_FRAMEWORK_DESTINATIONS);
    } catch (HumanReadableException exception) {
      return;
    }
    fail("Verification expected to throw an exception");
  }

  @Test
  public void
      test_givenResourcesWithDifferentNamesCopiedToDifferentDestinations_givenDestinationsAreMappedToTheSameDirectory_whenVerifyConflicts_thenNoExceptionIsThrown() {
    SourcePath path1 = PathSourcePath.of(filesystem, Paths.get("test1"));
    resourcesBuilder.addResourceFiles(
        SourcePathWithAppleBundleDestination.of(path1, AppleBundleDestination.RESOURCES));

    SourcePath path2 = PathSourcePath.of(filesystem, Paths.get("test2"));
    ImmutableList<AppleBundlePart> bundleParts =
        ImmutableList.of(
            FileAppleBundlePart.of(path2, AppleBundleDestination.METADATA, Optional.empty()));

    try {
      AppleResourceProcessing.verifyResourceConflicts(
          resourcesBuilder.build(),
          bundleParts,
          resolver,
          AppleBundleDestinations.OSX_FRAMEWORK_DESTINATIONS);
    } catch (HumanReadableException exception) {
      fail("Verification is not expected to throw an exception");
    }
  }

  @Test
  public void
      test_givenResourcesWithSameNameCopiedToDifferentDestinations_givenDestinationsAreMappedToDifferentDirectories_whenVerifyConflicts_thenNoExceptionIsThrown() {
    SourcePath path1 = PathSourcePath.of(filesystem, Paths.get("test1"));
    resourcesBuilder.addResourceFiles(
        SourcePathWithAppleBundleDestination.of(path1, AppleBundleDestination.RESOURCES));

    SourcePath path2 = PathSourcePath.of(filesystem, Paths.get("test1"));
    ImmutableList<AppleBundlePart> bundleParts =
        ImmutableList.of(
            FileAppleBundlePart.of(path2, AppleBundleDestination.HEADERS, Optional.empty()));

    try {
      AppleResourceProcessing.verifyResourceConflicts(
          resourcesBuilder.build(),
          bundleParts,
          resolver,
          AppleBundleDestinations.OSX_FRAMEWORK_DESTINATIONS);
    } catch (HumanReadableException exception) {
      fail("Verification is not expected to throw an exception");
    }
  }

  @Test
  public void
      test_givenResourcesWithSameNameCopiedToSameDirectory_givenOneIsRenamed_thenVerificationIsSuccessful() {
    SourcePath path1 = PathSourcePath.of(filesystem, Paths.get("test1"));
    resourcesBuilder.addResourceFiles(
        SourcePathWithAppleBundleDestination.of(path1, AppleBundleDestination.RESOURCES));

    SourcePath path2 = PathSourcePath.of(filesystem, Paths.get("test1"));
    ImmutableList<AppleBundlePart> bundleParts =
        ImmutableList.of(
            FileAppleBundlePart.of(
                path2,
                AppleBundleDestination.RESOURCES,
                Optional.empty(),
                false,
                Optional.of("test2"),
                false));

    try {
      AppleResourceProcessing.verifyResourceConflicts(
          resourcesBuilder.build(),
          bundleParts,
          resolver,
          AppleBundleDestinations.OSX_FRAMEWORK_DESTINATIONS);
    } catch (HumanReadableException exception) {
      fail("Verification is not expected to throw an exception");
    }
  }

  @Test
  public void
      test_givenResourcesWithDifferentNamesCopiedToSameDirectory_givenOneIsRenamedSoFinalNamesAreSame_thenVerificationFails() {
    SourcePath path1 = PathSourcePath.of(filesystem, Paths.get("test1"));
    resourcesBuilder.addResourceFiles(
        SourcePathWithAppleBundleDestination.of(path1, AppleBundleDestination.RESOURCES));

    SourcePath path2 = PathSourcePath.of(filesystem, Paths.get("test2"));
    ImmutableList<AppleBundlePart> bundleParts =
        ImmutableList.of(
            FileAppleBundlePart.of(
                path2,
                AppleBundleDestination.RESOURCES,
                Optional.empty(),
                false,
                Optional.of("test1"),
                false));

    try {
      AppleResourceProcessing.verifyResourceConflicts(
          resourcesBuilder.build(),
          bundleParts,
          resolver,
          AppleBundleDestinations.OSX_FRAMEWORK_DESTINATIONS);
    } catch (HumanReadableException exception) {
      return;
    }
    fail("Verification expected to throw an exception");
  }
}

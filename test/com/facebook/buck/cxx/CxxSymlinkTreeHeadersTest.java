/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTreeWithModuleMap;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.modern.SerializationTestHelper;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class CxxSymlinkTreeHeadersTest {
  @Test
  public void testSerialization() throws IOException {
    CxxSymlinkTreeHeaders cxxSymlinkTreeHeaders =
        CxxSymlinkTreeHeaders.of(
            CxxPreprocessables.IncludeType.SYSTEM,
            FakeSourcePath.of("root"),
            Either.ofRight(FakeSourcePath.of("includeRoot")),
            Optional.of(FakeSourcePath.of("headerMap")),
            ImmutableSortedMap.of(Paths.get("a/b"), FakeSourcePath.of("path")),
            "treeClass");

    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    CxxSymlinkTreeHeaders reconstructed =
        SerializationTestHelper.serializeAndDeserialize(
            cxxSymlinkTreeHeaders,
            ruleFinder,
            TestCellPathResolver.create(fakeFilesystem.getRootPath()),
            ruleFinder.getSourcePathResolver(),
            new ToolchainProviderBuilder().build(),
            cellPath -> fakeFilesystem);

    assertEquals(cxxSymlinkTreeHeaders, reconstructed);
  }

  @Test
  public void testSerializationAnonymousClass() {

    CxxSymlinkTreeHeaders cxxSymlinkTreeHeaders =
        new CxxSymlinkTreeHeaders() {

          @Override
          public CxxPreprocessables.IncludeType getIncludeType() {
            return CxxPreprocessables.IncludeType.SYSTEM;
          }

          @Override
          public SourcePath getRoot() {
            return FakeSourcePath.of("root");
          }

          @Override
          public Either<PathSourcePath, SourcePath> getIncludeRoot() {
            return Either.ofRight(FakeSourcePath.of("includeRoot"));
          }

          @Override
          public Optional<SourcePath> getHeaderMap() {
            return Optional.of(FakeSourcePath.of("headerMap"));
          }

          @Override
          public ImmutableSortedMap<Path, SourcePath> getNameToPathMap() {
            return ImmutableSortedMap.of(Paths.get("a/b"), FakeSourcePath.of("path"));
          }

          @Override
          public String getSymlinkTreeClass() {
            return "treeClass";
          }
        };

    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SerializationTestHelper.serializeAndDeserialize(
                    cxxSymlinkTreeHeaders,
                    ruleFinder,
                    TestCellPathResolver.create(fakeFilesystem.getRootPath()),
                    ruleFinder.getSourcePathResolver(),
                    new ToolchainProviderBuilder().build(),
                    cellPath -> fakeFilesystem));

    assertEquals(exception.getMessage(), "Cannot be or reference anonymous classes.");
  }

  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  @Test
  public void testHeadersIncludeModulemap() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());

    // Create a build target to use when building the symlink tree.
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//test:test");

    // Get the first file we're symlinking
    Path link1 = Paths.get("SomeModule", "SomeModule.h");
    AbsPath file1 = tmpDir.newFile();
    Files.write(file1.getPath(), "hello world".getBytes(StandardCharsets.UTF_8));

    // Get the second file we're symlinking
    Path link2 = Paths.get("SomeModule", "Header.h");
    AbsPath file2 = tmpDir.newFile();
    Files.write(file2.getPath(), "hello world".getBytes(StandardCharsets.UTF_8));

    // Setup the map representing the link tree.
    ImmutableMap<Path, SourcePath> links =
        ImmutableMap.of(
            link1,
            PathSourcePath.of(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1)),
            link2,
            PathSourcePath.of(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file2)));

    // The output path used by the buildable for the link tree.
    RelPath symlinkTreeRoot =
        BuildTargetPaths.getGenPath(
            projectFilesystem.getBuckPaths(), buildTarget, "%s/symlink-tree-root");

    // Setup the symlink tree buildable.
    HeaderSymlinkTreeWithModuleMap symlinkTreeBuildRule =
        HeaderSymlinkTreeWithModuleMap.create(
            buildTarget,
            projectFilesystem,
            symlinkTreeRoot.getPath(),
            links,
            "SomeModule",
            false,
            false);

    CxxSymlinkTreeHeaders headers =
        CxxSymlinkTreeHeaders.from(symlinkTreeBuildRule, CxxPreprocessables.IncludeType.LOCAL);

    ImmutableSortedMap.Builder<Path, SourcePath> expectedHeadersBuilder =
        ImmutableSortedMap.naturalOrder();
    expectedHeadersBuilder.putAll(symlinkTreeBuildRule.getLinks());
    Path modulemapPath =
        BuildTargetPaths.getGenPath(
                projectFilesystem.getBuckPaths(), buildTarget, "%s/module.modulemap")
            .getPath();
    expectedHeadersBuilder.put(modulemapPath, symlinkTreeBuildRule.getSourcePathToOutput());

    assertEquals(expectedHeadersBuilder.build(), headers.getNameToPathMap());
  }
}

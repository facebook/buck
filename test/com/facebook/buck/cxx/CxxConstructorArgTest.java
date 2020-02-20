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

package com.facebook.buck.cxx;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CxxConstructorArgTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCheckDuplicateSourcesFailsDuplicates() {
    // Stuff we actually care about testing
    Path fakePath = Paths.get("a", "pretty", "long", "path.cpp");
    SourcePath sharedPath = PathSourcePath.of(new FakeProjectFilesystem(), fakePath);

    ImmutableSortedSet<SourceWithFlags> srcs =
        ImmutableSortedSet.of(SourceWithFlags.of(sharedPath, ImmutableList.of("-Dbar", "-Dbaz")));
    PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs =
        PatternMatchedCollection.<ImmutableSortedSet<SourceWithFlags>>builder()
            .add(
                Pattern.compile("barbaz"),
                ImmutableSortedSet.of(
                    SourceWithFlags.of(sharedPath, ImmutableList.of("-DEADBEEF"))))
            .build();

    TestCxxConstructorArg cxxConstructorArg = new TestCxxConstructorArg(srcs, platformSrcs);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Files may be listed in srcs or platform_srcs, but not both. "
            + "The following file is listed both in srcs and platform_srcs:");
    thrown.expectMessage(Matchers.containsString("\t" + fakePath.toString()));

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    cxxConstructorArg.checkDuplicateSources(graphBuilder.getSourcePathResolver());
  }

  private static class TestCxxConstructorArg implements CxxConstructorArg {

    private final ImmutableSortedSet<SourceWithFlags> srcs;
    private final PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs;

    private TestCxxConstructorArg(
        ImmutableSortedSet<SourceWithFlags> srcs,
        PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSrcs) {
      this.srcs = srcs;
      this.platformSrcs = platformSrcs;
    }

    @Override
    public ImmutableSortedSet<SourceWithFlags> getSrcs() {
      return srcs;
    }

    @Override
    public PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> getPlatformSrcs() {
      return platformSrcs;
    }

    @Override
    public Optional<SourcePath> getPrefixHeader() {
      return Optional.empty();
    }

    @Override
    public Optional<SourcePath> getPrecompiledHeader() {
      return Optional.empty();
    }

    @Override
    public ImmutableList<StringWithMacros> getCompilerFlags() {
      return ImmutableList.of();
    }

    @Override
    public ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>> getLangCompilerFlags() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        getLangPlatformCompilerFlags() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableList<StringWithMacros> getPreprocessorFlags() {
      return ImmutableList.of();
    }

    @Override
    public ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>>
        getLangPreprocessorFlags() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        getLangPlatformPreprocessorFlags() {
      return ImmutableMap.of();
    }

    @Override
    public ImmutableList<StringWithMacros> getLinkerFlags() {
      return ImmutableList.of();
    }

    @Override
    public ImmutableList<StringWithMacros> getPostLinkerFlags() {
      return ImmutableList.of();
    }

    @Override
    public ImmutableList<String> getLinkerExtraOutputs() {
      return ImmutableList.of();
    }

    @Override
    public Optional<String> getExecutableName() {
      return Optional.empty();
    }

    @Override
    public Optional<String> getHeaderNamespace() {
      return Optional.empty();
    }

    @Override
    public Optional<Linker.CxxRuntimeType> getCxxRuntimeType() {
      return Optional.empty();
    }

    @Override
    public ImmutableMap<String, Flavor> getDefaults() {
      return ImmutableMap.of();
    }

    @Override
    public String getName() {
      return "testCxxConstructorArgName";
    }

    @Override
    public ImmutableSet<SourcePath> getLicenses() {
      return ImmutableSet.of();
    }

    @Override
    public ImmutableSortedSet<String> getLabels() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableSortedSet<BuildTarget> getDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public Optional<Flavor> getDefaultPlatform() {
      return Optional.empty();
    }

    @Override
    public ImmutableList<UnconfiguredBuildTarget> getCompatibleWith() {
      return ImmutableList.of();
    }

    @Override
    public Optional<UnconfiguredBuildTarget> getDefaultTargetPlatform() {
      return Optional.empty();
    }

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableSortedSet<FrameworkPath> getFrameworks() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableSortedSet<FrameworkPath> getLibraries() {
      return ImmutableSortedSet.of();
    }
  }
}

/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.regex.Pattern;

public class PythonLibraryDescriptionTest {

  @Test
  public void baseModule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    String sourceName = "main.py";
    SourcePath source = new FakeSourcePath("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonLibrary normal =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder()))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertEquals(
        ImmutableMap.of(
            target.getBasePath().resolve(sourceName),
            source),
        normal.getSrcs(PythonTestUtils.PYTHON_PLATFORM));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonLibrary withBaseModule =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder()))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .setBaseModule(baseModule)
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertEquals(
        ImmutableMap.of(
            Paths.get(baseModule).resolve(sourceName),
            source),
        withBaseModule.getSrcs(PythonTestUtils.PYTHON_PLATFORM));
  }

  @Test
  public void baseModuleStrip() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    String sourceName = "main.py";
    SourcePath source1 = new FakeSourcePath("foo/" + sourceName);
    SourcePath source2 = new FakeSourcePath("foo/bar/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonLibrary normal =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder()))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source1, source2)))
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertEquals(
        ImmutableMap.of(
            Paths.get("foo").resolve(sourceName),
            source1,
            Paths.get("foo/bar").resolve(sourceName),
            source2),
        normal.getSrcs(PythonTestUtils.PYTHON_PLATFORM));

    // Run *with* a base module strip set and verify it gets used to build the main module path.
    Integer baseModuleStrip = 1;
    PythonLibrary withBaseModuleStrip =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder()))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source1, source2)))
            .setBaseModuleStrip(baseModuleStrip)
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertEquals(
        ImmutableMap.of(
            Paths.get("").resolve(sourceName),
            source1,
            Paths.get("bar").resolve(sourceName),
            source2),
        withBaseModuleStrip.getSrcs(PythonTestUtils.PYTHON_PLATFORM));

    // Run it again, this time use the config to influence it
    PythonLibrary withBaseModuleStripConfig =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(
                    FakeBuckConfig.builder().setSections(
                        ImmutableMap.of(
                            "python",
                            ImmutableMap.of(
                                "base_module_strip", "1"))).build(),
                    new ExecutableFinder()))
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source1, source2)))
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertEquals(
        ImmutableMap.of(
            Paths.get("").resolve(sourceName),
            source1,
            Paths.get("bar").resolve(sourceName),
            source2),
        withBaseModuleStripConfig.getSrcs(PythonTestUtils.PYTHON_PLATFORM));
  }

  @Test
  public void platformSrcs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    PythonLibrary library =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder()))
            .setPlatformSrcs(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(
        library.getSrcs(PythonTestUtils.PYTHON_PLATFORM).values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void platformResources() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.dat");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.dat");
    PythonLibrary library =
        (PythonLibrary) new PythonLibraryBuilder(
                target,
                new PythonBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder()))
            .setPlatformResources(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer()));
    assertThat(
        library.getResources(PythonTestUtils.PYTHON_PLATFORM).values(),
        Matchers.contains(matchedSource));
  }

}

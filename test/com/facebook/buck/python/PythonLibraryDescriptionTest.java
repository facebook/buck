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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.regex.Pattern;

public class PythonLibraryDescriptionTest {

  @Test
  public void baseModule() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    String sourceName = "main.py";
    SourcePath source = new FakeSourcePath("foo/" + sourceName);

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    PythonLibraryBuilder normalBuilder =
        new PythonLibraryBuilder(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)));
    TargetGraph normalTargetGraph =
        TargetGraphFactory.newInstance(normalBuilder.build());
    PythonLibrary normal =
        (PythonLibrary) normalBuilder.build(
            new BuildRuleResolver(
                normalTargetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            normalTargetGraph);
    assertEquals(
        ImmutableMap.of(
            target.getBasePath().resolve(sourceName),
            source),
        normal.getSrcs(PythonTestUtils.PYTHON_PLATFORM));

    // Run *with* a base module set and verify it gets used to build the main module path.
    String baseModule = "blah";
    PythonLibraryBuilder withBaseModuleBuilder =
        new PythonLibraryBuilder(target)
            .setSrcs(SourceList.ofUnnamedSources(ImmutableSortedSet.of(source)))
            .setBaseModule(baseModule);
    TargetGraph withBaseModuleTargetGraph =
        TargetGraphFactory.newInstance(normalBuilder.build());
    PythonLibrary withBaseModule =
        (PythonLibrary) withBaseModuleBuilder.build(
            new BuildRuleResolver(
                withBaseModuleTargetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            withBaseModuleTargetGraph);
    assertEquals(
        ImmutableMap.of(
            Paths.get(baseModule).resolve(sourceName),
            source),
        withBaseModule.getSrcs(PythonTestUtils.PYTHON_PLATFORM));
  }

  @Test
  public void platformSrcs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setPlatformSrcs(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    PythonLibrary library =
        (PythonLibrary) builder.build(
            new BuildRuleResolver(
                targetGraph,
                new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            targetGraph);
    assertThat(
        library.getSrcs(PythonTestUtils.PYTHON_PLATFORM).values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void platformResources() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.dat");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.dat");
    PythonLibraryBuilder builder =
        new PythonLibraryBuilder(target)
            .setPlatformResources(
                PatternMatchedCollection.<SourceList>builder()
                    .add(
                        Pattern.compile(PythonTestUtils.PYTHON_PLATFORM.getFlavor().toString()),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        Pattern.compile("won't match anything"),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build());
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    PythonLibrary library =
        (PythonLibrary) builder.build(
              new BuildRuleResolver(
                  targetGraph,
                  new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem,
            targetGraph);
    assertThat(
        library.getResources(PythonTestUtils.PYTHON_PLATFORM).values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void versionedSrcs() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out");
    AbstractNodeBuilder<?, ?> builder =
        new PythonLibraryBuilder(target)
            .setVersionedSrcs(
                VersionMatchedCollection.<SourceList>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .setSelectedVersions(ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            targetGraph,
            new DefaultTargetNodeToBuildRuleTransformer());
    depBuilder.build(resolver, filesystem, targetGraph);
    PythonLibrary library = (PythonLibrary) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        library.getSrcs(PythonTestUtils.PYTHON_PLATFORM).values(),
        Matchers.contains(matchedSource));
  }

  @Test
  public void versionedResources() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    SourcePath matchedSource = new FakeSourcePath("foo/a.py");
    SourcePath unmatchedSource = new FakeSourcePath("foo/b.py");
    GenruleBuilder depBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out");
    AbstractNodeBuilder<?, ?> builder =
        new PythonLibraryBuilder(target)
            .setVersionedResources(
                VersionMatchedCollection.<SourceList>builder()
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(matchedSource)))
                    .add(
                        ImmutableMap.of(depBuilder.getTarget(), Version.of("2.0")),
                        SourceList.ofUnnamedSources(ImmutableSortedSet.of(unmatchedSource)))
                    .build())
            .setSelectedVersions(ImmutableMap.of(depBuilder.getTarget(), Version.of("1.0")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            targetGraph,
            new DefaultTargetNodeToBuildRuleTransformer());
    depBuilder.build(resolver, filesystem, targetGraph);
    PythonLibrary library = (PythonLibrary) builder.build(resolver, filesystem, targetGraph);
    assertThat(
        library.getResources(PythonTestUtils.PYTHON_PLATFORM).values(),
        Matchers.contains(matchedSource));
  }

}

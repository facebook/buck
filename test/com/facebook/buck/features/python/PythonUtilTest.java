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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonUtilTest {

  @Test
  public void toModuleMapWithExplicitMap() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ImmutableMap.Builder<Path, SourcePath> srcsBuilder = ImmutableMap.builder();
    PythonUtil.forEachModuleParam(
        target,
        new TestActionGraphBuilder(),
        CxxPlatformUtils.DEFAULT_PLATFORM,
        "srcs",
        target.getCellRelativeBasePath().getPath().toPathDefaultFileSystem(),
        ImmutableList.of(
            SourceSortedSet.ofNamedSources(
                ImmutableSortedMap.of("hello.py", FakeSourcePath.of("goodbye.py")))),
        srcsBuilder::put);
    ImmutableMap<Path, SourcePath> srcs = srcsBuilder.build();
    assertEquals(
        ImmutableMap.<Path, SourcePath>of(
            target
                .getCellRelativeBasePath()
                .getPath()
                .toPathDefaultFileSystem()
                .resolve("hello.py"),
            FakeSourcePath.of("goodbye.py")),
        srcs);
  }

  @Test
  public void checkSrcExt() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    PythonLibraryDescription.CoreArg args =
        new PythonLibraryBuilder(target)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(FakeSourcePath.of("foo.json"))))
            .build()
            .getConstructorArg();

    // Create Python source args.
    Consumer<PythonBuckConfig.SrcExtCheckStyle> parseSources =
        style ->
            PythonUtil.parseModules(
                target,
                new TestActionGraphBuilder(),
                PythonTestUtils.PYTHON_PLATFORM,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Optional.empty(),
                style,
                args);

    // Don't check any sources.
    parseSources.accept(PythonBuckConfig.SrcExtCheckStyle.NONE);

    // Check generated sources.
    parseSources.accept(PythonBuckConfig.SrcExtCheckStyle.GENERATED);

    // Check all sources.
    try {
      parseSources.accept(PythonBuckConfig.SrcExtCheckStyle.ALL);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), Matchers.containsString("invalid source extension"));
    }
  }

  @Test
  public void checkGeneratedSrcExt() {
    // Setup a generated source.
    GenruleBuilder genruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gen"))
            .setOut("foo.json");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(genruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    Genrule genrule = (Genrule) graphBuilder.requireRule(genruleBuilder.getTarget());

    // Create Python source args.
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    PythonLibraryDescription.CoreArg args =
        new PythonLibraryBuilder(target)
            .setSrcs(
                SourceSortedSet.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        Preconditions.checkNotNull(genrule.getSourcePathToOutput()))))
            .build()
            .getConstructorArg();

    Consumer<PythonBuckConfig.SrcExtCheckStyle> parseSources =
        style ->
            PythonUtil.parseModules(
                target,
                graphBuilder,
                PythonTestUtils.PYTHON_PLATFORM,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Optional.empty(),
                style,
                args);

    // Don't check any sources.
    parseSources.accept(PythonBuckConfig.SrcExtCheckStyle.NONE);

    // Check generated sources.
    try {
      parseSources.accept(PythonBuckConfig.SrcExtCheckStyle.GENERATED);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), Matchers.containsString("invalid source extension"));
    }

    // Check all sources.
    try {
      parseSources.accept(PythonBuckConfig.SrcExtCheckStyle.ALL);
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), Matchers.containsString("invalid source extension"));
    }
  }
}

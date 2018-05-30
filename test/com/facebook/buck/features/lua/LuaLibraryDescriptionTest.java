/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.lua;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class LuaLibraryDescriptionTest {

  @Test
  public void unnamedSource() {
    LuaLibraryBuilder builder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//some:rule"))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("some/foo.lua")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    LuaLibrary library = builder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        library.getLuaPackageComponents(pathResolver).getModules(),
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "some/foo.lua", FakeSourcePath.of("some/foo.lua"))));
  }

  @Test
  public void namedSource() {
    LuaLibraryBuilder builder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//some:rule"))
            .setSrcs(ImmutableSortedMap.of("bar.lua", FakeSourcePath.of("foo.lua")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    LuaLibrary library = builder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        library.getLuaPackageComponents(pathResolver).getModules(),
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "some/bar.lua", FakeSourcePath.of("foo.lua"))));
  }

  @Test
  public void baseModuleSource() {
    LuaLibraryBuilder builder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//some:rule"))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("some/foo.lua")))
            .setBaseModule("blah");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    LuaLibrary library = builder.build(graphBuilder, filesystem, targetGraph);
    assertThat(
        library.getLuaPackageComponents(pathResolver).getModules(),
        Matchers.equalTo(
            ImmutableSortedMap.<String, SourcePath>of(
                "blah/foo.lua", FakeSourcePath.of("some/foo.lua"))));
  }

  @Test
  public void platformDeps() {
    LuaLibraryBuilder libraryABuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:libA"));
    LuaLibraryBuilder libraryBBuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:libB"));
    LuaLibraryBuilder ruleBuilder =
        new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().toString(),
                            Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(libraryBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            libraryABuilder.build(), libraryBBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    LuaLibrary rule = (LuaLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    assertThat(
        RichStream.from(rule.getLuaPackageDeps(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
            .map(BuildRule::getBuildTarget)
            .toImmutableSet(),
        Matchers.allOf(
            Matchers.hasItem(libraryABuilder.getTarget()),
            Matchers.not(Matchers.hasItem(libraryBBuilder.getTarget()))));
  }
}

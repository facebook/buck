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

package com.facebook.buck.lua;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.python.PythonLibrary;
import com.facebook.buck.python.PythonLibraryBuilder;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LuaBinaryDescriptionTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void mainModule() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    LuaBinary binary =
        (LuaBinary) new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .build(resolver);
    assertThat(binary.getMainModule(), Matchers.equalTo("hello.world"));
  }

  @Test
  public void extensionOverride() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    LuaBinary binary =
        (LuaBinary) new LuaBinaryBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                FakeLuaConfig.DEFAULT
                    .withExtension(".override"))
            .setMainModule("main")
            .build(resolver);
    assertThat(binary.getBinPath().toString(), Matchers.endsWith(".override"));
  }

  @Test
  public void toolOverride() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    Tool override = new CommandTool.Builder().addArg("override").build();
    LuaBinary binary =
        (LuaBinary) new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FakeLuaConfig.DEFAULT
                .withLua(override)
                .withExtension(".override"))
            .setMainModule("main")
            .build(resolver);
    assertThat(binary.getLua(), Matchers.is(override));
  }

  @Test
  public void versionLessNativeLibraryExtension() throws Exception {
    CxxLibraryBuilder cxxLibraryBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:lib"))
            .setSoname("libfoo.so.1.0")
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("hello.c"))));
    LuaBinaryBuilder binaryBuilder =
        new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FakeLuaConfig.DEFAULT.withPackageStyle(LuaConfig.PackageStyle.INPLACE))
            .setMainModule("main")
            .setDeps(ImmutableSortedSet.of(cxxLibraryBuilder.getTarget()));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxLibraryBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxLibraryBuilder.build(resolver);
    binaryBuilder.build(resolver);
    SymlinkTree tree =
        resolver.getRuleWithType(
            LuaBinaryDescription.getNativeLibsSymlinkTreeTarget(binaryBuilder.getTarget()),
            SymlinkTree.class);
    assertThat(
        tree.getLinks().keySet(),
        Matchers.hasItem(
            tree.getProjectFilesystem().getRootPath().getFileSystem().getPath("libfoo.so")));
  }

  @Test
  public void duplicateIdenticalModules() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    LuaLibrary libraryA =
        (LuaLibrary) new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:a"))
            .setSrcs(
                ImmutableSortedMap.<String, SourcePath>of("foo.lua", new FakeSourcePath("test")))
            .build(resolver);
    LuaLibrary libraryB =
        (LuaLibrary) new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:b"))
            .setSrcs(
                ImmutableSortedMap.<String, SourcePath>of("foo.lua", new FakeSourcePath("test")))
            .build(resolver);
    new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
        .setMainModule("hello.world")
        .setDeps(ImmutableSortedSet.of(libraryA.getBuildTarget(), libraryB.getBuildTarget()))
        .build(resolver);
  }

  @Test
  public void duplicateConflictingModules() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    LuaLibrary libraryA =
        (LuaLibrary) new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:a"))
            .setSrcs(
                ImmutableSortedMap.<String, SourcePath>of("foo.lua", new FakeSourcePath("foo")))
            .build(resolver);
    LuaLibrary libraryB =
        (LuaLibrary) new LuaLibraryBuilder(BuildTargetFactory.newInstance("//:b"))
            .setSrcs(
                ImmutableSortedMap.<String, SourcePath>of("foo.lua", new FakeSourcePath("bar")))
            .build(resolver);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("conflicting modules for foo.lua"));
    new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
        .setMainModule("hello.world")
        .setDeps(ImmutableSortedSet.of(libraryA.getBuildTarget(), libraryB.getBuildTarget()))
        .build(resolver);
  }

  @Test
  public void pythonDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    PythonLibrary pythonLibrary =
        (PythonLibrary) new PythonLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.<SourcePath>of(new FakeSourcePath("foo.py"))))
            .build(resolver);
    LuaBinary luaBinary =
        (LuaBinary) new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .setDeps(ImmutableSortedSet.of(pythonLibrary.getBuildTarget()))
            .build(resolver);
    assertThat(
        luaBinary.getComponents().getPythonModules().keySet(),
        Matchers.hasItem("foo.py"));
  }

}

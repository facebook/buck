/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.cxx.toolchain.ArchiverProvider.Type;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxToolProvider;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.linker.LinkerProvider;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.junit.Test;

public class CxxToolchainDescriptionTest {
  public static class CxxPlatformNodeBuilder
      extends AbstractNodeBuilder<
          CxxToolchainDescriptionArg.Builder,
          CxxToolchainDescriptionArg,
          CxxToolchainDescription,
          CxxToolchainBuildRule> {

    protected CxxPlatformNodeBuilder(BuildTarget target) {
      super(new CxxToolchainDescription(), target);
    }
  }

  @Test
  public void testCxxToolchainCreatesAppropriateCxxPlatform() {
    BuildTarget target = BuildTargetFactory.newInstance("//:cxx");
    CxxPlatformNodeBuilder builder = new CxxPlatformNodeBuilder(target);
    BuildTarget binaryToolTarget = BuildTargetFactory.newInstance("//:tool");
    DefaultBuildTargetSourcePath binaryToolPath = DefaultBuildTargetSourcePath.of(binaryToolTarget);

    SourcePath pathToolPath = FakeSourcePath.of("some.path");
    builder
        .getArgForPopulating()
        .setBinaryExtension(".bin")
        .setArchiver(binaryToolPath)
        .setArchiverType(Type.GNU)
        .setAssembler(binaryToolPath)
        .setCCompiler(pathToolPath)
        .setCCompilerFlags(ImmutableList.of("c", "flags"))
        .setCompilerType(CxxToolProvider.Type.CLANG)
        .setLinker(pathToolPath)
        .setLinkerFlags(ImmutableList.of("linker", "flags"))
        .setNm(binaryToolPath)
        .setName("my_toolchain")
        .setObjectFileExtension(".object")
        .setSharedLibraryExtension(".library")
        .setSharedLibraryVersionedExtensionFormat("%s.versioned.library")
        .setStaticLibraryExtension(".archive")
        .setLinkerType(LinkerProvider.Type.GNU)
        .setCxxCompiler(pathToolPath)
        .setStrip(binaryToolPath)
        .setSharedLibraryInterfaceType(SharedLibraryInterfaceParams.Type.ENABLED)
        .setObjcopyForSharedLibraryInterface(binaryToolPath)
        .setUseHeaderMap(true);
    TestActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Tool binaryTool = new SimpleTool("this", "command");
    graphBuilder.addToIndex(new SimpleToolRule(binaryToolTarget, binaryTool));
    CxxToolchainBuildRule cxxPlatformRule = builder.build(graphBuilder);

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    CxxPlatform platform = cxxPlatformRule.getPlatformWithFlavor(InternalFlavor.of("dontcare"));

    Tool pathTool = new HashedFileTool(pathToolPath);

    assertIsBinaryTool(resolver, binaryTool, platform.getStrip());
    assertIsBinaryTool(
        resolver,
        pathTool,
        platform.getCc().resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE));
    assertIsBinaryTool(
        resolver,
        pathTool,
        platform.getCxx().resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE));
    assertIsBinaryTool(
        resolver,
        pathTool,
        platform.getCxxpp().resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE));
    assertIsBinaryTool(
        resolver,
        pathTool,
        platform.getLd().resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE));
    assertIsBinaryTool(
        resolver,
        binaryTool,
        platform.getAr().resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE));
    assertIsBinaryTool(
        resolver,
        binaryTool,
        platform.getAs().resolve(graphBuilder, EmptyTargetConfiguration.INSTANCE));
    assertEquals(Optional.empty(), platform.getAsm());
    assertEquals(Optional.empty(), platform.getAsmpp());
    assertEquals(Optional.empty(), platform.getHip());
    assertEquals(Optional.empty(), platform.getHippp());

    assertEquals(ImmutableList.of("-Wl,--build-id", "linker", "flags"), platform.getLdflags());
    assertEquals(ImmutableList.of("c", "flags"), platform.getCflags());
  }

  private void assertIsBinaryTool(SourcePathResolver resolver, Tool expected, Tool other) {
    MoreAsserts.assertIterablesEquals(
        expected.getCommandPrefix(resolver), other.getCommandPrefix(resolver));
  }

  private class SimpleToolRule extends FakeBuildRule implements BinaryBuildRule {
    private final Tool tool;

    public SimpleToolRule(BuildTarget buildTarget, Tool tool) {
      super(buildTarget);
      this.tool = tool;
    }

    @Override
    public Tool getExecutableCommand() {
      return tool;
    }
  }
}

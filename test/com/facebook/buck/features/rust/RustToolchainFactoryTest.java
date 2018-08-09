/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RustToolchainFactoryTest {

  @Test
  public void createToolchain() {
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(
                    CxxPlatformUtils.DEFAULT_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORMS))
            .build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    BuckConfig buckConfig = FakeBuckConfig.builder().setFilesystem(filesystem).build();
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            buckConfig,
            filesystem,
            new FakeProcessExecutor(),
            new AlwaysFoundExecutableFinder(),
            TestRuleKeyConfigurationFactory.create());
    RustToolchainFactory factory = new RustToolchainFactory();
    Optional<RustToolchain> toolchain =
        factory.createToolchain(toolchainProvider, toolchainCreationContext);
    assertThat(
        toolchain.get().getDefaultRustPlatform().getCxxPlatform(),
        Matchers.equalTo(CxxPlatformUtils.DEFAULT_PLATFORM));
    assertThat(
        toolchain
            .get()
            .getRustPlatforms()
            .getValues()
            .stream()
            .map(RustPlatform::getCxxPlatform)
            .collect(ImmutableList.toImmutableList()),
        Matchers.contains(CxxPlatformUtils.DEFAULT_PLATFORM));
  }

  @Test
  public void customPlatforms() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    Flavor custom = InternalFlavor.of("custom");
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(custom);
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(cxxPlatform, FlavorDomain.of("C/C++", cxxPlatform)))
            .build();

    ProcessExecutor processExecutor = new FakeProcessExecutor();
    ExecutableFinder executableFinder = new AlwaysFoundExecutableFinder();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    Path compiler = filesystem.getPath("/some/compiler");
    Path linker = filesystem.getPath("/some/linker");
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "rust#" + custom,
                    ImmutableMap.of("compiler", compiler.toString()),
                    "rust",
                    ImmutableMap.of("linker", linker.toString())))
            .build();
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            buckConfig,
            filesystem,
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    RustToolchainFactory factory = new RustToolchainFactory();
    Optional<RustToolchain> toolchain =
        factory.createToolchain(toolchainProvider, toolchainCreationContext);
    RustPlatform platform = toolchain.get().getRustPlatforms().getValue(custom);
    assertThat(
        toolchain
            .get()
            .getRustPlatforms()
            .getValue(custom)
            .getRustCompiler()
            .resolve(resolver)
            .getCommandPrefix(pathResolver),
        Matchers.contains(filesystem.resolve(compiler).toString()));
    assertThat(
        toolchain
            .get()
            .getRustPlatforms()
            .getValue(custom)
            .getLinker()
            .get()
            .resolve(resolver)
            .getCommandPrefix(pathResolver),
        Matchers.contains(filesystem.resolve(linker).toString()));
    assertThat(platform.getCxxPlatform(), Matchers.equalTo(cxxPlatform));
  }
}

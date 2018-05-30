/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.ocaml;

import static org.junit.Assert.assertThat;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.FakeExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class OcamlToolchainFactoryTest {

  @Test
  public void getCFlags() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withAsflags("-asflag")
            .withCppflags("-cppflag")
            .withCflags("-cflag");
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(cxxPlatform, FlavorDomain.of("C/C++", cxxPlatform)))
            .build();

    ProcessExecutor processExecutor = new FakeProcessExecutor();
    ExecutableFinder executableFinder = new FakeExecutableFinder();
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            buckConfig,
            new FakeProjectFilesystem(),
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    OcamlToolchainFactory factory = new OcamlToolchainFactory();
    Optional<OcamlToolchain> toolchain =
        factory.createToolchain(toolchainProvider, toolchainCreationContext);
    assertThat(
        toolchain.get().getDefaultOcamlPlatform().getCFlags(),
        Matchers.contains("-cppflag", "-cflag", "-asflag"));
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
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "ocaml#" + custom, ImmutableMap.of("ocaml.compiler", compiler.toString())))
            .build();
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            buckConfig,
            filesystem,
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    OcamlToolchainFactory factory = new OcamlToolchainFactory();
    Optional<OcamlToolchain> toolchain =
        factory.createToolchain(toolchainProvider, toolchainCreationContext);
    assertThat(
        toolchain
            .get()
            .getOcamlPlatforms()
            .getValue(custom)
            .getOcamlCompiler()
            .resolve(resolver)
            .getCommandPrefix(pathResolver),
        Matchers.equalTo(ImmutableList.of(filesystem.resolve(compiler).toString())));
  }
}

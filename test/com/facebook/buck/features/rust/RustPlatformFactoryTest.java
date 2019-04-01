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

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.AllExistingProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RustPlatformFactoryTest {

  @Test
  public void configuredPaths() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathRuleFinder finder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(finder);
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    RustPlatformFactory factory =
        RustPlatformFactory.of(
            FakeBuckConfig.builder()
                .setFilesystem(filesystem)
                .setSections(
                    ImmutableMap.of(
                        "rust",
                        ImmutableMap.of(
                            "compiler", "compiler",
                            "linker", "linker")))
                .build(),
            new AlwaysFoundExecutableFinder());
    RustPlatform platform = factory.getPlatform("rust", CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        platform
            .getRustCompiler()
            .resolve(resolver, EmptyTargetConfiguration.INSTANCE)
            .getCommandPrefix(pathResolver),
        Matchers.equalTo(ImmutableList.of(filesystem.resolve("compiler").toString())));
    assertThat(
        platform
            .getLinkerProvider()
            .resolve(resolver, EmptyTargetConfiguration.INSTANCE)
            .getCommandPrefix(pathResolver),
        Matchers.equalTo(ImmutableList.of(filesystem.resolve("linker").toString())));
  }
}

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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class JavaBinaryRuleBuilder
    extends AbstractNodeBuilder<
        JavaBinaryDescriptionArg.Builder, JavaBinaryDescriptionArg, JavaBinaryDescription,
        JavaBinary> {

  private JavaBinaryRuleBuilder(
      BuildTarget target,
      JavaOptions javaOptions,
      JavacOptions javacOptions,
      JavaBuckConfig javaBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    super(
        new JavaBinaryDescription(
            new ToolchainProviderBuilder()
                .withToolchain(
                    CxxPlatformsProvider.DEFAULT_NAME,
                    CxxPlatformsProvider.of(defaultCxxPlatform, cxxPlatforms))
                .withToolchain(
                    JavaCxxPlatformProvider.DEFAULT_NAME,
                    JavaCxxPlatformProvider.of(defaultCxxPlatform))
                .withToolchain(
                    JavaOptionsProvider.DEFAULT_NAME,
                    JavaOptionsProvider.of(javaOptions, javaOptions))
                .withToolchain(
                    JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(javacOptions))
                .build(),
            javaBuckConfig),
        target);
  }

  public JavaBinaryRuleBuilder(
      BuildTarget target, CxxPlatform defaultCxxPlatform, FlavorDomain<CxxPlatform> cxxPlatforms) {
    this(
        target,
        DEFAULT_JAVA_OPTIONS,
        DEFAULT_JAVAC_OPTIONS,
        JavaBuckConfig.of(FakeBuckConfig.builder().build()),
        defaultCxxPlatform,
        cxxPlatforms);
  }

  public JavaBinaryRuleBuilder(BuildTarget target) {
    this(target, CxxPlatformUtils.DEFAULT_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORMS);
  }

  public JavaBinaryRuleBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public JavaBinaryRuleBuilder setMainClass(String mainClass) {
    getArgForPopulating().setMainClass(Optional.of(mainClass));
    return this;
  }

  public JavaBinaryRuleBuilder addTest(BuildTarget test) {
    getArgForPopulating().addTests(test);
    return this;
  }

  public JavaBinaryRuleBuilder setDefaultCxxPlatform(Flavor flavor) {
    getArgForPopulating().setDefaultCxxPlatform(flavor);
    return this;
  }
}

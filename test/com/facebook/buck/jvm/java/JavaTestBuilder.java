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
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_CONFIG;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.TestCxxPlatformsProviderFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.toolchain.JavaCxxPlatformProvider;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class JavaTestBuilder
    extends AbstractNodeBuilder<
        JavaTestDescriptionArg.Builder, JavaTestDescriptionArg, JavaTestDescription, JavaTest> {
  private JavaTestBuilder(BuildTarget target, JavaBuckConfig javaBuckConfig) {
    super(
        new JavaTestDescription(
            new ToolchainProviderBuilder()
                .withToolchain(TestCxxPlatformsProviderFactory.createDefaultCxxPlatformsProvider())
                .withToolchain(
                    JavaCxxPlatformProvider.DEFAULT_NAME,
                    JavaCxxPlatformProvider.of(CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM))
                .withToolchain(
                    JavacOptionsProvider.DEFAULT_NAME,
                    JavacOptionsProvider.of(DEFAULT_JAVAC_OPTIONS))
                .withToolchain(
                    JavaOptionsProvider.DEFAULT_NAME,
                    JavaOptionsProvider.of(DEFAULT_JAVA_OPTIONS, DEFAULT_JAVA_OPTIONS))
                .withToolchain(
                    JavaToolchain.DEFAULT_NAME, JavaCompilationConstants.DEFAULT_JAVA_TOOLCHAIN)
                .build(),
            javaBuckConfig),
        target);
  }

  public static JavaTestBuilder createBuilder(BuildTarget target) {
    return new JavaTestBuilder(target, DEFAULT_JAVA_CONFIG);
  }

  public static JavaTestBuilder createBuilder(BuildTarget target, JavaBuckConfig javaBuckConfig) {
    return new JavaTestBuilder(target, javaBuckConfig);
  }

  public JavaTestBuilder addDep(BuildTarget rule) {
    getArgForPopulating().addDeps(rule);
    return this;
  }

  public JavaTestBuilder addProvidedDep(BuildTarget rule) {
    getArgForPopulating().addProvidedDeps(rule);
    return this;
  }

  public JavaTestBuilder addSrc(Path path) {
    getArgForPopulating().addSrcs(PathSourcePath.of(new FakeProjectFilesystem(), path));
    return this;
  }

  public JavaTestBuilder setVmArgs(@Nullable ImmutableList<StringWithMacros> vmArgs) {
    getArgForPopulating().setVmArgs(Optional.ofNullable(vmArgs).orElse(ImmutableList.of()));
    return this;
  }
}

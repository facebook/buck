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

package com.facebook.buck.android;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_CONFIG;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import java.nio.file.Path;
import java.util.Optional;

public class AndroidLibraryBuilder
    extends AbstractNodeBuilder<
        AndroidLibraryDescriptionArg.Builder, AndroidLibraryDescriptionArg,
        AndroidLibraryDescription, AndroidLibrary> {

  private static final AndroidLibraryCompilerFactory JAVA_ONLY_COMPILER_FACTORY =
      language ->
          new JavaConfiguredCompilerFactory(DEFAULT_JAVA_CONFIG, AndroidClasspathProvider::new);

  private AndroidLibraryBuilder(BuildTarget target, JavaBuckConfig javaBuckConfig) {
    super(
        new AndroidLibraryDescription(javaBuckConfig, JAVA_ONLY_COMPILER_FACTORY),
        target,
        new FakeProjectFilesystem(),
        createToolchainProviderForAndroidLibrary(),
        null);
  }

  public static AndroidLibraryBuilder createBuilder(BuildTarget target) {
    return new AndroidLibraryBuilder(target, DEFAULT_JAVA_CONFIG);
  }

  public static AndroidLibraryBuilder createBuilder(
      BuildTarget target, JavaBuckConfig javaBuckConfig) {
    return new AndroidLibraryBuilder(target, javaBuckConfig);
  }

  public static ToolchainProvider createToolchainProviderForAndroidLibrary() {
    return new ToolchainProviderBuilder()
        .withToolchain(
            JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(ANDROID_JAVAC_OPTIONS))
        .withToolchain(
            AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
        .build();
  }

  public AndroidLibraryBuilder addProcessor(String processor) {
    getArgForPopulating().addAnnotationProcessors(processor);
    return this;
  }

  public AndroidLibraryBuilder addProcessorBuildTarget(BuildTarget processorRule) {
    getArgForPopulating().addAnnotationProcessorDeps(processorRule);
    return this;
  }

  public AndroidLibraryBuilder setManifestFile(SourcePath manifestFile) {
    getArgForPopulating().setManifest(Optional.of(manifestFile));
    return this;
  }

  public AndroidLibraryBuilder addDep(BuildTarget rule) {
    getArgForPopulating().addDeps(rule);
    return this;
  }

  public AndroidLibraryBuilder setDepsQuery(Query query) {
    getArgForPopulating().setDepsQuery(Optional.of(query));
    return this;
  }

  public AndroidLibraryBuilder addProvidedDep(BuildTarget rule) {
    getArgForPopulating().addProvidedDeps(rule);
    return this;
  }

  public AndroidLibraryBuilder setProvidedDepsQuery(Query query) {
    getArgForPopulating().setProvidedDepsQuery(Optional.of(query));
    return this;
  }

  public AndroidLibraryBuilder addSrc(Path path) {
    getArgForPopulating().addSrcs(PathSourcePath.of(new FakeProjectFilesystem(), path));
    return this;
  }
}

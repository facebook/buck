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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.toolchain.JavaToolchain;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;

public class JavaLibraryBuilder
    extends AbstractNodeBuilder<
        JavaLibraryDescriptionArg.Builder,
        JavaLibraryDescriptionArg,
        JavaLibraryDescription,
        DefaultJavaLibrary> {

  private final ProjectFilesystem projectFilesystem;

  protected JavaLibraryBuilder(
      BuildTarget target, ProjectFilesystem projectFilesystem, HashCode hashCode) {
    super(
        new JavaLibraryDescription(createToolchainProviderForJavaLibrary(), DEFAULT_JAVA_CONFIG),
        target,
        projectFilesystem,
        createToolchainProviderForJavaLibrary(),
        hashCode);
    this.projectFilesystem = projectFilesystem;
  }

  protected JavaLibraryBuilder(
      BuildTarget target,
      JavaBuckConfig javaBuckConfig,
      ProjectFilesystem projectFilesystem,
      HashCode hashCode) {
    super(
        new JavaLibraryDescription(createToolchainProviderForJavaLibrary(), javaBuckConfig),
        target,
        projectFilesystem,
        createToolchainProviderForJavaLibrary(),
        hashCode);
    this.projectFilesystem = projectFilesystem;
  }

  public static JavaLibraryBuilder createBuilder(String qualifiedTarget) {
    return createBuilder(BuildTargetFactory.newInstance(qualifiedTarget));
  }

  public static JavaLibraryBuilder createBuilder(BuildTarget target) {
    return new JavaLibraryBuilder(target, new FakeProjectFilesystem(), null);
  }

  public static JavaLibraryBuilder createBuilder(
      BuildTarget target, JavaBuckConfig javaBuckConfig) {
    return new JavaLibraryBuilder(target, javaBuckConfig, new FakeProjectFilesystem(), null);
  }

  public static JavaLibraryBuilder createBuilder(
      BuildTarget target, ProjectFilesystem projectFilesystem) {
    return new JavaLibraryBuilder(target, projectFilesystem, null);
  }

  public static JavaLibraryBuilder createBuilder(
      BuildTarget target, JavaBuckConfig javaBuckConfig, ProjectFilesystem projectFilesystem) {
    return new JavaLibraryBuilder(target, javaBuckConfig, projectFilesystem, null);
  }

  public static JavaLibraryBuilder createBuilder(BuildTarget target, HashCode hashCode) {
    return new JavaLibraryBuilder(target, new FakeProjectFilesystem(), hashCode);
  }

  public JavaLibraryBuilder addDep(BuildTarget rule) {
    getArgForPopulating().addDeps(rule);
    return this;
  }

  public JavaLibraryBuilder addAnnotationProcessorDep(BuildTarget rule) {
    getArgForPopulating().addAnnotationProcessorDeps(rule);
    return this;
  }

  public JavaLibraryBuilder addExportedDep(BuildTarget rule) {
    getArgForPopulating().addExportedDeps(rule);
    return this;
  }

  public JavaLibraryBuilder addProvidedDep(BuildTarget rule) {
    getArgForPopulating().addProvidedDeps(rule);
    return this;
  }

  public JavaLibraryBuilder addResource(SourcePath sourcePath) {
    getArgForPopulating().addResources(sourcePath);
    return this;
  }

  public JavaLibraryBuilder setResourcesRoot(Path root) {
    getArgForPopulating().setResourcesRoot(Optional.of(root));
    return this;
  }

  public JavaLibraryBuilder setMavenCoords(String mavenCoords) {
    getArgForPopulating().setMavenCoords(Optional.of(mavenCoords));
    return this;
  }

  public JavaLibraryBuilder addSrc(SourcePath path) {
    getArgForPopulating().addSrcs(path);
    return this;
  }

  public JavaLibraryBuilder addSrc(Path path) {
    return addSrc(PathSourcePath.of(projectFilesystem, path));
  }

  public JavaLibraryBuilder addSrcTarget(BuildTarget target) {
    return addSrc(DefaultBuildTargetSourcePath.of(target));
  }

  public JavaLibraryBuilder setProguardConfig(SourcePath proguardConfig) {
    getArgForPopulating().setProguardConfig(Optional.of(proguardConfig));
    return this;
  }

  public JavaLibraryBuilder setCompiler(SourcePath javac) {
    Either<BuiltInJavac, SourcePath> value = Either.ofRight(javac);
    getArgForPopulating().setCompiler(Optional.of(value));
    return this;
  }

  public JavaLibraryBuilder setSourceLevel(String sourceLevel) {
    getArgForPopulating().setSource(Optional.of(sourceLevel));
    return this;
  }

  public JavaLibraryBuilder setTargetLevel(String targetLevel) {
    getArgForPopulating().setTarget(Optional.of(targetLevel));
    return this;
  }

  public JavaLibraryBuilder setAnnotationProcessors(ImmutableSet<String> annotationProcessors) {
    getArgForPopulating().setAnnotationProcessors(annotationProcessors);
    return this;
  }

  public JavaLibraryBuilder addTest(BuildTarget test) {
    getArgForPopulating().addTests(test);
    return this;
  }

  public static ToolchainProvider createToolchainProviderForJavaLibrary() {
    return new ToolchainProviderBuilder()
        .withToolchain(
            JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(DEFAULT_JAVAC_OPTIONS))
        .withToolchain(JavaToolchain.DEFAULT_NAME, JavaCompilationConstants.DEFAULT_JAVA_TOOLCHAIN)
        .build();
  }
}

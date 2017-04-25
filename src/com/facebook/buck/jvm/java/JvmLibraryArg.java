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
package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@SuppressFieldNotInitialized
public class JvmLibraryArg extends AbstractDescriptionArg {
  public Optional<String> source;
  public Optional<String> target;
  public Optional<String> javaVersion;
  public Optional<Path> javac;
  public Optional<SourcePath> javacJar;
  public Optional<String> compilerClassName;
  public Optional<Either<BuiltInJavac, SourcePath>> compiler;
  public ImmutableList<String> extraArguments = ImmutableList.of();
  public ImmutableSet<Pattern> removeClasses = ImmutableSet.of();
  public ImmutableSortedSet<BuildTarget> annotationProcessorDeps = ImmutableSortedSet.of();
  public ImmutableList<String> annotationProcessorParams = ImmutableList.of();
  public ImmutableSet<String> annotationProcessors = ImmutableSet.of();
  public Optional<Boolean> annotationProcessorOnly;
  public ImmutableList<BuildTarget> plugins = ImmutableList.of();
  public Optional<Boolean> generateAbiFromSource;

  @Nullable
  public JavacSpec getJavacSpec() {
    if (!compiler.isPresent() && !javac.isPresent() && !javacJar.isPresent()) {
      return null;
    }

    return JavacSpec.builder()
        .setCompiler(compiler)
        .setJavacPath(
            javac.isPresent() ? Optional.of(Either.ofLeft(javac.get())) : Optional.empty())
        .setJavacJarPath(javacJar)
        .setCompilerClassName(compilerClassName)
        .build();
  }

  public AnnotationProcessingParams buildAnnotationProcessingParams(
      BuildTarget owner,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      Set<String> safeAnnotationProcessors) {
    if (annotationProcessors.isEmpty() && plugins.isEmpty() && annotationProcessorDeps.isEmpty()) {
      return AnnotationProcessingParams.EMPTY;
    }

    AnnotationProcessingParams.Builder builder = AnnotationProcessingParams.builder();
    builder.setOwnerTarget(owner);
    builder.setLegacySafeAnnotationProcessors(safeAnnotationProcessors);
    builder.setProjectFilesystem(filesystem);

    addLegacyProcessors(builder, resolver);
    addProcessors(builder, resolver, owner);

    for (String processorParam : annotationProcessorParams) {
      builder.addParameters(processorParam);
    }
    builder.setProcessOnly(annotationProcessorOnly.orElse(Boolean.FALSE));

    return builder.build();
  }

  void addProcessors(
      AnnotationProcessingParams.Builder builder, BuildRuleResolver resolver, BuildTarget owner) {
    for (BuildTarget pluginTarget : plugins) {
      BuildRule pluginRule = resolver.getRule(pluginTarget);
      if (!(pluginRule instanceof JavaAnnotationProcessor)) {
        throw new HumanReadableException(
            String.format(
                "%s: only java_annotation_processor rules can be specified as plugins. "
                    + "%s is not a java_annotation_processor.",
                owner, pluginTarget));
      }
      JavaAnnotationProcessor plugin = (JavaAnnotationProcessor) pluginRule;
      builder.addModernProcessors(plugin.getProcessorProperties());
    }
  }

  void addLegacyProcessors(AnnotationProcessingParams.Builder builder, BuildRuleResolver resolver) {
    builder.setLegacyAnnotationProcessorNames(annotationProcessors);
    ImmutableSortedSet<BuildRule> processorDeps = resolver.getAllRules(annotationProcessorDeps);
    for (BuildRule processorDep : processorDeps) {
      builder.addLegacyAnnotationProcessorDeps(processorDep);
    }
  }
}

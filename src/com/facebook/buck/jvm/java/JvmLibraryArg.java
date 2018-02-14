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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBuckConfig.SourceAbiVerificationMode;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public interface JvmLibraryArg extends CommonDescriptionArg, MaybeRequiredForSourceOnlyAbiArg {
  Optional<String> getSource();

  Optional<String> getTarget();

  Optional<String> getJavaVersion();

  Optional<Path> getJavac();

  Optional<SourcePath> getJavacJar();

  Optional<String> getCompilerClassName();

  Optional<Either<BuiltInJavac, SourcePath>> getCompiler();

  ImmutableList<String> getExtraArguments();

  ImmutableSet<Pattern> getRemoveClasses();

  @Value.NaturalOrder
  ImmutableSortedSet<BuildTarget> getAnnotationProcessorDeps();

  ImmutableList<String> getAnnotationProcessorParams();

  ImmutableSet<String> getAnnotationProcessors();

  Optional<Boolean> getAnnotationProcessorOnly();

  ImmutableList<BuildTarget> getPlugins();

  Optional<AbiGenerationMode> getAbiGenerationMode();

  Optional<CompileAgainstLibraryType> getCompileAgainst();

  Optional<SourceAbiVerificationMode> getSourceAbiVerificationMode();

  @Value.Derived
  @Nullable
  default JavacSpec getJavacSpec() {
    if (!getCompiler().isPresent() && !getJavac().isPresent() && !getJavacJar().isPresent()) {
      return null;
    }

    return JavacSpec.builder()
        .setCompiler(getCompiler())
        .setJavacPath(
            getJavac().isPresent()
                ? Optional.of(Either.ofLeft(getJavac().get()))
                : Optional.empty())
        .setJavacJarPath(getJavacJar())
        .setCompilerClassName(getCompilerClassName())
        .build();
  }

  @Value.Derived
  default AnnotationProcessingParams buildAnnotationProcessingParams(
      BuildTarget owner,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver,
      Set<String> safeAnnotationProcessors) {
    if (getAnnotationProcessors().isEmpty()
        && getPlugins().isEmpty()
        && getAnnotationProcessorDeps().isEmpty()) {
      return AnnotationProcessingParams.EMPTY;
    }

    AnnotationProcessingParams.Builder builder = AnnotationProcessingParams.builder();
    builder.setLegacySafeAnnotationProcessors(safeAnnotationProcessors);
    builder.setProjectFilesystem(filesystem);

    addLegacyProcessors(builder, resolver);
    addProcessors(builder, resolver, owner);

    for (String processorParam : getAnnotationProcessorParams()) {
      builder.addParameters(processorParam);
    }
    builder.setProcessOnly(getAnnotationProcessorOnly().orElse(Boolean.FALSE));

    return builder.build();
  }

  default void addProcessors(
      AnnotationProcessingParams.Builder builder, BuildRuleResolver resolver, BuildTarget owner) {
    for (BuildTarget pluginTarget : getPlugins()) {
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

  default void addLegacyProcessors(
      AnnotationProcessingParams.Builder builder, BuildRuleResolver resolver) {
    builder.setLegacyAnnotationProcessorNames(getAnnotationProcessors());
    ImmutableSortedSet<BuildRule> processorDeps =
        resolver.getAllRules(getAnnotationProcessorDeps());
    for (BuildRule processorDep : processorDeps) {
      builder.addLegacyAnnotationProcessorDeps(processorDep);
    }
  }
}

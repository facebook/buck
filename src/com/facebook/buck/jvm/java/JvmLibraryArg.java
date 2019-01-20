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

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig.SourceAbiVerificationMode;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.jvm.java.JavacSpec.Builder;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public interface JvmLibraryArg extends CommonDescriptionArg, MaybeRequiredForSourceOnlyAbiArg {
  Optional<String> getSource();

  Optional<String> getTarget();

  Optional<String> getJavaVersion();

  Optional<SourcePath> getJavac();

  Optional<SourcePath> getJavacJar();

  Optional<String> getCompilerClassName();

  // TODO(cjhopman): Remove the compiler argument. It's behavior is an odd mix of javac and
  // javac_jar.
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

  Optional<UnusedDependenciesAction> getOnUnusedDependencies();

  /** Verifies some preconditions on the arguments. */
  @Value.Check
  default void verify() {
    boolean javacJarIsSet = getJavacJar().isPresent();
    boolean javacIsSet = getJavac().isPresent();

    if (javacIsSet && javacJarIsSet) {
      throw new HumanReadableException("Can only set one of javac/javac_jar.");
    }
  }

  default boolean hasJavacSpec() {
    return getCompiler().isPresent() || getJavac().isPresent() || getJavacJar().isPresent();
  }

  @Value.Derived
  @Nullable
  default JavacSpec getJavacSpec(SourcePathRuleFinder ruleFinder) {
    if (!hasJavacSpec()) {
      return null;
    }

    Builder builder = JavacSpec.builder();
    builder.setCompilerClassName(getCompilerClassName());

    if (getCompiler().isPresent()) {
      if (getCompiler().get().isRight()) {
        SourcePath sourcePath = getCompiler().get().getRight();
        if (isValidJavacJar(sourcePath, ruleFinder.getRule(sourcePath))) {
          builder.setJavacJarPath(sourcePath);
        } else {
          builder.setJavacPath(sourcePath);
        }
      }
      // compiler's left case is handled as just an empty spec.
    } else {
      builder.setJavacPath(getJavac());
      builder.setJavacJarPath(getJavacJar());
    }
    return builder.build();
  }

  default boolean isValidJavacJar(SourcePath sourcePath, Optional<BuildRule> possibleRule) {
    if (!possibleRule.isPresent() || !(possibleRule.get() instanceof JavaLibrary)) {
      return false;
    }
    SourcePath javacJarPath = possibleRule.get().getSourcePathToOutput();
    if (javacJarPath == null) {
      throw new HumanReadableException(
          String.format(
              "%s isn't a valid value for compiler because it isn't a java library", sourcePath));
    }
    return true;
  }

  @Value.Derived
  default AnnotationProcessingParams buildAnnotationProcessingParams(
      BuildTarget owner, BuildRuleResolver resolver) {
    if (getAnnotationProcessors().isEmpty()
        && getPlugins().isEmpty()
        && getAnnotationProcessorDeps().isEmpty()) {
      return AnnotationProcessingParams.EMPTY;
    }

    AbstractAnnotationProcessingParams.Builder builder = AnnotationProcessingParams.builder();
    addLegacyProcessors(builder, resolver);
    addProcessors(builder, resolver, owner);
    for (String processorParam : getAnnotationProcessorParams()) {
      builder.addParameters(processorParam);
    }
    builder.setProcessOnly(getAnnotationProcessorOnly().orElse(Boolean.FALSE));

    return builder.build();
  }

  default void addProcessors(
      AbstractAnnotationProcessingParams.Builder builder,
      BuildRuleResolver resolver,
      BuildTarget owner) {
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
      AbstractAnnotationProcessingParams.Builder builder, BuildRuleResolver resolver) {
    builder.setLegacyAnnotationProcessorNames(getAnnotationProcessors());
    ImmutableSortedSet<BuildRule> processorDeps =
        resolver.getAllRules(getAnnotationProcessorDeps());
    for (BuildRule processorDep : processorDeps) {
      builder.addLegacyAnnotationProcessorDeps(processorDep);
    }
  }
}

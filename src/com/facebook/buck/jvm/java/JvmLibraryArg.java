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

import static com.facebook.buck.jvm.java.AbstractJavacPluginProperties.Type.ANNOTATION_PROCESSOR;
import static com.facebook.buck.jvm.java.AbstractJavacPluginProperties.Type.JAVAC_PLUGIN;

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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

  ImmutableList<String> getJavaPluginParams();

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

  default List<BuildRule> getPluginsOf(
      BuildRuleResolver resolver, final AbstractJavacPluginProperties.Type type) {
    return getPlugins().stream()
        .map(pluginTarget -> resolver.getRule(pluginTarget))
        .filter(
            pluginRule -> ((JavacPlugin) pluginRule).getUnresolvedProperties().getType() == type)
        .collect(Collectors.toList());
  }

  default void addPlugins(
      AbstractJavacPluginParams.Builder builder,
      BuildRuleResolver resolver,
      BuildTarget owner,
      AbstractJavacPluginProperties.Type type) {
    for (BuildTarget pluginTarget : getPlugins()) {
      BuildRule pluginRule = resolver.getRule(pluginTarget);
      if (!(pluginRule instanceof JavacPlugin)) {
        throw new HumanReadableException(
            String.format(
                "%s: only java_annotation_processor or java_plugin rules can be specified "
                    + "as plugins. %s is not a java_annotation_processor nor java_plugin.",
                owner, pluginTarget));
      }
      JavacPlugin javacPluginRule = (JavacPlugin) pluginRule;
      if (javacPluginRule.getUnresolvedProperties().getType() == type) {
        builder.addPluginProperties(javacPluginRule.getPluginProperties());
      }
    }
  }

  @Value.Derived
  default JavacPluginParams buildStandardJavacParams(
      BuildTarget owner, BuildRuleResolver resolver) {

    if (getPluginsOf(resolver, JAVAC_PLUGIN).isEmpty()) {
      return JavacPluginParams.EMPTY;
    }

    AbstractJavacPluginParams.Builder builder = JavacPluginParams.builder();
    addPlugins(builder, resolver, owner, JAVAC_PLUGIN);
    for (String processorParam : getJavaPluginParams()) {
      builder.addParameters(processorParam);
    }
    return builder.build();
  }

  default void addLegacyProcessors(
      AbstractJavacPluginParams.Builder builder, BuildRuleResolver resolver) {
    builder.setLegacyAnnotationProcessorNames(getAnnotationProcessors());
    ImmutableSortedSet<BuildRule> processorDeps =
        resolver.getAllRules(getAnnotationProcessorDeps());
    for (BuildRule processorDep : processorDeps) {
      builder.addLegacyAnnotationProcessorDeps(processorDep);
    }
  }

  @Value.Derived
  default JavacPluginParams buildJavaAnnotationProcessorParams(
      BuildTarget owner, BuildRuleResolver resolver) {
    if (getAnnotationProcessors().isEmpty()
        && getAnnotationProcessorDeps().isEmpty()
        && getPluginsOf(resolver, ANNOTATION_PROCESSOR).isEmpty()) {
      return JavacPluginParams.EMPTY;
    }

    AbstractJavacPluginParams.Builder builder = JavacPluginParams.builder();
    addLegacyProcessors(builder, resolver);
    addPlugins(builder, resolver, owner, ANNOTATION_PROCESSOR);
    for (String processorParam : getAnnotationProcessorParams()) {
      builder.addParameters(processorParam);
    }
    builder.setProcessOnly(getAnnotationProcessorOnly().orElse(Boolean.FALSE));

    return builder.build();
  }
}

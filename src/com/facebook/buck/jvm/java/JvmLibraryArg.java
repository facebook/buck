/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavacPluginProperties.Type.ANNOTATION_PROCESSOR;
import static com.facebook.buck.jvm.java.JavacPluginProperties.Type.JAVAC_PLUGIN;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.javacd.model.AbiGenerationMode;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.jvm.java.JavaBuckConfig.SourceAbiVerificationMode;
import com.facebook.buck.jvm.java.JavacSpec.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** JVM library rule constructor arg */
public interface JvmLibraryArg extends BuildRuleArg, MaybeRequiredForSourceOnlyAbiArg {

  Optional<String> getSource();

  Optional<String> getTarget();

  Optional<String> getJavaVersion();

  Optional<SourcePath> getJavac();

  Optional<SourcePath> getJavacJar();

  Optional<String> getCompilerClassName();

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

  Optional<UnusedDependenciesParams.UnusedDependenciesAction> getOnUnusedDependencies();

  Optional<Boolean> getNeverMarkAsUnusedDependency();

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
    return getJavac().isPresent() || getJavacJar().isPresent();
  }

  @Value.Derived
  @Nullable
  default JavacSpec getJavacSpec() {
    if (!hasJavacSpec()) {
      return null;
    }

    Builder builder = JavacSpec.builder();
    builder.setCompilerClassName(getCompilerClassName());
    builder.setJavacPath(getJavac());
    builder.setJavacJarPath(getJavacJar());

    return builder.build();
  }

  default List<BuildRule> getPluginsOf(
      BuildRuleResolver resolver, final JavacPluginProperties.Type type) {
    return getPlugins().stream()
        .map(resolver::getRule)
        .filter(
            pluginRule -> ((JavacPlugin) pluginRule).getUnresolvedProperties().getType() == type)
        .collect(Collectors.toList());
  }

  default void addPlugins(
      JavacPluginParams.Builder builder,
      BuildRuleResolver resolver,
      BuildTarget owner,
      JavacPluginProperties.Type type) {
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
      BuildTarget owner, BuildRuleResolver resolver, AbsPath ruleCellRoot) {

    if (getPluginsOf(resolver, JAVAC_PLUGIN).isEmpty()) {
      return JavacPluginParams.EMPTY;
    }

    JavacPluginParams.Builder builder = JavacPluginParams.builder();
    addPlugins(builder, resolver, owner, JAVAC_PLUGIN);
    for (String processorParam : getJavaPluginParams()) {
      builder.addParameters(processorParam);
    }
    return builder.build(resolver.getSourcePathResolver(), ruleCellRoot);
  }

  default void addLegacyProcessors(JavacPluginParams.Builder builder, BuildRuleResolver resolver) {
    builder.setLegacyAnnotationProcessorNames(getAnnotationProcessors());
    ImmutableSortedSet<BuildRule> processorDeps =
        resolver.getAllRules(getAnnotationProcessorDeps());
    for (BuildRule processorDep : processorDeps) {
      builder.addLegacyAnnotationProcessorDeps(processorDep);
    }
  }

  @Value.Derived
  default JavacPluginParams buildJavaAnnotationProcessorParams(
      BuildTarget owner, BuildRuleResolver resolver, AbsPath ruleCellRoot) {
    if (getAnnotationProcessors().isEmpty()
        && getAnnotationProcessorDeps().isEmpty()
        && getPluginsOf(resolver, ANNOTATION_PROCESSOR).isEmpty()) {
      return JavacPluginParams.EMPTY;
    }

    JavacPluginParams.Builder builder = JavacPluginParams.builder();
    addLegacyProcessors(builder, resolver);
    addPlugins(builder, resolver, owner, ANNOTATION_PROCESSOR);
    for (String processorParam : getAnnotationProcessorParams()) {
      builder.addParameters(processorParam);
    }
    builder.setProcessOnly(getAnnotationProcessorOnly().orElse(Boolean.FALSE));

    return builder.build(resolver.getSourcePathResolver(), ruleCellRoot);
  }
}

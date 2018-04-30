/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Information for annotation processing.
 *
 * <p>Annotation processing involves a set of processors, their classpath(s), and a few other
 * command-line options for javac. We want to be able to specify all this various information in a
 * BUCK configuration file and use it when we generate the javac command. This facilitates threading
 * the information through buck in a more descriptive package rather than passing all the components
 * separately.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAnnotationProcessingParams implements AddsToRuleKey {
  public static final AnnotationProcessingParams EMPTY =
      AnnotationProcessingParams.builder()
          .setProjectFilesystem(null)
          .setModernProcessors(ImmutableList.of())
          .setParameters(ImmutableSortedSet.of())
          .setProcessOnly(false)
          .build();

  @Nullable
  protected abstract ProjectFilesystem getProjectFilesystem();

  protected abstract Set<String> getLegacySafeAnnotationProcessors();

  protected abstract ImmutableList<BuildRule> getLegacyAnnotationProcessorDeps();

  @Value.NaturalOrder
  protected abstract ImmutableSortedSet<String> getLegacyAnnotationProcessorNames();

  @Value.Lazy
  @AddToRuleKey
  protected ImmutableList<JavacPluginProperties> getLegacyProcessors() {
    JavacPluginProperties.Builder legacySafeProcessorsBuilder =
        JavacPluginProperties.builder()
            .setCanReuseClassLoader(true)
            .setDoesNotAffectAbi(false)
            .setSupportsAbiGenerationFromSource(false)
            .setProcessorNames(
                Sets.intersection(
                    getLegacyAnnotationProcessorNames(), getLegacySafeAnnotationProcessors()));

    JavacPluginProperties.Builder legacyUnsafeProcessorsBuilder =
        JavacPluginProperties.builder()
            .setCanReuseClassLoader(false)
            .setDoesNotAffectAbi(false)
            .setSupportsAbiGenerationFromSource(false)
            .setProcessorNames(
                Sets.difference(
                    getLegacyAnnotationProcessorNames(), getLegacySafeAnnotationProcessors()));

    for (BuildRule dep : getLegacyAnnotationProcessorDeps()) {
      legacySafeProcessorsBuilder.addDep(dep);
      legacyUnsafeProcessorsBuilder.addDep(dep);
    }

    JavacPluginProperties legacySafeProcessors = legacySafeProcessorsBuilder.build();
    JavacPluginProperties legacyUnsafeProcessors = legacyUnsafeProcessorsBuilder.build();

    ImmutableList.Builder<JavacPluginProperties> resultBuilder = ImmutableList.builder();
    if (!legacySafeProcessors.isEmpty()) {
      resultBuilder.add(legacySafeProcessors);
    }
    if (!legacyUnsafeProcessors.isEmpty()) {
      resultBuilder.add(legacyUnsafeProcessors);
    }

    return resultBuilder.build();
  }

  @AddToRuleKey
  protected abstract ImmutableList<ResolvedJavacPluginProperties> getModernProcessors();

  @Value.Check
  protected void check() {
    if (!isEmpty()) {
      Preconditions.checkNotNull(getProjectFilesystem());
    }
  }

  public boolean isEmpty() {
    return getModernProcessors().isEmpty()
        && getLegacyProcessors().isEmpty()
        && getParameters().isEmpty();
  }

  public ImmutableList<ResolvedJavacPluginProperties> getAnnotationProcessors(
      ProjectFilesystem filesystem, SourcePathResolver resolver) {
    if (getLegacyProcessors().isEmpty()) {
      return getModernProcessors();
    }

    return Stream.concat(
            getLegacyProcessors()
                .stream()
                .map(processorGroup -> processorGroup.resolve(filesystem, resolver)),
            getModernProcessors().stream())
        .collect(ImmutableList.toImmutableList());
  }

  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<String> getParameters();

  public ImmutableSortedSet<SourcePath> getInputs() {
    return Stream.concat(
            getLegacyProcessors().stream().map(JavacPluginProperties::getInputs),
            getModernProcessors().stream().map(ResolvedJavacPluginProperties::getInputs))
        .flatMap(Collection::stream)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Value.Default
  @AddToRuleKey
  protected boolean getProcessOnly() {
    return false;
  }
}

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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.immutables.value.Value;

/**
 * Information for javac plugins (includes annotation processors).
 *
 * <p>Javac Plugins involves a set of plugin properties, their classpath(s), and a few other
 * command-line options for javac. We want to be able to specify all this various information in a
 * BUCK configuration file and use it when we generate the javac command. This facilitates threading
 * the information through buck in a more descriptive package rather than passing all the components
 * separately.
 */
@BuckStyleValueWithBuilder
public abstract class JavacPluginParams implements AddsToRuleKey {

  public static final JavacPluginParams EMPTY = builder().build(null, null);

  @AddToRuleKey
  public abstract ImmutableList<ResolvedJavacPluginProperties> getPluginProperties();

  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<String> getParameters();

  @Value.Default
  @AddToRuleKey
  public boolean getProcessOnly() {
    return false;
  }

  public boolean isEmpty() {
    return getPluginProperties().isEmpty() && getParameters().isEmpty();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Creates {@link JavacPluginParams} with ABI processors only */
  public JavacPluginParams withAbiProcessorsOnly(
      SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    return JavacPluginParams.builder()
        .setPluginProperties(
            getPluginProperties().stream()
                .filter(properties -> !properties.getDoesNotAffectAbi())
                .collect(ImmutableList.toImmutableList()))
        .setParameters(getParameters())
        .setProcessOnly(getProcessOnly())
        .build(resolver, ruleCellRoot);
  }

  /** A customized Builder for JavacPluginParams. */
  @org.immutables.builder.Builder.AccessibleFields
  public static class Builder extends ImmutableJavacPluginParams.Builder {

    private Set<String> legacyAnnotationProcessorNames = new LinkedHashSet<>();
    private List<BuildRule> legacyAnnotationProcessorDeps = new ArrayList<>();

    private List<ResolvedJavacPluginProperties> resolveLegacyProcessors(
        SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
      if (getLegacyProcessors().isEmpty()) {
        return ImmutableList.of();
      }

      return getLegacyProcessors().stream()
          .map(jpp -> jpp.resolve(resolver, ruleCellRoot))
          .collect(ImmutableList.toImmutableList());
    }

    private ImmutableList<JavacPluginProperties> getLegacyProcessors() {
      JavacPluginProperties.Builder legacySafeProcessorsBuilder =
          JavacPluginProperties.builder()
              .setType(ANNOTATION_PROCESSOR)
              .setCanReuseClassLoader(true)
              .setDoesNotAffectAbi(false)
              .setSupportsAbiGenerationFromSource(false)
              .setProcessorNames(legacyAnnotationProcessorNames);

      for (BuildRule dep : legacyAnnotationProcessorDeps) {
        legacySafeProcessorsBuilder.addDep(dep);
      }

      JavacPluginProperties legacySafeProcessors = legacySafeProcessorsBuilder.build();

      ImmutableList.Builder<JavacPluginProperties> resultBuilder = ImmutableList.builder();
      if (!legacySafeProcessors.isEmpty()) {
        resultBuilder.add(legacySafeProcessors);
      }

      return resultBuilder.build();
    }

    public JavacPluginParams build(SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
      addAllPluginProperties(resolveLegacyProcessors(resolver, ruleCellRoot));
      return super.build();
    }

    @Override
    public JavacPluginParams build() {
      throw new UnsupportedOperationException(
          "Use build method with SourcePathResolverAdapter and AbsPath as params");
    }

    public Builder setLegacyAnnotationProcessorNames(Collection<String> annotationProcessors) {
      legacyAnnotationProcessorNames = ImmutableSet.copyOf(annotationProcessors);
      return this;
    }

    public Builder addLegacyAnnotationProcessorDeps(BuildRule dep) {
      legacyAnnotationProcessorDeps.add(dep);
      return this;
    }
  }
}

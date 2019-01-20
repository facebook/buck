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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/**
 * Describes the properties of a plugin to javac, either a {@link
 * javax.annotation.processing.Processor} or a {@link com.sun.source.util.Plugin}. The classpath and
 * input properties in particular can be expensive to compute, so this object should be cached when
 * possible.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJavacPluginProperties implements AddsToRuleKey {
  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<String> getProcessorNames();

  @Value.NaturalOrder
  @AddToRuleKey
  public abstract ImmutableSortedSet<SourcePath> getInputs();

  @Value.NaturalOrder
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  // classpathEntries is not necessary because it is derived from inputs
  public abstract ImmutableSortedSet<SourcePath> getClasspathEntries();

  @AddToRuleKey
  public abstract boolean getCanReuseClassLoader();

  @AddToRuleKey
  public abstract boolean getDoesNotAffectAbi();

  @AddToRuleKey
  public abstract boolean getSupportsAbiGenerationFromSource();

  public boolean isEmpty() {
    return getProcessorNames().isEmpty() && getClasspathEntries().isEmpty();
  }

  public ResolvedJavacPluginProperties resolve() {
    return new ResolvedJavacPluginProperties((JavacPluginProperties) this);
  }

  abstract static class Builder {
    public abstract Builder addInputs(SourcePath... elements);

    public abstract Builder addClasspathEntries(SourcePath... elements);

    public abstract Builder addAllClasspathEntries(Iterable<? extends SourcePath> elements);

    public abstract JavacPluginProperties build();

    public JavacPluginProperties.Builder addDep(BuildRule rule) {
      if (rule.getClass().isAnnotationPresent(BuildsAnnotationProcessor.class)) {
        SourcePath outputSourcePath = rule.getSourcePathToOutput();
        if (outputSourcePath != null) {
          addInputs(outputSourcePath);
          addClasspathEntries(outputSourcePath);
        }
      } else if (rule instanceof HasClasspathEntries) {
        HasClasspathEntries hasClasspathEntries = (HasClasspathEntries) rule;
        ImmutableSet<JavaLibrary> entries = hasClasspathEntries.getTransitiveClasspathDeps();
        for (JavaLibrary entry : entries) {
          // Libraries may merely re-export other libraries' class paths, instead of having one
          // itself. In such cases do not add the library itself, and just move on.
          if (entry.getSourcePathToOutput() != null) {
            addInputs(entry.getSourcePathToOutput());
          }
        }
        addAllClasspathEntries(hasClasspathEntries.getTransitiveClasspaths());
      } else {
        throw new HumanReadableException(
            "%s is not a legal dependency for an annotation processor or compiler plugin; "
                + "must refer only to prebuilt jar, java binary, or java library targets.",
            rule.getFullyQualifiedName());
      }
      return (JavacPluginProperties.Builder) this;
    }
  }
}

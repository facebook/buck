/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Information for annotation processing.
 *
 * Annotation processing involves a set of processors, their classpath(s), and a few other
 * command-line options for javac.  We want to be able to specify all this various information
 * in a BUCK configuration file and use it when we generate the javac command.  This facilitates
 * threading the information through buck in a more descriptive package rather than passing all
 * the components separately.
 */
public class AnnotationProcessingParams implements AnnotationProcessingData {
  public static final AnnotationProcessingParams EMPTY = new AnnotationProcessingParams(
      /* owner target */ null,
      /* project filesystem */ null,
      ImmutableSet.<Path>of(),
      ImmutableSet.<String>of(),
      ImmutableSet.<String>of(),
      ImmutableSortedSet.<BuildRule>of(),
      false);

  @Nullable
  private final BuildTarget ownerTarget;
  @Nullable
  private final ProjectFilesystem filesystem;
  private final ImmutableSortedSet<Path> searchPathElements;
  private final ImmutableSortedSet<String> names;
  private final ImmutableSortedSet<String> parameters;
  private final ImmutableSortedSet<BuildRule> rules;
  private final boolean processOnly;

  private AnnotationProcessingParams(
      @Nullable BuildTarget ownerTarget,
      @Nullable ProjectFilesystem filesystem,
      Set<Path> searchPathElements,
      Set<String> names,
      Set<String> parameters,
      Set<BuildRule> rules,
      boolean processOnly) {
    this.ownerTarget = ownerTarget;
    this.filesystem = filesystem;
    this.searchPathElements = ImmutableSortedSet.copyOf(searchPathElements);
    this.names = ImmutableSortedSet.copyOf(names);
    this.parameters = ImmutableSortedSet.copyOf(parameters);
    this.rules = ImmutableSortedSet.copyOf(rules);
    this.processOnly = processOnly;

    if (!isEmpty() && ownerTarget != null) {
      Preconditions.checkNotNull(filesystem);
    }
  }

  private Path getGeneratedSrcFolder() {
    Preconditions.checkNotNull(filesystem);
    return Paths.get(
        String.format(
            "%s/%s__%s_gen__",
            BuckConstant.ANNOTATION_DIR,
            Preconditions.checkNotNull(ownerTarget).getBasePathWithSlash(),
            ownerTarget.getShortNameAndFlavorPostfix()));
  }

  @Override
  public boolean isEmpty() {
    return searchPathElements.isEmpty() && names.isEmpty() && parameters.isEmpty();
  }

  @Override
  public ImmutableSortedSet<Path> getSearchPathElements() {
    return searchPathElements;
  }

  @Override
  public ImmutableSortedSet<String> getNames() {
    return names;
  }

  @Override
  public ImmutableSortedSet<String> getParameters() {
    return parameters;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    if (!isEmpty()) {
      // searchPathElements is not needed here since it comes from rules, which is appended below.
      String owner = (ownerTarget == null) ? null : ownerTarget.getFullyQualifiedName();
      builder.set("owner", owner)
          .set("names", names)
          .set("parameters", parameters)
          .set("processOnly", processOnly);

      ImmutableList.Builder<String> ruleKeyStrings = ImmutableList.builder();
      for (BuildRule rule : rules) {
        ruleKeyStrings.add(rule.getRuleKey().toString());
      }
      builder.set("annotationProcessorRuleKeys", ruleKeyStrings.build());
    }

    return builder;
  }

  @Override
  public boolean getProcessOnly() {
    return processOnly;
  }

  @Override
  @Nullable
  public Path getGeneratedSourceFolderName() {
    if ((ownerTarget != null) && !isEmpty()) {
      return getGeneratedSrcFolder();
    } else {
      return null;
    }
  }

  public static class Builder {
    @Nullable
    private BuildTarget ownerTarget;
    @Nullable
    private ProjectFilesystem filesystem;
    private Set<BuildRule> rules = Sets.newHashSet();
    private Set<String> names = Sets.newHashSet();
    private Set<String> parameters = Sets.newHashSet();
    private boolean processOnly;

    public Builder setOwnerTarget(BuildTarget owner) {
      ownerTarget = owner;
      return this;
    }

    public Builder addProcessorBuildTarget(BuildRule rule) {
      rules.add(rule);
      return this;
    }

    public Builder addAllProcessors(Collection<? extends String> processorNames) {
      names.addAll(processorNames);
      return this;
    }

    public Builder addParameter(String parameter) {
      parameters.add(parameter);
      return this;
    }

    public Builder setProcessOnly(boolean processOnly) {
      this.processOnly = processOnly;
      return this;
    }

    public Builder setProjectFilesystem(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      return this;
    }

    public AnnotationProcessingParams build() {
      if (names.isEmpty() && rules.isEmpty() && parameters.isEmpty()) {
        return EMPTY;
      }

      Set<Path> searchPathElements = Sets.newHashSet();
      ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();

      for (BuildRule rule : this.rules) {
        String type = rule.getType().getName();

        rules.add(rule);

        // We're using raw strings here to avoid circular dependencies.
        // TODO(simons): don't use raw strings.
        HasClasspathEntries hasClasspathEntries = getRuleAsHasClasspathEntries(rule);
        if ("java_binary".equals(type) || "prebuilt_jar".equals(type)) {
          Path pathToOutput = rule.getPathToOutputFile();
          if (pathToOutput != null) {
            searchPathElements.add(pathToOutput);
          }
        } else if (hasClasspathEntries != null) {
          searchPathElements.addAll(hasClasspathEntries.getTransitiveClasspathEntries().values());
        } else {
          throw new HumanReadableException(
              "%1$s: Error adding '%2$s' to annotation_processing_deps: " +
              "must refer only to prebuilt jar, java binary, or java library targets.",
              ownerTarget,
              rule.getFullyQualifiedName());
        }
      }

      return new AnnotationProcessingParams(
          ownerTarget,
          filesystem,
          searchPathElements,
          names,
          parameters,
          rules.build(),
          processOnly);
    }

    @Nullable
    private HasClasspathEntries getRuleAsHasClasspathEntries(BuildRule rule) {
      if (rule instanceof HasClasspathEntries) {
        return (HasClasspathEntries) rule;
      }
      return null;
    }
  }
}

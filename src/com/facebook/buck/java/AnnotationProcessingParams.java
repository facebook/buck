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

import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleBuilderParams;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

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
  public final static AnnotationProcessingParams EMPTY = new AnnotationProcessingParams(
      null,
      ImmutableSet.<String>of(),
      ImmutableSet.<String>of(),
      ImmutableSet.<String>of(),
      false);

  @Nullable
  private final BuildTarget ownerTarget;
  private final ImmutableSortedSet<String> searchPathElements;
  private final ImmutableSortedSet<String> names;
  private final ImmutableSortedSet<String> parameters;
  private final boolean processOnly;

  private AnnotationProcessingParams(
      @Nullable BuildTarget ownerTarget,
      Set<String> searchPathElements,
      Set<String> names,
      Set<String> parameters,
      boolean processOnly) {
    this.ownerTarget = ownerTarget;
    this.searchPathElements = ImmutableSortedSet.copyOf(searchPathElements);
    this.names = ImmutableSortedSet.copyOf(names);
    this.parameters = ImmutableSortedSet.copyOf(parameters);
    this.processOnly = processOnly;
  }

  private String getGeneratedSrcFolder() {
    return String.format("%s/%s__%s_gen__",
        BuckConstant.ANNOTATION_DIR,
        ownerTarget.getBasePathWithSlash(),
        ownerTarget.getShortName());
  }

  @Override
  public boolean isEmpty() {
    return searchPathElements.isEmpty() && names.isEmpty() && parameters.isEmpty();
  }

  @Override
  public ImmutableSortedSet<String> getSearchPathElements() {
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
  public boolean getProcessOnly() {
    return processOnly;
  }

  @Override
  @Nullable
  public String getGeneratedSourceFolderName() {
    if ((ownerTarget != null) && !isEmpty()) {
      return getGeneratedSrcFolder();
    } else {
      return null;
    }
  }

  public static class Builder {
    private BuildTarget ownerTarget;
    private Set<BuildTarget> targets = Sets.newHashSet();
    private Set<String> names = Sets.newHashSet();
    private Set<String> parameters = Sets.newHashSet();
    private boolean processOnly;

    public Builder setOwnerTarget(BuildTarget owner) {
      ownerTarget = owner;
      return this;
    }

    public Builder addProcessorBuildTarget(BuildTarget target) {
      targets.add(target);
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

    public AnnotationProcessingParams build(BuildRuleBuilderParams buildRuleBuilderParams) {
      Preconditions.checkNotNull(buildRuleBuilderParams);

      if (names.isEmpty() && targets.isEmpty() && parameters.isEmpty()) {
        return EMPTY;
      }

      Set<String> searchPathElements = Sets.newHashSet();

      for (BuildTarget target : targets) {
        BuildRule rule = buildRuleBuilderParams.get(target);
        String type = rule.getType().getName();

        // We're using raw strings here to avoid circular dependencies.
        // TODO(simons): don't use raw strings.
        if ("java_binary".equals(type) || "prebuilt_jar".equals(type)) {
          String pathToOutput = rule.getPathToOutputFile();
          if (pathToOutput != null) {
            searchPathElements.add(pathToOutput);
          }
        } else if (rule instanceof HasClasspathEntries) {
          HasClasspathEntries javaLibraryRule = (HasClasspathEntries)rule;
          searchPathElements.addAll(javaLibraryRule.getTransitiveClasspathEntries().values());
        } else {
          throw new HumanReadableException(
              "%1$s: Error adding '%2$s' to annotation_processing_deps: " +
              "must refer only to prebuilt jar, java binary, or java library targets.",
              ownerTarget,
              target.getFullyQualifiedName());
        }
      }

      return new AnnotationProcessingParams(
          ownerTarget,
          searchPathElements,
          names,
          parameters,
          processOnly);
    }
  }
}

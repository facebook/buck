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
package com.facebook.buck.rules;

import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Map;
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
  private final ImmutableSet<String> searchPathElements;
  private final ImmutableSet<String> names;
  private final ImmutableSet<String> parameters;
  private final boolean processOnly;

  private AnnotationProcessingParams(
      @Nullable BuildTarget ownerTarget,
      Set<String> searchPathElements,
      Set<String> names,
      Set<String> parameters,
      boolean processOnly) {
    this.ownerTarget = ownerTarget;
    this.searchPathElements = ImmutableSet.copyOf(searchPathElements);
    this.names = ImmutableSet.copyOf(names);
    this.parameters = ImmutableSet.copyOf(parameters);
    this.processOnly = processOnly;
  }

  static String getGeneratedSrcFolder(BuildTarget buildTarget) {
    return String.format("%s/%s__%s_gen__",
        BuckConstant.ANNOTATION_DIR,
        buildTarget.getBasePathWithSlash(),
        buildTarget.getShortName());
  }

  @Override
  public boolean isEmpty() {
    return searchPathElements.isEmpty() && names.isEmpty() && parameters.isEmpty();
  }

  @Override
  public ImmutableSet<String> getSearchPathElements() {
    return searchPathElements;
  }

  @Override
  public ImmutableSet<String> getNames() {
    return names;
  }

  @Override
  public ImmutableSet<String> getParameters() {
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
      return getGeneratedSrcFolder(ownerTarget);
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

    public AnnotationProcessingParams build(Map<String, BuildRule> buildRuleIndex) {
      Preconditions.checkNotNull(buildRuleIndex);

      if (names.isEmpty() && targets.isEmpty() && parameters.isEmpty()) {
        return EMPTY;
      }

      Set<String> searchPathElements = Sets.newHashSet();

      for (BuildTarget target : targets) {
        BuildRule rule = buildRuleIndex.get(target.getFullyQualifiedName());

        // TODO simons: can we just use BuildRule.getOutput() here without special-cases?
        if (rule instanceof JavaBinaryRule) {
          JavaBinaryRule javaBinaryRule = (JavaBinaryRule)rule;
          searchPathElements.add(javaBinaryRule.getOutputFile());
        } else if (rule instanceof PrebuiltJarRule) {
          PrebuiltJarRule prebuiltJarRule = (PrebuiltJarRule)rule;
          searchPathElements.add(prebuiltJarRule.getBinaryJar());
        } else if (rule instanceof JavaLibraryRule) {
          JavaLibraryRule javaLibraryRule = (JavaLibraryRule)rule;
          searchPathElements.addAll(javaLibraryRule.getClasspathEntries());
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

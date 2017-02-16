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
package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
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
public class AnnotationProcessingParams implements RuleKeyAppendable {

  public static final AnnotationProcessingParams EMPTY = new AnnotationProcessingParams(
      /* owner target */ null,
      /* project filesystem */ null,
      /* processors */ JavacPluginProperties.builder().build(),
      ImmutableSortedSet.of(),
      false);

  @Nullable
  private final BuildTarget ownerTarget;
  @Nullable
  private final ProjectFilesystem filesystem;
  private final JavacPluginProperties annotationProcessors;
  private final ImmutableSortedSet<String> parameters;
  private final boolean processOnly;

  private AnnotationProcessingParams(
      @Nullable BuildTarget ownerTarget,
      @Nullable ProjectFilesystem filesystem,
      JavacPluginProperties annotationProcessors,
      Set<String> parameters,
      boolean processOnly) {
    this.ownerTarget = ownerTarget;
    this.filesystem = filesystem;
    this.annotationProcessors = annotationProcessors;
    this.parameters = ImmutableSortedSet.copyOf(parameters);
    this.processOnly = processOnly;

    if (!isEmpty() && ownerTarget != null) {
      Preconditions.checkNotNull(filesystem);
    }
  }

  private Path getGeneratedSrcFolder() {
    Preconditions.checkNotNull(filesystem);
    return BuildTargets.getAnnotationPath(
        filesystem,
        Preconditions.checkNotNull(ownerTarget), "__%s_gen__");
  }

  public boolean isEmpty() {
    return annotationProcessors.isEmpty() && parameters.isEmpty();
  }

  public ImmutableSortedSet<SourcePath> getSearchPathElements() {
    return annotationProcessors.getClasspathEntries();
  }

  public ImmutableSortedSet<String> getNames() {
    return annotationProcessors.getProcessorNames();
  }

  public ImmutableSortedSet<String> getParameters() {
    return parameters;
  }

  public ImmutableSortedSet<SourcePath> getInputs() {
    return annotationProcessors.getInputs();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    if (!isEmpty()) {
      sink.setReflectively("owner", ownerTarget)
          .setReflectively("properties", annotationProcessors)
          .setReflectively("parameters", parameters)
          .setReflectively("processOnly", processOnly);
    }
  }

  public boolean getProcessOnly() {
    return processOnly;
  }

  @Nullable
  public Path getGeneratedSourceFolderName() {
    if ((ownerTarget != null) && !isEmpty()) {
      return getGeneratedSrcFolder();
    } else {
      return null;
    }
  }

  public AnnotationProcessingParams withoutProcessOnly() {
    if (processOnly) {
      return new AnnotationProcessingParams(
          ownerTarget,
          filesystem,
          searchPathElements,
          names,
          parameters,
          inputs,
          false);
    } else {
      return this;
    }
  }

  public static class Builder {
    @Nullable
    private BuildTarget ownerTarget;
    @Nullable
    private ProjectFilesystem filesystem;
    private JavacPluginProperties.Builder processorsBuilder = JavacPluginProperties.builder();
    private Set<String> parameters = Sets.newHashSet();
    private boolean processOnly;

    public Builder setOwnerTarget(BuildTarget owner) {
      ownerTarget = owner;
      return this;
    }

    public Builder addProcessorBuildTarget(BuildRule rule) {
      processorsBuilder.addDep(rule);
      return this;
    }

    public Builder addAllProcessors(Collection<String> processorNames) {
      processorsBuilder.addAllProcessorNames(processorNames);
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
      JavacPluginProperties processors = processorsBuilder.build();
      if (processors.isEmpty() && parameters.isEmpty()) {
        return EMPTY;
      }

      return new AnnotationProcessingParams(
          ownerTarget,
          filesystem,
          processors,
          parameters,
          processOnly);
    }
  }
}

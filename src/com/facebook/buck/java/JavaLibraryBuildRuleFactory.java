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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.List;

public class JavaLibraryBuildRuleFactory extends AbstractBuildRuleFactory<DefaultJavaLibraryRule.Builder> {

  public static final String ANNOTATION_PROCESSORS = "annotation_processors";

  private final Optional<Path> javac;
  private final Optional<String> javacVersion;

  public JavaLibraryBuildRuleFactory() {
    this(Optional.<Path>absent(), Optional.<String>absent());
  }

  public JavaLibraryBuildRuleFactory(Optional<Path> javac, Optional<String> javacVersion) {
    this.javac = javac;
    this.javacVersion = javacVersion;
  }

  @VisibleForTesting
  public Optional<Path> getJavac() {
    return javac;
  }

  @VisibleForTesting
  public Optional<String> getJavacVersion() {
    return javacVersion;
  }

  @Override
  public DefaultJavaLibraryRule.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(javac, javacVersion, params);
  }

  @Override
  protected void amendBuilder(DefaultJavaLibraryRule.Builder builder,
                              BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    Optional<String> proguardConfig = params.getOptionalStringAttribute("proguard_config");
    builder.setProguardConfig(
        proguardConfig.transform(params.getResolveFilePathRelativeToBuildFileDirectoryTransform()));


    for (String exportedDep : params.getOptionalListAttribute("exported_deps")) {
      BuildTarget buildTarget = params.resolveBuildTarget(exportedDep);
      builder.addExportedDep(buildTarget);
    }

    extractAnnotationProcessorParameters(
        builder.getAnnotationProcessingBuilder(), builder, params);

    Optional<String> sourceLevel = params.getOptionalStringAttribute("source");
    if (sourceLevel.isPresent()) {
      builder.setSourceLevel(sourceLevel.get());
    }

    Optional<String> targetLevel = params.getOptionalStringAttribute("target");
    if (targetLevel.isPresent()) {
      builder.setTargetLevel(targetLevel.get());
    }
  }

  static void extractAnnotationProcessorParameters(
      AnnotationProcessingParams.Builder annotationProcessingBuilder,
      DefaultJavaLibraryRule.Builder buildRuleBuilder,
      BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {

    // annotation_processors
    //
    // Names of the classes used for annotation processing.  These must be implemented
    // in a BuildTarget listed in annotation_processor_deps.
    List<String> annotationProcessors = params.getOptionalListAttribute(ANNOTATION_PROCESSORS);
    if (!annotationProcessors.isEmpty()) {
      annotationProcessingBuilder.addAllProcessors(annotationProcessors);

      // annotation_processor_deps
      //
      // These are the targets that implement one or more of the annotation_processors.
      for (String processor : params.getOptionalListAttribute("annotation_processor_deps")) {
        BuildTarget buildTarget = params.resolveBuildTarget(processor);
        buildRuleBuilder.addDep(buildTarget);
        annotationProcessingBuilder.addProcessorBuildTarget(buildTarget);
      }

      // annotation_processor_params
      //
      // These will be prefixed with "-A" when passing to javac.  They may be of the
      // form "parameter_name" or "parameter_name=string_value".
      for (String parameter : params.getOptionalListAttribute("annotation_processor_params")) {
        annotationProcessingBuilder.addParameter(parameter);
      }

      // annotation_processor_only
      //
      // If true, we only run annotation processors and do not compile.
      boolean processOnly = params.getBooleanAttribute("annotation_processor_only");
      annotationProcessingBuilder.setProcessOnly(processOnly);
    }
  }
}

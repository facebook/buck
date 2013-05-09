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
import com.facebook.buck.step.ExecutionContext;
import com.google.common.annotations.Beta;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.Set;

@Beta
public final class JavacOptionsUtil {

  public static final String DEFAULT_SOURCE_LEVEL = "6";
  public static final String DEFAULT_TARGET_LEVEL = "6";

  /** Utility class: do not instantiate. */
  private JavacOptionsUtil() {}

  /**
   * Adds the appropriate options for javac given the specified {@link com.facebook.buck.step.ExecutionContext}.
   */
  static void addOptions(
      ImmutableList.Builder<String> optionsBuilder,
      ExecutionContext context,
      File outputDirectory,
      Set<String> classpathEntries,
      Supplier<String> bootclasspathSupplier,
      AnnotationProcessingData annotationProcessingData,
      String sourceLevel,
      String targetLevel) {
    Preconditions.checkNotNull(optionsBuilder);
    Preconditions.checkNotNull(context);
    Preconditions.checkNotNull(outputDirectory);
    Preconditions.checkNotNull(classpathEntries);
    Preconditions.checkNotNull(bootclasspathSupplier);
    Preconditions.checkNotNull(annotationProcessingData);
    Preconditions.checkNotNull(sourceLevel);
    Preconditions.checkNotNull(targetLevel);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      optionsBuilder.add("-verbose");
    }

    // Add some standard options.
    optionsBuilder.add("-target", sourceLevel);
    optionsBuilder.add("-source", targetLevel);

    // Include all debug information. Let Proguard be responsible for stripping it from production
    // builds.
    optionsBuilder.add("-g");

    // Specify the output directory.
    optionsBuilder.add("-d").add(outputDirectory.getAbsolutePath());

    // Override the bootclasspath if Buck is building Java code for Android.
    String bootclasspath = bootclasspathSupplier.get();
    if (bootclasspath != null) {
      optionsBuilder.add("-bootclasspath", bootclasspath);
    }

    // Build up and set the classpath.
    if (!classpathEntries.isEmpty()) {
      String classpath = Joiner.on(":").join(classpathEntries);
      optionsBuilder.add("-classpath", classpath);
    }

    // Add annotation processors.
    if (!annotationProcessingData.isEmpty()) {

      // Specify where to generate sources so IntelliJ can pick them up.
      String generateTo = annotationProcessingData.getGeneratedSourceFolderName();
      if (generateTo != null) {
        optionsBuilder.add("-s").add(generateTo);
      }

      // Specify processorpath to search for processors.
      optionsBuilder.add("-processorpath",
          Joiner.on(':').join(annotationProcessingData.getSearchPathElements()));

      // Specify names of processors.
      if (!annotationProcessingData.getNames().isEmpty()) {
        optionsBuilder.add("-processor", Joiner.on(',').join(annotationProcessingData.getNames()));
      }

      // Add processor parameters.
      for (String parameter : annotationProcessingData.getParameters()) {
        optionsBuilder.add("-A" + parameter);
      }

      if (annotationProcessingData.getProcessOnly()) {
        optionsBuilder.add("-proc:only");
      }
    }
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;

public final class JavacArgInterpreter {
  public static JavacOptions populateJavacOptions(
      JavacOptions defaultOptions,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      JavacArg javacArg) {
    if ((javacArg.source.isPresent() || javacArg.target.isPresent()) &&
        javacArg.javaVersion.isPresent()) {
      throw new HumanReadableException("Please set either source and target or java_version.");
    }

    JavacOptions.Builder builder = JavacOptions.builder(defaultOptions);

    if (javacArg.javaVersion.isPresent()) {
      builder.setSourceLevel(javacArg.javaVersion.get());
      builder.setTargetLevel(javacArg.javaVersion.get());
    }

    if (javacArg.source.isPresent()) {
      builder.setSourceLevel(javacArg.source.get());
    }

    if (javacArg.target.isPresent()) {
      builder.setTargetLevel(javacArg.target.get());
    }

    if (javacArg.extraArguments.isPresent()) {
      builder.addAllExtraArguments(javacArg.extraArguments.get());
    }

    if (javacArg.compiler.isPresent()) {
      Either<BuiltInJavac, SourcePath> either = javacArg.compiler.get();

      if (either.isRight()) {
        SourcePath sourcePath = either.getRight();

        Optional<BuildRule> possibleRule = pathResolver.getRule(sourcePath);
        if (possibleRule.isPresent()) {
          BuildRule rule = possibleRule.get();
          if (rule instanceof PrebuiltJar) {
            builder.setJavacJarPath(
                new BuildTargetSourcePath(rule.getBuildTarget()));
          } else {
            throw new HumanReadableException("Only prebuilt_jar targets can be used as a javac");
          }
        } else {
          builder.setJavacPath(pathResolver.getAbsolutePath(sourcePath));
        }
      }
    } else {
      if (javacArg.javac.isPresent() || javacArg.javacJar.isPresent()) {
        if (javacArg.javac.isPresent() && javacArg.javacJar.isPresent()) {
          throw new HumanReadableException("Cannot set both javac and javacjar");
        }
        builder.setJavacPath(javacArg.javac);
        builder.setJavacJarPath(javacArg.javacJar);
      }
    }

    AnnotationProcessingParams annotationParams =
        javacArg.buildAnnotationProcessingParams(
            params.getBuildTarget(),
            params.getProjectFilesystem(),
            resolver);
    builder.setAnnotationProcessingParams(annotationParams);

    return builder.build();
  }

  private JavacArgInterpreter() {}
}

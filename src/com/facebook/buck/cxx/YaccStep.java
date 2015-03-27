/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MoveStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;

public class YaccStep extends CompositeStep {

  public YaccStep(
      final ImmutableList<String> yaccPrefix,
      final ImmutableList<String> flags,
      final Path outputPrefix,
      final Path input) {

    super(ImmutableList.of(
        new ShellStep() {
          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.<String>builder()
                .addAll(yaccPrefix)
                .addAll(flags)
                .add("-d")
                .add("-b", outputPrefix.toString())
                .add(input.toString())
                .build();
          }
          @Override
          public String getShortName() {
            return "yacc command";
          }
        },
        new FindAndReplaceStep(
            getIntermediateSourceOutputPath(outputPrefix),
            getSourceOutputPath(outputPrefix),
            getIntermediateSourceOutputPath(outputPrefix).toString(),
            getSourceOutputPath(outputPrefix).toString()),
        new MoveStep(
            getIntermediateHeaderOutputPath(outputPrefix),
            getHeaderOutputPath(outputPrefix))));
  }

  public static Path getHeaderOutputPath(Path outputPrefix) {
    return Paths.get(outputPrefix + ".yy.h");
  }

  public static Path getSourceOutputPath(Path outputPrefix) {
    return Paths.get(outputPrefix + ".yy.cc");
  }

  private static Path getIntermediateSourceOutputPath(Path outputPrefix) {
    return Paths.get(outputPrefix + ".tab.c");
  }

  private static Path getIntermediateHeaderOutputPath(Path outputPrefix) {
    return Paths.get(outputPrefix + ".tab.h");
  }

  @Override
  public String getShortName() {
    return "yacc";
  }

}

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

import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Prepares argfile for the CxxLinkStep, so all arguments to the linker will be stored in a
 * single file. CxxLinkStep then would pass it to the linker via @path/to/argfile.txt command.
 * This allows us to break the constraints that command line sets for the maximum length of
 * the commands.
 */
public class CxxPrepareForLinkStep extends CompositeStep {

  private static final Logger LOG = Logger.get(CxxPrepareForLinkStep.class);

  public static CxxPrepareForLinkStep create(
      Path argFilePath,
      Path fileListPath,
      Iterable<Arg> linkerArgsToSupportFileList,
      Path output,
      ImmutableList<Arg> args,
      Linker linker,
      Path currentCellPath) {

    ImmutableList<Arg> allArgs = ImmutableList.<Arg>builder()
        .addAll(StringArg.from(linker.outputArgs(output.toString())))
        .addAll(args)
        .addAll(linkerArgsToSupportFileList)
        .build();

    boolean hasLinkArgsToSupportFileList = linkerArgsToSupportFileList.iterator().hasNext();

    CxxWriteArgsToFileStep createArgFileStep = new CxxWriteArgsToFileStep(
        argFilePath,
        hasLinkArgsToSupportFileList ? allArgs.stream()
            .filter(input -> !(input instanceof FileListableLinkerInputArg))
            .collect(MoreCollectors.toImmutableList()) : allArgs,
        Optional.of(Javac.ARGFILES_ESCAPER),
        currentCellPath);

    if (!hasLinkArgsToSupportFileList) {
      LOG.verbose("linkerArgsToSupportFileList is empty, filelist feature is not supported");
      return new CxxPrepareForLinkStep(ImmutableList.of(createArgFileStep));
    }

    CxxWriteArgsToFileStep createFileListStep = new CxxWriteArgsToFileStep(
        fileListPath,
        allArgs.stream()
            .filter(input -> input instanceof FileListableLinkerInputArg)
            .collect(MoreCollectors.toImmutableList()),
        Optional.empty(), currentCellPath);

    return new CxxPrepareForLinkStep(ImmutableList.of(createArgFileStep, createFileListStep));
  }

  private CxxPrepareForLinkStep(List<? extends Step> commands) {
    super(commands);
  }

  @Override
  public String getShortName() {
    return "cxx prepare for link step";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "prepares arg file that will be passed to the linker";
  }
}

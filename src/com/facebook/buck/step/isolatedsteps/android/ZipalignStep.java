/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.step.isolatedsteps.shell.IsolatedShellStep;
import com.google.common.collect.ImmutableList;

/** {@link IsolatedShellStep} wrapping Android's zipalign tool. */
public class ZipalignStep extends IsolatedShellStep {

  private final RelPath inputFile;
  private final RelPath outputFile;
  private final ImmutableList<String> zipAlignCommandPrefix;

  public ZipalignStep(
      AbsPath workingDirectory,
      RelPath cellRootPath,
      RelPath inputFile,
      RelPath outputFile,
      ImmutableList<String> zipAlignCommandPrefix,
      boolean withDownwardApi) {
    super(workingDirectory, cellRootPath, withDownwardApi);
    this.inputFile = inputFile;
    this.outputFile = outputFile;
    this.zipAlignCommandPrefix = zipAlignCommandPrefix;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(IsolatedExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();

    args.addAll(zipAlignCommandPrefix);
    args.add("-f").add("4");
    args.add(inputFile.toString());
    args.add(outputFile.toString());
    return args.build();
  }

  @Override
  public String getShortName() {
    return "zipalign";
  }
}

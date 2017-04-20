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

package org.openqa.selenium.buck.javascript;


import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class ClosureCompilerStep extends ShellStep {

  private final Path output;
  private final ImmutableList<String> cmd;

  private ClosureCompilerStep(Path workingDirectory, Path output, ImmutableList<String> cmd) {
    super(workingDirectory);
    this.output = Preconditions.checkNotNull(output);
    this.cmd = cmd;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return cmd;
  }

  @Override
  public String getShortName() {
    return "closure compiler";
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws InterruptedException, IOException {
    StepExecutionResult exitCode = super.execute(context);

    if (exitCode.isSuccess()) {
      return exitCode;
    }
    File file = output.toFile();
    if (file.exists() && !file.delete()) {
      throw new HumanReadableException(
          "Unable to delete output, which may lead to incorrect builds: " + output);
    }
    return exitCode;
  }

  public static Builder builder(
      Path workingDirectory,
      SourcePathResolver resolver,
      Tool jsCompiler) {
    return new Builder(workingDirectory, resolver, jsCompiler);
  }

  public static class Builder {

    private final Path workingDirectory;
    private final SourcePathResolver resolver;
    private ImmutableList.Builder<String> cmd = ImmutableList.builder();
    private Path output;

    public Builder(Path workingDirectory, SourcePathResolver resolver, Tool compiler) {
      this.workingDirectory = Preconditions.checkNotNull(workingDirectory);
      this.resolver = resolver;

      cmd.addAll(compiler.getCommandPrefix(resolver));
    }

    public Builder defines(ImmutableList<String> defines) {
      for (String define : defines) {
        cmd.add("--define=" + define);
      }
      return this;
    }

    public Builder externs(ImmutableList<SourcePath> externs) {
      for (SourcePath path : externs) {
        cmd.add("--externs='" + resolver.getAbsolutePath(path) + "'");
      }
      return this;
    }

    public Builder flags(ImmutableList<String> flags) {
      for (String flag : flags) {
        cmd.add(flag);
      }
      return this;
    }

    public Builder prettyPrint() {
      cmd.add("--formatting=PRETTY_PRINT");
      return this;
    }

    public Builder prettyPrint(boolean isPretty) {
      if (isPretty) {
        prettyPrint();
      }
      return this;
    }

    public Builder sources(Iterable<Path> paths) {
      for (Path path : paths) {
        cmd.add("--js='" + path + "'");
      }
      return this;
    }

    public Builder output(Path out) {
      this.output = out;
      cmd.add("--js_output_file=" + out);
      return this;
    }

    public ClosureCompilerStep build() {
      return new ClosureCompilerStep(workingDirectory, output, cmd.build());
    }
  }
}

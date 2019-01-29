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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;

/** This step runs ocamldep tool to compute dependencies among source files (*.mli and *.ml) */
public class OcamlDepToolStep extends ShellStep {

  private final ImmutableList<SourcePath> input;
  private final ImmutableList<String> flags;
  private final SourcePathResolver resolver;
  private Tool ocamlDepTool;

  public OcamlDepToolStep(
      Path workingDirectory,
      SourcePathResolver resolver,
      Tool ocamlDepTool,
      List<SourcePath> input,
      List<String> flags) {
    super(workingDirectory);
    this.resolver = resolver;
    this.ocamlDepTool = ocamlDepTool;
    this.flags = ImmutableList.copyOf(flags);
    this.input = ImmutableList.copyOf(input);
  }

  @Override
  public String getShortName() {
    return "OCaml dependency";
  }

  @Override
  protected void addOptions(ImmutableSet.Builder<ProcessExecutor.Option> options) {
    // We need this else we get output with color codes which confuses parsing
    options.add(ProcessExecutor.Option.EXPECTING_STD_ERR);
    options.add(ProcessExecutor.Option.EXPECTING_STD_OUT);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(@Nullable ExecutionContext context) {

    ImmutableList.Builder<String> cmd = ImmutableList.builder();

    cmd.addAll(ocamlDepTool.getCommandPrefix(resolver))
        .add("-one-line")
        .add("-native")
        .addAll(flags)
        .add("-ml-synonym")
        .add(".re")
        .add("-mli-synonym")
        .add(".rei");

    boolean previousFileWasReason = false;

    for (SourcePath sourcePath : input) {
      String filePath = resolver.getAbsolutePath(sourcePath).toString();
      String ext = Files.getFileExtension(filePath);
      String dotExt = "." + ext;

      boolean isImplementation =
          dotExt.equals(OcamlCompilables.OCAML_ML) || dotExt.equals(OcamlCompilables.OCAML_RE);
      boolean isReason =
          dotExt.equals(OcamlCompilables.OCAML_RE) || dotExt.equals(OcamlCompilables.OCAML_REI);

      if (isReason && !previousFileWasReason) {
        cmd.add("-pp").add("refmt");
      } else if (!isReason && previousFileWasReason) {
        // Use cat to restore the preprocessor only when the previous file was Reason.
        cmd.add("-pp").add("cat");
      }

      // Note -impl and -intf must go after the -pp flag, if any.
      cmd.add(isImplementation ? "-impl" : "-intf");

      cmd.add(filePath);

      previousFileWasReason = isReason;
    }

    return cmd.build();
  }
}

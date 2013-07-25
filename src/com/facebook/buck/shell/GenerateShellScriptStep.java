/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * When executed, generates a shell script at the specified location.
 */
public class GenerateShellScriptStep implements Step {

  private final Path basePath;
  private final Path scriptToRun;
  private final Iterable<Path> resources;
  private final Path outputFile;

  public GenerateShellScriptStep(
      Path basePath,
      Path scriptToRun,
      Iterable<Path> resources,
      Path outputFile) {
    this.basePath = Preconditions.checkNotNull(basePath);
    this.scriptToRun = Preconditions.checkNotNull(scriptToRun);
    this.resources = Preconditions.checkNotNull(resources);
    this.outputFile = Preconditions.checkNotNull(outputFile);
  }

  @Override
  public int execute(ExecutionContext context) {
    List<String> lines = Lists.newArrayList();
    // Run with -e so the script will fail if any of the steps fail.
    lines.add("#!/bin/sh");
    lines.add("set -e");

    // Create a tmp directory that will be deleted when this script exits.
    lines.add("BUCK_PROJECT_ROOT=`mktemp -d -t sh_binary.XXXXXXXXXX`");
    lines.add("trap \"chmod -R 755 $BUCK_PROJECT_ROOT " +
    		"&& rm -rf $BUCK_PROJECT_ROOT\" EXIT HUP INT TERM");

    // Navigate to the tmp directory.
    lines.add("cd $BUCK_PROJECT_ROOT");

    // Symlink the resources to the $BUCK_PROJECT_ROOT directory.
    Function<String, String> pathRelativizer = context.getProjectFilesystem().getPathRelativizer();
    createSymlinkCommands(resources, pathRelativizer, lines);

    // Make everything in $BUCK_PROJECT_ROOT read-only.
    lines.add("find $BUCK_PROJECT_ROOT -type d -exec chmod 555 {} \\;");
    lines.add("find $BUCK_PROJECT_ROOT -type f -exec chmod 444 {} \\;");

    // Forward the args to this generated script to scriptToRun and execute it.
    lines.add(String.format(
        "BUCK_PROJECT_ROOT=$BUCK_PROJECT_ROOT %s \"$@\"",
        pathRelativizer.apply(scriptToRun.toString())));

    // Write the contents to the file.
    File output = context.getProjectFilesystem().getFileForRelativePath(outputFile.toString());
    try {
      Files.write(Joiner.on('\n').join(lines) + '\n', output, Charsets.UTF_8);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    // Make sure the file is executable.
    if (output.setExecutable(/* executable */ true, /* ownerOnly */ false)) {
      return 0;
    } else {
      context.getConsole().printErrorText("Failed to set file as executable: " + output);
      return 1;
    }
  }

  private void createSymlinkCommands(Iterable<Path> paths,
      Function<String, String> pathRelativizer,
      List<String> lines) {
    for (Path path : paths) {
      Preconditions.checkArgument(basePath.toString().isEmpty()
          || path.startsWith(basePath), "%s should start with %s", path, basePath);

      if (path.getNameCount() > 1) {
        lines.add(String.format("mkdir -p %s", Escaper.escapeAsBashString(path.getParent())));
      }
      lines.add(String.format("ln -s %s $BUCK_PROJECT_ROOT/%s",
          Escaper.escapeAsBashString(pathRelativizer.apply(path.toString())),
          Escaper.escapeAsBashString(path)));
    }
  }

  @Override
  public String getShortName() {
    return "gen_sh";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "write " + context.getProjectFilesystem().getFileForRelativePath(outputFile.toString());
  }
}

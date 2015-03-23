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

package com.facebook.buck.cli;

import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.intellij.Project;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.google.common.base.Joiner;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.IOException;
import java.util.List;

public class CleanCommand implements CommandRunner {
  private final Console console;
  private final ProjectFilesystem filesystem;

  public CleanCommand(CommandRunnerParams params) {
    console = params.getConsole();
    filesystem = params.getRepository().getFilesystem();
  }

  @Override
  public int runCommand(BuckConfig buckConfig, List<String> args) throws IOException {
    CleanCommandOptions options = new CleanCommandOptions(buckConfig);
    CmdLineParser parser = new CmdLineParserAdditionalOptions(options);

    boolean hasValidOptions = false;
    try {
      parser.parseArgument(args);
      hasValidOptions = true;
    } catch (CmdLineException e) {
      console.getStdErr().println(e.getMessage());
    }

    if (hasValidOptions && !options.showHelp()) {
      return runCommandWithOptions(options);
    } else {
      console.getStdErr().println("deletes any generated files");
      parser.printUsage(console.getStdErr());
      return 1;
    }
  }

  int runCommandWithOptions(CleanCommandOptions options) throws IOException {
    if (!options.getArguments().isEmpty()) {
      console.getStdErr().printf("Unrecognized argument%s to buck clean: %s\n",
          options.getArguments().size() == 1 ? "" : "s",
          Joiner.on(' ').join(options.getArguments()));
      return 1;
    }

    // Ideally, we would like the implementation of this method to be as simple as:
    //
    // getProjectFilesystem().rmdir(BuckConstant.BUCK_OUTPUT_DIRECTORY);
    //
    // However, we want to avoid blowing away directories that IntelliJ indexes, because that tends
    // to make it angry. Currently, those directories are:
    //
    // Project.ANDROID_GEN_DIR
    // BuckConstant.ANNOTATION_DIR
    //
    // However, Buck itself also uses BuckConstant.ANNOTATION_DIR. We need to fix things so that
    // IntelliJ does its default thing to generate code from annotations, and manages/indexes those
    // directories itself so we can blow away BuckConstant.ANNOTATION_DIR as part of `buck clean`.
    // This will also reduce how long `buck project` takes.
    //
    if (options.isCleanBuckProjectFiles()) {
      // Delete directories that were created for the purpose of `buck project`.
      // TODO(mbolin): Unify these two directories under a single buck-ide directory,
      // which is distinct from the buck-out directory.
      filesystem.rmdir(Project.ANDROID_GEN_PATH);
      filesystem.rmdir(BuckConstant.ANNOTATION_PATH);
    } else {
      // On Windows, you have to close all files that will be deleted.
      // Because buck clean will delete build.log, you must close it first.
      JavaUtilsLoggingBuildListener.closeLogFile();
      filesystem.rmdir(BuckConstant.SCRATCH_PATH);
      filesystem.rmdir(BuckConstant.GEN_PATH);
    }

    return 0;
  }
}

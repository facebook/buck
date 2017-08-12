// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.bisect;

import com.android.tools.r8.errors.CompilationError;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class BisectOptions {
  private static final String HELP_FLAG = "help";
  public static final String BUILD_GOOD_FLAG = "good";
  public static final String BUILD_BAD_FLAG = "bad";
  public static final String RESULT_GOOD_FLAG = "result-good";
  public static final String RESULT_BAD_FLAG = "result-bad";
  public static final String STATE_FLAG = "state";
  public static final String OUTPUT_FLAG = "output";
  public static final String COMMAND_FLAG = "command";

  public final File goodBuild;
  public final File badBuild;
  public final File stateFile;
  public final File command;
  public final File output;
  public final Result result;

  public enum Result { UNKNOWN, GOOD, BAD }

  private static class ParserSpec {
    OptionSpec<String> goodBuild;
    OptionSpec<String> badBuild;
    OptionSpec<String> command;
    OptionSpec<String> stateFile;
    OptionSpec<String> output;
    OptionSpec<Void> resultGood;
    OptionSpec<Void> resultBad;
    OptionSpec<Void> help;

    void init(OptionParser parser) {
      help = parser.accepts(HELP_FLAG).forHelp();
      resultGood = parser.accepts(RESULT_GOOD_FLAG, "Bisect again assuming previous run was good.");
      resultBad = parser.accepts(RESULT_BAD_FLAG, "Bisect again assuming previous run was bad.");
      goodBuild = parser.accepts(BUILD_GOOD_FLAG, "Known good APK.")
          .withRequiredArg()
          .describedAs("apk");
      badBuild = parser.accepts(BUILD_BAD_FLAG, "Known bad APK.")
          .withRequiredArg()
          .describedAs("apk");
      stateFile = parser.accepts(STATE_FLAG, "Bisection state.")
          .requiredIf(resultGood, resultBad)
          .withRequiredArg()
          .describedAs("file");
      output = parser.accepts(OUTPUT_FLAG, "Output directory.")
          .withRequiredArg()
          .describedAs("dir");
      command = parser.accepts(COMMAND_FLAG, "Command to run after each bisection.")
          .requiredUnless(stateFile)
          .withRequiredArg()
          .describedAs("file");
    }

    OptionSet parse(String[] args) {
      OptionParser parser = new OptionParser();
      init(parser);
      return parser.parse(args);
    }

    static void printHelp(OutputStream out) throws IOException {
      OptionParser parser = new OptionParser();
      new ParserSpec().init(parser);
      parser.printHelpOn(out);
    }
  }

  private BisectOptions(File goodBuild, File badBuild, File stateFile, File command, File output,
      Result result) {
    this.goodBuild = goodBuild;
    this.badBuild = badBuild;
    this.stateFile = stateFile;
    this.command = command;
    this.output = output;
    this.result = result;
  }

  public static BisectOptions parse(String[] args) throws IOException {
    ParserSpec parser = new ParserSpec();
    OptionSet options = parser.parse(args);
    if (options.has(parser.help)) {
      printHelp(System.out);
      return null;
    }
    File goodBuild = exists(require(options, parser.goodBuild, BUILD_GOOD_FLAG), BUILD_GOOD_FLAG);
    File badBuild = exists(require(options, parser.badBuild, BUILD_BAD_FLAG), BUILD_BAD_FLAG);
    File stateFile = null;
    if (options.valueOf(parser.stateFile) != null) {
      stateFile = exists(options.valueOf(parser.stateFile), STATE_FLAG);
    }
    File command = null;
    if (options.valueOf(parser.command) != null) {
      command = exists(options.valueOf(parser.command), COMMAND_FLAG);
    }
    File output = null;
    if (options.valueOf(parser.output) != null) {
      output = directoryExists(options.valueOf(parser.output), OUTPUT_FLAG);
    }
    Result result = Result.UNKNOWN;
    if (options.has(parser.resultGood)) {
      result = Result.GOOD;
    }
    if (options.has(parser.resultBad)) {
      if (result == Result.GOOD) {
        throw new CompilationError("Cannot specify --" + RESULT_GOOD_FLAG
            + " and --" + RESULT_BAD_FLAG + " simultaneously");
      }
      result = Result.BAD;
    }
    return new BisectOptions(goodBuild, badBuild, stateFile, command, output, result);
  }

  private static <T> T require(OptionSet options, OptionSpec<T> option, String flag) {
    T value = options.valueOf(option);
    if (value != null) {
      return value;
    }
    throw new CompilationError("Missing required option: --" + flag);
  }

  private static File exists(String path, String flag) {
    File file = new File(path);
    if (file.exists()) {
      return file;
    }
    throw new CompilationError("File --" + flag + ": " + file + " does not exist");
  }

  private static File directoryExists(String path, String flag) {
    File file = new File(path);
    if (file.exists() && file.isDirectory()) {
      return file;
    }
    throw new CompilationError("File --" + flag + ": " + file + " is not a valid directory");
  }

  public static void printHelp(OutputStream out) throws IOException {
    ParserSpec.printHelp(out);
  }
}

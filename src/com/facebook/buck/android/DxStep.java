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

package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import javax.annotation.CheckForNull;

public class DxStep extends ShellStep {

  /**
   */
  public static final String XMX_OVERRIDE =
      "";

  /** Options to pass to {@code dx}. */
  public static enum Option {
    /** Specify the {@code --no-optimize} flag when running {@code dx}. */
    NO_OPTIMIZE,

    /** Specify the {@code --force-jumbo} flag when running {@code dx}. */
    FORCE_JUMBO,

    /**
     * See if the {@code buck.dx} property was specified, and if so, use the executable that that
     * points to instead of the {@code dx} in the user's Android SDK.
     */
    USE_CUSTOM_DX_IF_AVAILABLE,

    /**
     * Execute DX in-process instead of fork/execing.
     * This only works with custom dx.
     */
    RUN_IN_PROCESS,
    ;
  }

  private static final Supplier<String> DEFAULT_GET_CUSTOM_DX = new Supplier<String>() {
    @Override
    @CheckForNull
    public String get() {
      return Strings.emptyToNull(System.getProperty("buck.dx"));
    }
  };

  private final Path outputDexFile;
  private final Set<Path> filesToDex;
  private final Set<Option> options;
  private final Supplier<String> getPathToCustomDx;

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   */
  public DxStep(Path outputDexFile, Iterable<Path> filesToDex) {
    this(outputDexFile, filesToDex, EnumSet.noneOf(DxStep.Option.class));
  }

  /**
   * @param outputDexFile path to the file where the generated classes.dex should go.
   * @param filesToDex each element in this set is a path to a .class file, a zip file of .class
   *     files, or a directory of .class files.
   * @param options to pass to {@code dx}.
   */
  public DxStep(Path outputDexFile, Iterable<Path> filesToDex, EnumSet<Option> options) {
    this(outputDexFile, filesToDex, options, DEFAULT_GET_CUSTOM_DX);
  }

  @VisibleForTesting
  DxStep(Path outputDexFile, Iterable<Path> filesToDex, EnumSet<Option> options,
      Supplier<String> getPathToCustomDx) {
    this.outputDexFile = outputDexFile;
    this.filesToDex = ImmutableSet.copyOf(filesToDex);
    this.options = Sets.immutableEnumSet(options);
    this.getPathToCustomDx = getPathToCustomDx;

    Preconditions.checkArgument(
        !options.contains(Option.RUN_IN_PROCESS) ||
            options.contains(Option.USE_CUSTOM_DX_IF_AVAILABLE),
        "In-process dexing is only supported with custom DX");
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();
    String dx = androidPlatformTarget.getDxExecutable().toString();

    if (options.contains(Option.USE_CUSTOM_DX_IF_AVAILABLE)) {
      String customDx = getPathToCustomDx.get();
      dx = customDx != null ? customDx : dx;
    }

    builder.add(dx);

    // Add the Xmx override, but not for in-process dexing, since the dexer won't understand it.
    // Also, if DX works in-process, it probably wouldn't need an enlarged Xmx.
    if (!XMX_OVERRIDE.isEmpty() && !options.contains(Option.RUN_IN_PROCESS)) {
      builder.add(XMX_OVERRIDE);
    }

    builder.add("--dex");

    // --statistics flag, if appropriate.
    if (context.getVerbosity().shouldPrintSelectCommandOutput()) {
      builder.add("--statistics");
    }

    if (options.contains(Option.NO_OPTIMIZE)) {
      builder.add("--no-optimize");
    }

    if (options.contains(Option.FORCE_JUMBO)) {
      builder.add("--force-jumbo");
    }

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("--verbose");
    }

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    builder.add("--output", projectFilesystem.resolve(outputDexFile).toString());
    for (Path fileToDex : filesToDex) {
      builder.add(projectFilesystem.resolve(fileToDex).toString());
    }

    return builder.build();
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    if (options.contains(Option.RUN_IN_PROCESS)) {
      return executeInProcess(context);
    } else {
      return super.execute(context);
    }
  }

  private int executeInProcess(ExecutionContext context) {
    ImmutableList<String> argv = getShellCommandInternal(context);

    // The first arguments should be ".../dx --dex" ("...\dx --dex on Windows).  Strip them off
    // because we bypass the dispatcher and go straight to the dexer.
    Preconditions.checkState(argv.get(0).endsWith("/dx") || argv.get(0).endsWith("\\dx"));
    Preconditions.checkState(argv.get(1).equals("--dex"));
    ImmutableList<String> args = argv.subList(2, argv.size());

    try {
      return new com.android.dx.command.dexer.Main().run(
          args.toArray(new String[args.size()]),
          context.getStdOut(),
          context.getStdErr()
      );
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    return verbosity.shouldPrintSelectCommandOutput();
  }

  @Override
  protected boolean shouldPrintStdout(Verbosity verbosity) {
    return verbosity.shouldPrintSelectCommandOutput();
  }

  @Override
  public String getShortName() {
    return "dx";
  }

}

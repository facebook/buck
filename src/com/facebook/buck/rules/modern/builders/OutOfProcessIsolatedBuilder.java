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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.console.ConsoleBuckEventListener;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogManager;

/** This implements out of process rule execution. */
public class OutOfProcessIsolatedBuilder {

  private static final Logger LOG = Logger.get(OutOfProcessIsolatedBuilder.class);
  private static final int NUM_ARGS = 6;

  /**
   * Entry point for out of process rule execution. This should be run within the build root
   * directory (i.e. within the root cell's root).
   *
   * <p>Expected usage: {@code this_binary <build_root> <root_cell> <rule_hash> } where build_root
   * is the shared cell path ancestor and contains the rule_hash serialized data.
   */
  public static void main(String[] args) {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
    LOG.info("Started buck at time [%s].", Instant.now());
    checkArguments(args);

    Console console = createConsole(args);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> handleException(thread, console, error));
    try {
      build(args, console);
    } catch (Exception e) {
      handleException(Thread.currentThread(), console, e);
    }

    System.exit(0);
  }

  private static void checkArguments(String[] args) {
    Preconditions.checkState(
        args.length == NUM_ARGS,
        "Expected %s arguments, got %s: <%s>",
        NUM_ARGS,
        args.length,
        Joiner.on(",").join(args));
  }

  private static Console createConsole(String[] args) {
    boolean isAnsiEscapeSequencesEnabled = Boolean.parseBoolean(args[4]);
    Verbosity verbosity = Verbosity.valueOf(args[5]);
    return new Console(
        verbosity,
        System.out,
        System.err,
        isAnsiEscapeSequencesEnabled ? Ansi.forceTty() : Ansi.withoutTty());
  }

  private static void handleException(Thread thread, Console console, Throwable throwable) {
    console.printErrorText(
        "Cannot execute a rule out of process. On RE worker. Thread: "
            + thread
            + System.lineSeparator()
            + Throwables.getStackTraceAsString(throwable));
    System.exit(1);
  }

  private static void build(String[] args, Console console) throws Exception {
    Path buildDir = Paths.get(args[0]);
    Path projectRoot = Paths.get(args[1]);
    HashCode hash = HashCode.fromString(args[2]);
    Path metadataPath = Paths.get(args[3]);

    new IsolatedBuildableBuilder(buildDir, projectRoot, metadataPath) {

      @Override
      protected Console createConsole() {
        return console;
      }

      @Override
      protected BuckEventBus createEventBus(Console console) {
        BuckEventBus buckEventBus =
            new DefaultBuckEventBus(new DefaultClock(), new BuildId("whatever"));
        buckEventBus.register(new ConsoleBuckEventListener(console));
        return buckEventBus;
      }
    }.build(hash);
  }
}

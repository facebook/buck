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
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.console.ConsoleBuckEventListener;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.LogManager;

/** This implements out of process rule execution. */
public class OutOfProcessIsolatedBuilder {
  private static final Logger LOG = Logger.get(OutOfProcessIsolatedBuilder.class);
  private static final int NUM_ARGS = 4;
  /**
   * Entry point for out of process rule execution. This should be run within the build root
   * directory (i.e. within the root cell's root).
   *
   * <p>Expected usage: {@code this_binary <build_root> <root_cell> <rule_hash> } where build_root
   * is the shared cell path ancestor and contains the rule_hash serialized data.
   */
  public static void main(String[] args)
      throws IOException, StepFailedException, InterruptedException {
    LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);

    LOG.info(String.format("Started buck at time [%s].", new Date()));
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          error.printStackTrace(System.err);
          System.exit(1);
        });
    Preconditions.checkState(
        args.length == NUM_ARGS,
        "Expected %s arguments, got %s: <%s>",
        NUM_ARGS,
        args.length,
        Joiner.on(",").join(args));
    Path buildDir = Paths.get(args[0]);
    Path projectRoot = Paths.get(args[1]);
    HashCode hash = HashCode.fromString(args[2]);
    Path metadataPath = Paths.get(args[3]);
    new IsolatedBuildableBuilder(buildDir, projectRoot, metadataPath) {

      @Override
      protected Console createConsole() {
        return new Console(
            Verbosity.STANDARD_INFORMATION, System.out, System.err, Ansi.withoutTty());
      }

      @Override
      protected BuckEventBus createEventBus(Console console) {
        DefaultBuckEventBus buckEventBus =
            new DefaultBuckEventBus(new DefaultClock(), new BuildId("whatever"));
        buckEventBus.register(new ConsoleBuckEventListener(console));
        return buckEventBus;
      }
    }.build(hash);
    System.exit(0);
  }
}

/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.facebook.buck.core.starlark.eventhandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * An event handler that prints to an OutErr stream pair in a canonical format, for example:
 *
 * <pre>
 * ERROR: /home/jrluser/src/workspace/x/BUILD:23:1: syntax error.
 * </pre>
 *
 * This syntax is parseable by Emacs's compile.el.
 */
public class PrintingEventHandler extends AbstractEventHandler implements EventHandler {

  /**
   * A convenient event-handler for terminal applications that prints all errors and warnings it
   * encounters to the error stream. STDOUT and STDERR events pass their output directly through to
   * the corresponding streams.
   */
  public static final PrintingEventHandler ERRORS_AND_WARNINGS_TO_STDERR =
      new PrintingEventHandler(EventKind.ERRORS_AND_WARNINGS_AND_OUTPUT);

  /**
   * A convenient event-handler for terminal applications that prints all errors it encounters to
   * the error stream. STDOUT and STDERR events pass their output directly through to the
   * corresponding streams.
   */
  public static final PrintingEventHandler ERRORS_TO_STDERR =
      new PrintingEventHandler(EventKind.ERRORS_AND_OUTPUT);

  /**
   * Setup a printing event handler that prints events matching the mask. Events are printed to the
   * System.out and System.err unless/until redirected by a call to setOutErr().
   */
  public PrintingEventHandler(Set<EventKind> mask) {
    super(mask);
  }

  /** Print a description of the specified event to the appropriate output or error stream. */
  @Override
  public void handle(Event event) {
    if (!getEventMask().contains(event.getKind())) {
      return;
    }
    try {
      switch (event.getKind()) {
        case STDOUT:
          System.out.write(event.getMessageBytes());
          System.out.flush();
          break;
        case STDERR:
          System.err.write(event.getMessageBytes());
          System.err.flush();
          break;
          // $CASES-OMITTED$
        default:
          StringBuilder builder = new StringBuilder();
          builder.append(event.getKind()).append(": ");
          if (event.getLocation() != null) {
            builder.append(event.getLocation()).append(": ");
          }
          builder.append(event.getMessage()).append("\n");
          System.err.write(builder.toString().getBytes(StandardCharsets.UTF_8));
          System.err.flush();
      }
    } catch (IOException e) {
      /*
       * Note: we can't print to System.out or System.err here,
       * because those will normally be set to streams which
       * translate I/O to STDOUT and STDERR events,
       * which would result in infinite recursion.
       */
      System.err.println(e.getMessage());
    }
  }
}

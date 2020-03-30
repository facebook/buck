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

package com.facebook.buck.util.console;

import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.Console;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.io.PrintStream;

/**
 * BuckEventListener that subscribes to {@code ConsoleEvent}, format and print them into the {@link
 * Console}
 */
public class ConsoleBuckEventListener implements BuckEventListener {
  private final Console console;

  public ConsoleBuckEventListener(Console console) {
    this.console = console;
  }

  /** Subscribes to {@code ConsoleEvent}, format and print them into the {@link Console} */
  @Subscribe
  public void subscribe(ConsoleEvent event) {
    ImmutableList<String> lines = ConsoleUtils.formatConsoleEvent(event, console.getAnsi());
    if (lines.isEmpty()) {
      return;
    }

    if (console.getVerbosity().shouldPrintStandardInformation()) {
      PrintStream rawStream = console.getStdErr().getRawStream();
      lines.forEach(rawStream::println);
    }
  }
}

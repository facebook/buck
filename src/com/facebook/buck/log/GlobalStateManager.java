/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.facebook.buck.util.Verbosity;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public class GlobalStateManager {
  private static final Logger LOG = Logger.get(GlobalStateManager.class);

  private static final GlobalStateManager SINGLETON = new GlobalStateManager();

  // Shared global state.
  private final ConcurrentMap<Long, String> threadIdToCommandId;

  // Global state required by the ConsoleHandler.
  private final ConcurrentMap<String, OutputStreamWriter> commandIdToConsoleHandlerWriter;
  private final ConcurrentMap<String, Level> commandIdToConsoleHandlerLevel;

  public static GlobalStateManager singleton() {
    return SINGLETON;
  }

  public GlobalStateManager() {
    threadIdToCommandId = new ConcurrentHashMap<>();
    commandIdToConsoleHandlerWriter = new ConcurrentHashMap<>();
    commandIdToConsoleHandlerLevel = new ConcurrentHashMap<>();
  }

  public Closeable setupLoggers(
      InvocationInfo info,
      OutputStream consoleHandlerStream,
      final Optional<OutputStream> consoleHandlerOriginalStream,
      final Optional<Verbosity> consoleHandlerVerbosity) {

    final long threadId = Thread.currentThread().getId();
    final String commandId = info.getCommandId();

    // Setup the shared state.
    threadIdToCommandId.putIfAbsent(threadId, commandId);

    // Setup the ConsoleHandler state.
    commandIdToConsoleHandlerWriter.put(
        commandId,
        ConsoleHandler.utf8OutputStreamWriter(consoleHandlerStream));
    if (consoleHandlerVerbosity.isPresent() &&
        Verbosity.ALL.equals(consoleHandlerVerbosity.get())) {
      commandIdToConsoleHandlerLevel.put(commandId, Level.ALL);
    }

    // Setup the LogFileHandler state.
    Path logDirectory = info.getLogDirectoryPath();
    try {
      Files.createDirectories(logDirectory);
    } catch (IOException e) {
      LOG.error(
          e,
          "Failed to created 'per command log directory': [%s]",
          logDirectory.toAbsolutePath());
    }

    return new Closeable() {
      @Override
      public void close() throws IOException {
        // Tear down the ConsoleHandler state.
        if (consoleHandlerOriginalStream.isPresent()) {
          commandIdToConsoleHandlerWriter.put(
              commandId,
              ConsoleHandler.utf8OutputStreamWriter(consoleHandlerOriginalStream.get()));
        } else {
          commandIdToConsoleHandlerWriter.remove(commandId);
        }
        commandIdToConsoleHandlerLevel.remove(commandId);

        // Tear down the shared state.
        // NOTE: Avoid iterator in case there's a concurrent change to this map.
        List<Long> allKeys = Lists.newArrayList(threadIdToCommandId.keySet());
        for (Long threadId : allKeys) {
          if (commandId.equals(threadIdToCommandId.get(threadId))) {
            threadIdToCommandId.remove(threadId);
          }
        }
      }
    };
  }

  public CommonThreadFactoryState getThreadToCommandRegister() {
    return new CommonThreadFactoryState() {
      @Override
      public String threadIdToCommandId(long threadId) {
        return threadIdToCommandId.get(threadId);
      }

      @Override
      public void register(long threadId, String commandId) {
        threadIdToCommandId.put(threadId, commandId);
      }
    };
  }

  public ConsoleHandlerState getConsoleHandlerState() {
    return new ConsoleHandlerState() {
      @Override
      public OutputStreamWriter getWriter(String commandId) {
        return commandIdToConsoleHandlerWriter.get(commandId);
      }

      @Override
      public Iterable<OutputStreamWriter> getAllAvailableWriters() {
        return commandIdToConsoleHandlerWriter.values();
      }

      @Override
      public Level getLogLevel(String commandId) {
        return commandIdToConsoleHandlerLevel.get(commandId);
      }

      @Override
      public String threadIdToCommandId(long threadId) {
        return threadIdToCommandId.get(threadId);
      }
    };
  }

  public ThreadIdToCommandIdMapper getThreadIdToCommandIdMapper() {
    return new ThreadIdToCommandIdMapper() {
      @Override
      public String threadIdToCommandId(long threadId) {
        return threadIdToCommandId.get(threadId);
      }
    };
  }
}

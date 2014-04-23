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

package com.facebook.buck.plugin.intellij.commands;

import com.facebook.buck.plugin.intellij.commands.SocketClient.BuckPluginEventListener;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.Executors;

public class BuckRunner {

  private static final Logger LOG = Logger.getInstance(BuckRunner.class);
  private static final String BUCK_BIN = "buck";
  private static final String BUCKD_BIN = "buckd";
  private static final String BUCK_EXTRA_JAVA_ARGS = "BUCK_EXTRA_JAVA_ARGS";
  private static final String BUCKD_HTTPSERVER_PORT = "-Dbuck.httpserver.port";

  private final File workingDirectory;
  private String buckPath;
  private Optional<String> buckdPath;
  private Optional<SocketClient> socket;
  private BuckPluginEventListener listener;
  private String stdout;
  private String stderr;

  public BuckRunner(Project project,
                    Optional<String> buckDirectory,
                    BuckPluginEventListener listener) throws BuckNotFound {
    Preconditions.checkNotNull(project);
    Preconditions.checkNotNull(buckDirectory);
    this.listener = Preconditions.checkNotNull(listener);
    buckdPath = Optional.absent();
    socket = Optional.absent();
    stdout = "";
    stderr = "";
    workingDirectory = new File(project.getBasePath());
    Preconditions.checkState(workingDirectory.isDirectory(),
        String.format("%s is not a valid working directory.", workingDirectory));
    executorService = Executors.newFixedThreadPool(2); // For stdout and stderr stream readers
    if (!detectBuck(buckDirectory)) {
      throw new BuckNotFound();
    }
  }

  public int execute(String... args) {
    ImmutableList<String> command = ImmutableList.<String>builder()
        .add(buckPath)
        .addAll(ImmutableList.copyOf(args))
        .build();
    return execute(command, ImmutableMap.<String, String>of());
  }

  public int executeAndListenToWebsocket(String... args) {
    if (socket.isPresent()) {
      socket.get().start();
    }
    int exitCode = execute(args);
    if (socket.isPresent()) {
      socket.get().stop();
    }
    return exitCode;
  }

  public String getStdout() {
    return stdout;
  }

  public String getStderr() {
    return stderr;
  }

  private boolean detectBuck(Optional<String> buckDirectory) {
    if (buckDirectory.isPresent()) {
      String binDirectory = buckDirectory.get() + "/bin";
      if (detectBuckUnderDirectory(binDirectory)) {
        return true;
      }
    }
    // Find buck under system path
    String value = Strings.nullToEmpty(System.getenv("PATH"));
    for (String binDirectory : value.split(File.pathSeparator)) {
      if (detectBuckUnderDirectory(binDirectory)) {
        return true;
      }
    }
    return false;
  }

  private boolean detectBuckUnderDirectory(String binDirectory) {
    boolean success = false;
    File buck = new File(binDirectory, BUCK_BIN);
    if (buck.canExecute()) {
      buckPath = buck.getAbsolutePath();
      success = true;
    }
    File buckd = new File(binDirectory, BUCKD_BIN);
    if (buckd.canExecute()) {
      buckdPath = Optional.of(buckd.getAbsolutePath());
    }
    return success;
  }

  public void launchBuckd() {
    if (buckdPath.isPresent()) {
      try {
        int port = findAvailablePortForBuckdHttpServer();
        int exitCode = execute(ImmutableList.of(buckdPath.get()),
            ImmutableMap.<String, String>of(BUCK_EXTRA_JAVA_ARGS,
                String.format("%s=%d", BUCKD_HTTPSERVER_PORT, port)));
        if (exitCode != 0) {
          throw new Exception(getStderr());
        }
        socket = Optional.of(new SocketClient(port, listener));
      } catch (Exception e) {
        LOG.warn(String.format("Can not launch buckd: %s", e.getMessage()));
      }
    } else {
      LOG.warn("Can not find path to buckd, buck will be used barely.");
    }
  }

  private int findAvailablePortForBuckdHttpServer() throws IOException {
    ServerSocket server = new ServerSocket(0);
    int port = server.getLocalPort();
    server.close();
    return port;
  }

  public class BuckNotFound extends Exception {
  }
}

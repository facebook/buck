/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckDaemon.DaemonCommandExecutionScope;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.environment.Platform;
import com.facebook.nailgun.NGContext;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * The Main entry point for Nailgun calls.
 *
 * <p>This class maintains the state for statically storing daemon fields
 */
@SuppressWarnings("unused")
public class MainWithNailgun extends AbstractMain {

  private static final Logger LOG = Logger.get(MainWithNailgun.class);

  @Nullable private static FileLock resourcesFileLock = null;

  private static final Platform running_platform = Platform.detect();

  private final NGContext ngContext;

  public MainWithNailgun(NGContext ngContext) {
    super(
        ngContext.out,
        ngContext.err,
        ngContext.in,
        getClientEnvironment(ngContext),
        running_platform,
        Optional.of(ngContext));
    this.ngContext = ngContext;
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link MainRunner} so that the given context can be used to listen for client disconnections
   * and interrupt command processing when they occur.
   */
  @SuppressWarnings("unused")
  public static void nailMain(NGContext context) {
    obtainResourceFileLock();
    try (DaemonCommandExecutionScope ignored =
        BuckDaemon.getInstance().getDaemonCommandExecutionScope()) {

      MainWithNailgun mainWithNailgun = new MainWithNailgun(context);
      MainRunner mainRunner = mainWithNailgun.prepareMainRunner();
      mainRunner.runMainThenExit(context.getArgs(), System.nanoTime());
    }
  }

  /**
   * To prevent 'buck kill' from deleting resources from underneath a 'live' buckd we hold on to the
   * FileLock for the entire lifetime of the process. We depend on the fact that on Linux and MacOS
   * Java FileLock is implemented using the same mechanism as the Python fcntl.lockf method. Should
   * this not be the case we'll simply have a small race between buckd start and `buck kill`.
   */
  private static void obtainResourceFileLock() {
    if (resourcesFileLock != null) {
      return;
    }
    String resourceLockFilePath = System.getProperties().getProperty("buck.resource_lock_path");
    if (resourceLockFilePath == null) {
      // Running from ant, no resource lock needed.
      return;
    }
    try {
      // R+W+A is equivalent to 'a+' in Python (which is how the lock file is opened in Python)
      // because WRITE in Java does not imply truncating the file.
      FileChannel fileChannel =
          FileChannel.open(
              Paths.get(resourceLockFilePath),
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              StandardOpenOption.CREATE);
      resourcesFileLock = fileChannel.tryLock(0L, Long.MAX_VALUE, true);
    } catch (IOException | OverlappingFileLockException e) {
      LOG.warn(e, "Error when attempting to acquire resources file lock.");
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ImmutableMap<String, String> getClientEnvironment(NGContext context) {
    return ImmutableMap.copyOf((Map) context.getEnv());
  }
}

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

package com.facebook.buck.cli;

import com.facebook.buck.core.util.log.Logger;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Daemon killer that kills the daemon when domain socket used to communicate with it has been
 * deleted.
 */
class SocketLossKiller {
  private static final Logger LOG = Logger.get(SocketLossKiller.class);

  private final ScheduledExecutorService scheduledExecutorService;
  private final Path absolutePathToSocket;
  private final Runnable killTask;

  private AtomicBoolean isArmed = new AtomicBoolean();

  @GuardedBy("this")
  private @Nullable ScheduledFuture<?> checkSocketTask;

  public SocketLossKiller(
      ScheduledExecutorService scheduledExecutorService,
      Path absolutePathToSocket,
      Runnable killTask) {
    this.scheduledExecutorService = scheduledExecutorService;
    this.absolutePathToSocket = absolutePathToSocket;
    this.killTask = killTask;
  }

  /**
   * Start the service, recording the current socket file info.
   *
   * <p>If the socket does not exist or can't be accessed, the kill task will be called.
   *
   * <p>This is meant to be called after the server is started and socket created, such as in {@code
   * NailMain}. Repeated calls are no-ops.
   */
  public synchronized void arm() {
    if (isArmed.getAndSet(true)) {
      // Already armed.
      return;
    }
    try {
      BasicFileAttributes socketFileAttributes = readFileAttributes(absolutePathToSocket);
      checkSocketTask =
          scheduledExecutorService.scheduleAtFixedRate(
              () -> checkSocket(socketFileAttributes), 0, 5000, TimeUnit.MILLISECONDS);
    } catch (IOException e) {
      LOG.error(e, "Failed to read socket when arming SocketLossKiller.");
      cancelAndKill();
      return;
    }
  }

  private void checkSocket(BasicFileAttributes originalAttributes) {
    BasicFileAttributes newAttributes;
    try {
      newAttributes = readFileAttributes(absolutePathToSocket);
    } catch (NoSuchFileException | FileNotFoundException e) {
      // File no longer exists.
      cancelAndKill();
      return;
    } catch (IOException e) {
      // Some other error occurred.
      LOG.error(e, "Failed to check socket.");
      cancelAndKill();
      return;
    }
    synchronized (this) {
      // On platforms supporting file key, we make sure file keys are equal.
      // This may catch more cases than creation time if the socket is deleted and re-created
      // faster than can be distinguished by the timestamp resolution.
      //
      // Check modified time in addition to creation time because Windows lies about creation time
      // ("file system tunneling"). This feature is is still enabled in Windows 10.
      // See: https://support.microsoft.com/en-us/kb/172190
      if (newAttributes.fileKey() != null
          && !newAttributes.fileKey().equals(originalAttributes.fileKey())) {
        cancelAndKill();
      } else if (!newAttributes.creationTime().equals(originalAttributes.creationTime())
          || !newAttributes.lastModifiedTime().equals(originalAttributes.lastModifiedTime())) {
        cancelAndKill();
      }
    }
  }

  private synchronized void cancelAndKill() {
    if (checkSocketTask != null) {
      checkSocketTask.cancel(false);
      checkSocketTask = null;
    }
    killTask.run();
  }

  private static BasicFileAttributes readFileAttributes(Path path) throws IOException {
    return Files.getFileAttributeView(path, BasicFileAttributeView.class).readAttributes();
  }
}

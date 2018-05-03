/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Guards access to the daemon instance behind cell configuration checks. Creates or re-creates the
 * daemon instance as necessary if the cell configuration changes.
 */
@ThreadSafe
class DaemonLifecycleManager {
  private static final Logger LOG = Logger.get(DaemonLifecycleManager.class);

  @Nullable private volatile Daemon daemon;

  boolean hasDaemon() {
    return daemon != null;
  }

  /** Get or create Daemon. */
  synchronized Daemon getDaemon(
      Cell rootCell,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      ExecutableFinder executableFinder,
      Console console)
      throws IOException {
    Path rootPath = rootCell.getFilesystem().getRootPath();
    if (daemon == null) {
      LOG.debug("Starting up daemon for project root [%s]", rootPath);
      daemon =
          new Daemon(rootCell, knownBuildRuleTypesProvider, executableFinder, Optional.empty());
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      Path parserRoot = rootCell.getFilesystem().getRootPath();
      if (!rootPath.equals(parserRoot)) {
        throw new HumanReadableException(
            String.format("Unsupported root path change from %s to %s", rootPath, parserRoot));
      }

      // If Buck config has changed or SDKs have changed, invalidate the cache and
      // create a new daemon.
      Cell.IsCompatibleForCaching cacheCompat =
          daemon.getRootCell().isCompatibleForCaching(rootCell);
      if (cacheCompat != Cell.IsCompatibleForCaching.IS_COMPATIBLE) {
        LOG.warn(
            "Shutting down and restarting daemon on config or directory resolver change (%s != %s)",
            daemon.getRootCell(), rootCell);
        // Use the raw stream because otherwise this will stop superconsole from ever printing again
        console
            .getStdErr()
            .getRawStream()
            .println(
                console
                    .getAnsi()
                    .asWarningText(
                        String.format(
                            "Shutting down and restarting buck daemon: %s",
                            cacheCompat.toHumanReasonableError())));

        Optional<WebServer> webServer;
        if (shouldReuseWebServer(rootCell)) {
          webServer = daemon.getWebServer();
          LOG.info("Reusing web server");
        } else {
          webServer = Optional.empty();
          daemon.close();
        }
        daemon = new Daemon(rootCell, knownBuildRuleTypesProvider, executableFinder, webServer);
      }
    }
    return daemon;
  }

  /** Manually kill the daemon instance, used for testing. */
  synchronized void resetDaemon() {
    if (daemon != null) {
      LOG.info("Closing daemon on reset request.");
      daemon.close();
    }
    daemon = null;
  }

  private boolean shouldReuseWebServer(Cell newCell) {
    if (newCell == null || daemon == null) {
      return false;
    }
    Optional<Integer> portFromOldConfig =
        Daemon.getValidWebServerPort(daemon.getRootCell().getBuckConfig());
    Optional<Integer> portFromUpdatedConfig = Daemon.getValidWebServerPort(newCell.getBuckConfig());

    return portFromOldConfig.equals(portFromUpdatedConfig);
  }
}

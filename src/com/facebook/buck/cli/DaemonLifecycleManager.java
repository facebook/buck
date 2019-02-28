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

import com.facebook.buck.cli.DaemonCellChecker.IsCompatibleForCaching;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.listener.devspeed.DevspeedBuildListenerFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.timing.Clock;
import com.facebook.nailgun.NGContext;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
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
      KnownRuleTypesProvider knownRuleTypesProvider,
      Watchman watchman,
      Console console,
      Clock clock,
      Supplier<Optional<DevspeedBuildListenerFactory>> devspeedBuildListenerFactorySupplier,
      Optional<NGContext> context) {

    Daemon currentState = daemon;
    @Nullable String daemonRestartReason = null;

    // If Watchman failed to start, drop all caches
    if (daemon != null && watchman == WatchmanFactory.NULL_WATCHMAN) {
      // TODO(buck_team): make Watchman a requirement
      LOG.info("Restarting daemon because watchman failed to start");
      daemonRestartReason = "Watchman failed to start";
      daemon = null;
    }

    // If Buck config has changed or SDKs have changed, drop all caches
    if (daemon != null) {
      IsCompatibleForCaching cacheCompat =
          DaemonCellChecker.areCellsCompatibleForCaching(daemon.getRootCell(), rootCell);
      if (cacheCompat != IsCompatibleForCaching.IS_COMPATIBLE) {
        LOG.info(
            "Shutting down and restarting daemon on config or directory graphBuilder change (%s != %s)",
            daemon.getRootCell(), rootCell);
        daemonRestartReason = cacheCompat.toHumanReasonableError();
        daemon = null;
      }
    }

    // if we restart daemon, notify user that caches are screwed
    if (daemon == null
        && currentState != null
        && console.getVerbosity().shouldPrintStandardInformation()) {
      // Use the raw stream because otherwise this will stop superconsole from ever printing again
      console
          .getStdErr()
          .getRawStream()
          .println(
              console
                  .getAnsi()
                  .asWarningText(
                      String.format(
                          "Invalidating internal cached state: %s. This may cause slower builds.",
                          daemonRestartReason)));
    }

    // start new daemon, clean old one if needed
    if (daemon == null) {
      LOG.debug("Starting up daemon for project root [%s]", rootCell.getFilesystem().getRootPath());

      // try to reuse webserver from previous state
      // does it need to be in daemon at all?
      Optional<WebServer> webServer =
          currentState != null && shouldReuseWebServer(rootCell)
              ? currentState.getWebServer()
              : Optional.empty();

      if (currentState != null) {
        currentState.close();
      }

      daemon =
          new Daemon(
              rootCell,
              knownRuleTypesProvider,
              watchman,
              webServer,
              clock,
              devspeedBuildListenerFactorySupplier,
              context);
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
    OptionalInt portFromOldConfig =
        Daemon.getValidWebServerPort(daemon.getRootCell().getBuckConfig());
    OptionalInt portFromUpdatedConfig = Daemon.getValidWebServerPort(newCell.getBuckConfig());

    return portFromOldConfig.equals(portFromUpdatedConfig);
  }
}

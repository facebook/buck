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
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.listener.devspeed.DevspeedBuildListenerFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
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

  @Nullable private volatile BuckGlobalState buckGlobalState;

  /** Indicates whether a daemon is reused, or why it can't be reused */
  enum DaemonStatus {
    REUSED,
    NEW_DAEMON,
    INVALIDATED_NO_WATCHMAN,
    INVALIDATED_FILESYSTEM_CHANGED,
    INVALIDATED_BUCK_CONFIG_CHANGED,
    INVALIDATED_TOOLCHAINS_INCOMPATIBLE;

    private String toHumanReadableError() {
      switch (this) {
        case REUSED:
          return "Reusing existing daemon";
        case NEW_DAEMON:
          return "Initializing daemon for the first time";
        case INVALIDATED_NO_WATCHMAN:
          return "Watchman failed to start";
        case INVALIDATED_FILESYSTEM_CHANGED:
          return "The project directory changed between invocations";
        case INVALIDATED_BUCK_CONFIG_CHANGED:
          return "Buck configuration options changed between invocations";
        case INVALIDATED_TOOLCHAINS_INCOMPATIBLE:
          return "Available / configured toolchains changed between invocations";
        default:
          throw new AssertionError(String.format("Unknown value: %s", this));
      }
    }

    private static DaemonStatus fromCellInvalidation(IsCompatibleForCaching compatible) {
      switch (compatible) {
        case FILESYSTEM_CHANGED:
          return DaemonStatus.INVALIDATED_FILESYSTEM_CHANGED;
        case BUCK_CONFIG_CHANGED:
          return DaemonStatus.INVALIDATED_BUCK_CONFIG_CHANGED;
        case TOOLCHAINS_INCOMPATIBLE:
          return DaemonStatus.INVALIDATED_TOOLCHAINS_INCOMPATIBLE;
        case IS_COMPATIBLE:
          return DaemonStatus.REUSED;
        default:
          throw new AssertionError(String.format("Unknown value: %s", compatible));
      }
    }

    /** Get the string to be logged as an event, if an event should be logged. */
    protected Optional<String> newDaemonEvent() {
      switch (this) {
        case REUSED:
          return Optional.empty();
        case NEW_DAEMON:
          return Optional.of("DaemonInitialized");
        case INVALIDATED_NO_WATCHMAN:
          return Optional.of("DaemonWatchmanInvalidated");
        case INVALIDATED_FILESYSTEM_CHANGED:
          return Optional.of("DaemonFilesystemInvalidated");
        case INVALIDATED_BUCK_CONFIG_CHANGED:
          return Optional.of("DaemonBuckConfigInvalidated");
        case INVALIDATED_TOOLCHAINS_INCOMPATIBLE:
          return Optional.of("DaemonToolchainsInvalidated");
        default:
          throw new AssertionError(String.format("Unknown value: %s", this));
      }
    }
  }

  synchronized boolean hasDaemon() {
    return buckGlobalState != null;
  }

  synchronized Optional<BuckConfig> getBuckConfig() {
    return Optional.ofNullable(buckGlobalState)
        .map(BuckGlobalState::getRootCell)
        .map(Cell::getBuckConfig);
  }

  /** Get or create Daemon. */
  synchronized Pair<BuckGlobalState, DaemonStatus> getDaemon(
      Cell rootCell,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Watchman watchman,
      Console console,
      Clock clock,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      Supplier<Optional<DevspeedBuildListenerFactory>> devspeedBuildListenerFactorySupplier,
      Optional<NGContext> context) {

    BuckGlobalState currentState = buckGlobalState;
    DaemonStatus daemonStatus =
        buckGlobalState == null ? DaemonStatus.NEW_DAEMON : DaemonStatus.REUSED;

    // If Watchman failed to start, drop all caches
    if (buckGlobalState != null && watchman == WatchmanFactory.NULL_WATCHMAN) {
      // TODO(buck_team): make Watchman a requirement
      LOG.info("Restarting daemon because watchman failed to start");
      daemonStatus = DaemonStatus.INVALIDATED_NO_WATCHMAN;
      buckGlobalState = null;
    }

    // If Buck config has changed or SDKs have changed, drop all caches
    if (buckGlobalState != null) {
      IsCompatibleForCaching cacheCompat =
          DaemonCellChecker.areCellsCompatibleForCaching(buckGlobalState.getRootCell(), rootCell);
      if (cacheCompat != IsCompatibleForCaching.IS_COMPATIBLE) {
        LOG.info(
            "Shutting down and restarting daemon on config or directory graphBuilder change (%s != %s)",
            buckGlobalState.getRootCell(), rootCell);
        daemonStatus = DaemonStatus.fromCellInvalidation(cacheCompat);
        buckGlobalState = null;
      }
    }

    // if we restart daemon, notify user that caches are screwed
    if (buckGlobalState == null
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
                          daemonStatus.toHumanReadableError())));
    }

    // start new daemon, clean old one if needed
    if (buckGlobalState == null) {
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

      buckGlobalState =
          new BuckGlobalState(
              rootCell,
              knownRuleTypesProvider,
              watchman,
              webServer,
              unconfiguredBuildTargetFactory,
              targetConfigurationSerializer,
              clock,
              devspeedBuildListenerFactorySupplier,
              context);
    }

    return new Pair<>(buckGlobalState, daemonStatus);
  }

  /** Manually kill the daemon instance, used for testing. */
  synchronized void resetDaemon() {
    if (buckGlobalState != null) {
      LOG.info("Closing daemon on reset request.");
      buckGlobalState.close();
    }
    buckGlobalState = null;
  }

  private boolean shouldReuseWebServer(Cell newCell) {
    if (newCell == null || buckGlobalState == null) {
      return false;
    }
    OptionalInt portFromOldConfig =
        getBuckConfig().map(BuckGlobalState::getValidWebServerPort).orElse(OptionalInt.empty());
    OptionalInt portFromUpdatedConfig =
        BuckGlobalState.getValidWebServerPort(newCell.getBuckConfig());

    return portFromOldConfig.equals(portFromUpdatedConfig);
  }
}

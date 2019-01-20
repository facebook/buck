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

package com.facebook.buck.android.exopackage;

import com.android.ddmlib.InstallException;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.google.common.base.Throwables;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

class ExopackageAgent {
  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  private boolean useNativeAgent;
  private final String classPath;
  private final String nativeAgentPath;

  public ExopackageAgent(boolean useNativeAgent, String classPath, String nativeAgentPath) {
    this.useNativeAgent = useNativeAgent;
    this.classPath = classPath;
    this.nativeAgentPath = nativeAgentPath;
  }

  /**
   * Sets {@link #useNativeAgent} to true on pre-L devices, because our native agent is built
   * without -fPIC. The java agent works fine on L as long as we don't use it for mkdir.
   */
  private static boolean determineBestAgent(BuckEventBus eventBus, AndroidDevice device)
      throws Exception {
    String value;
    try (SimplePerfEvent.Scope scope = SimplePerfEvent.scope(eventBus, "get_device_sdk_version")) {
      value = device.getProperty("ro.build.version.sdk");
      scope.appendFinishedInfo("sdk_version", value);
    }
    try {
      if (Integer.parseInt(value) > 19) {
        return false;
      }
    } catch (NumberFormatException exn) {
      return false;
    }
    return true;
  }

  String getAgentCommand() {
    if (useNativeAgent) {
      return nativeAgentPath + "/libagent.so ";
    } else {
      return "dalvikvm -classpath " + classPath + " com.facebook.buck.android.agent.AgentMain ";
    }
  }

  public String getMkDirCommand() {
    // Kind of a hack here.  The java agent can't force the proper permissions on the
    // directories it creates, so we use the command-line "mkdir -p" instead of the java agent.
    // Fortunately, "mkdir -p" seems to work on all devices where we use use the java agent.
    return useNativeAgent ? getAgentCommand() + "mkdir-p" : "mkdir -p";
  }

  public static ExopackageAgent installAgentIfNecessary(
      BuckEventBus eventBus, AndroidDevice device, Path agentApkPath) {
    try {
      Optional<PackageInfo> agentInfo = device.getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
      if (agentInfo.isPresent()
          && !agentInfo.get().versionCode.equals(AgentUtil.AGENT_VERSION_CODE)) {
        LOG.debug(
            "Agent version mismatch. Wanted %s, got %s.",
            AgentUtil.AGENT_VERSION_CODE, agentInfo.get().versionCode);
        // Always uninstall before installing.  We might be downgrading, which requires
        // an uninstall, or we might just want a clean installation.
        uninstallAgent(eventBus, device);
        agentInfo = Optional.empty();
      }
      if (!agentInfo.isPresent()) {
        LOG.debug("Installing agent.");
        installAgentApk(eventBus, device, agentApkPath);
        agentInfo = device.getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
      }
      return new ExopackageAgent(
          determineBestAgent(eventBus, device),
          agentInfo.get().apkPath,
          agentInfo.get().nativeLibPath);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }

  private static void uninstallAgent(BuckEventBus eventBus, AndroidDevice device)
      throws InstallException {
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "uninstall_old_agent")) {
      device.uninstallPackage(AgentUtil.AGENT_PACKAGE_NAME);
    }
  }

  private static void installAgentApk(
      BuckEventBus eventBus, AndroidDevice device, Path agentApkPath) {
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "install_agent_apk")) {
      File apkPath = agentApkPath.toFile();
      boolean success =
          device.installApkOnDevice(apkPath, /* installViaSd */ false, /* quiet */ false);
      if (!success) {
        throw new RuntimeException();
      }
    }
  }
}

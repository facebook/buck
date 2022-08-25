/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.exopackage;

import static com.facebook.buck.android.exopackage.ScopeUtils.getEventScope;

import com.android.ddmlib.InstallException;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.google.common.base.Throwables;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

class ExopackageAgent {

  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  private final boolean useNativeAgent;
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
  private static boolean useNativeAgent(
      Optional<BuckEventBus> eventBus, AndroidDevice device, boolean alwaysUseJavaAgent)
      throws Exception {
    if (alwaysUseJavaAgent) {
      return false;
    }

    String value;
    try (AutoCloseable scope = getEventScope(eventBus, "get_device_sdk_version")) {

      value = device.getProperty("ro.build.version.sdk");
      if (scope instanceof SimplePerfEvent.Scope) {
        ((SimplePerfEvent.Scope) scope).appendFinishedInfo("sdk_version", value);
      }
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
    return getAgentCommand(/* javaLibraryPath */ null);
  }

  /**
   * @param javaLibraryPath The java library path that we want to use for the dalvikvm call. This is
   *     required because dalvikvm doesn't set up the java library path to point to the relevant
   *     directories within the APK.
   */
  String getAgentCommand(@Nullable String javaLibraryPath) {
    if (useNativeAgent) {
      return nativeAgentPath + "/libagent.so ";
    } else {
      if (javaLibraryPath != null) {
        return "dalvikvm -Djava.library.path="
            + javaLibraryPath
            + " -classpath "
            + classPath
            + " com.facebook.buck.android.agent.AgentMain ";
      } else {
        return "dalvikvm -classpath " + classPath + " com.facebook.buck.android.agent.AgentMain ";
      }
    }
  }

  String getNativePath() {
    return nativeAgentPath;
  }

  boolean isUseNativeAgent() {
    return useNativeAgent;
  }

  public String getMkDirCommand() {
    // Kind of a hack here.  The java agent can't force the proper permissions on the
    // directories it creates, so we use the command-line "mkdir -p" instead of the java agent.
    // Fortunately, "mkdir -p" seems to work on all devices where we use use the java agent.
    return useNativeAgent ? getAgentCommand() + "mkdir-p" : "mkdir -p";
  }

  public static ExopackageAgent installAgentIfNecessary(
      Optional<BuckEventBus> eventBus,
      AndroidDevice device,
      Path agentApkPath,
      boolean alwaysUseJavaAgent) {
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
      if (agentInfo.isEmpty()) {
        LOG.debug("Installing agent.");
        installAgentApk(eventBus, device, agentApkPath);
        agentInfo = device.getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
      }
      PackageInfo packageInfo = agentInfo.get();
      return new ExopackageAgent(
          useNativeAgent(eventBus, device, alwaysUseJavaAgent),
          packageInfo.apkPath,
          packageInfo.nativeLibPath);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }

  private static void uninstallAgent(Optional<BuckEventBus> eventBus, AndroidDevice device)
      throws InstallException {
    try (AutoCloseable ignored = getEventScope(eventBus, "uninstall_old_agent")) {
      device.uninstallPackage(AgentUtil.AGENT_PACKAGE_NAME);
    } catch (Exception e) {
      throw new InstallException(e);
    }
  }

  private static void installAgentApk(
      Optional<BuckEventBus> eventBus, AndroidDevice device, Path agentApkPath) {
    try (AutoCloseable ignored = getEventScope(eventBus, "install_agent_apk")) {
      File apkPath = agentApkPath.toFile();
      boolean success =
          device.installApkOnDevice(apkPath, /* installViaSd */ false, /* quiet */ false);
      if (!success) {
        throw new RuntimeException();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}

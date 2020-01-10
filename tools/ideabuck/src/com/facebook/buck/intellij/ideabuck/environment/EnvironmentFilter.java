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

package com.facebook.buck.intellij.ideabuck.environment;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Locale;
import java.util.Map;

/** Utility class to filter system environment maps. */
public class EnvironmentFilter {

  // Always exclude environment variables with these names.
  private static final ImmutableSet<String> ENV_TO_REMOVE =
      ImmutableSet.of(
          "ATOM_BACKUP_EDITOR", // Added by Nuclide editor
          "ATOM_DISABLE_SHELLING_OUT_FOR_ENVIRONMENT", // Added by Nuclide editor
          "ATOM_HOME", // Added by Nuclide editor
          "ARCANIST", // Phabricator / Arcanist cruft.
          "Apple_PubSub_Socket_Render", // OS X pubsub control variable.
          "BUCK_BUILD_ID", // Build ID passed in from Python.
          "BUCK_CLASSPATH", // Main classpath; set in Python
          "CHGHG", // Mercurial
          "CHGTIMEOUT", // Mercurial
          "CLASSPATH", // Bootstrap classpath; set in Python.
          "COLORFGBG",
          "COLUMNS",
          "CMD_DURATION", // Added to environment by 'fish' shell.
          "com.apple.java.jvmTask", // Added to environment by Apple JVM.
          "COMP_WORDBREAKS", // Set by the programmable completion part of bash.
          "DBUS_SESSION_BUS_ADDRESS",
          "DIFF_EDITOR", // Changes between terminals and GUIs
          "EDITOR", // Changes between terminals and GUIs
          "GOOGLE_API_KEY", // Added by Nuclide editor
          "GOROOT",
          "HG", // Mercurial
          "HGNODE", // Mercurial
          "HG_NODE", // Mercurial
          "HISTSIZE", // Bash history configuration.
          "HISTCONTROL", // Bash history configuration.
          "HPHP_INTERPRETER", // Added by hhvm.
          "ITERM_SESSION_ID", // Added by iTerm on OS X.
          "ITERM_ORIG_PS1", // Added by iTerm on OS X.
          "ITERM_PREV_PS1", // Added by iTerm on OS X.
          "ITERM_PROFILE", // Added by iTerm on OS X.
          "JAVA_ARCH", // More OS X cruft.
          "KRB5CCNAME", // Kerberos authentication adds this.
          "KRB5RCACHETYPE", // More Kerberos cruft.
          "LOG_SESSION_ID", // Session ID for certain environments.
          "LINES",
          "LS_COLORS", // Colour configuration for ls
          "MAIL",
          "MallocNanoZone", // Added to environment by Xcode
          "NODE_ENV", // Added by Nuclide editor
          "NODE_PATH", // Added by Nuclide editor
          "OLDPWD", // Previous working directory; set by bash's cd builtin.
          "PAR_LAUNCH_TIMESTAMP",
          "PROMPT_COMMAND", // Prompt control variable, just in case someone exports it.
          "PS1", // Same.
          "PS2", // Same.
          "PS3", // Same.
          "PS4", // Same.
          "PWD", // Current working directory; set by bash.
          "SECURITYSESSIONID", // Session ID for certain environments.
          "SHELL",
          "SHLVL", // Shell nestedness level; set by bash.
          "SSH_AGENT_PID", // SSH session management variable.
          "SSH_ASKPASS", // Same.
          "SSH_AUTH_SOCK", // Same.
          "SSH_CLIENT", // Same.
          "SSH_CONNECTION", // Same.
          "SSH_TTY", // Same.
          "SUDO_COMMAND", // Folks shouldn't run buck under sudo, but..
          "TERMCAP",
          "TERMINIX_ID", // Added by Terminix on Linux.
          "TERM_SESSION_ID", // UUID added to environment by OS X.
          "TERM_PROGRAM", // Added to environment by OS X.
          "TERM_PROGRAM_VERSION", // Added to environment by OS X.
          "TMUX", // tmux session management variable.
          "TMUX_PANE", // Current tmux pane.
          "WINDOW",
          "XDG_SESSION_ID", // Session ID for certain environments.
          "XPC_FLAGS", // More OS X cruft.
          "XPC_SERVICE_NAME" // Same.
          );

  // Ignore the environment variables with these names when comparing environments.
  private static final ImmutableSet<String> ENV_TO_IGNORE =
      ImmutableSet.of(
          "ANDROID_SERIAL", // Serial of the target Android device/emulator.
          "BUCK_TTY", // Whether buck's stdin is connected to a TTY
          "SCRIPT", // Populated by script command: http://www.freebsd.org/cgi/man.cgi?script
          "NAILGUN_TTY_0", // Nailgun stdin supports ANSI escape sequences.
          "NAILGUN_TTY_1", // Nailgun stdout supports ANSI escape sequences.
          "NAILGUN_TTY_2", // Nailgun stderr supports ANSI escape sequences.
          "TERM" // The type of terminal we're connected to, used by AnsiEnvironmentChecking
          );

  // Ignore environment variables whose names start with this string.
  private static final ImmutableSet<String> ENV_PREFIXES_TO_IGNORE =
      ImmutableSet.of(
          "JAVA_MAIN_CLASS_", "SCM_" // Source control, e.g. SCM_WORKER_EXE
          );

  public static final Predicate<String> NOT_IGNORED_ENV_PREDICATE =
      Predicates.not(
          Predicates.or(
              ENV_TO_IGNORE::contains,
              value -> {
                for (String prefix : ENV_PREFIXES_TO_IGNORE) {
                  if (value.startsWith(prefix)) {
                    return true;
                  }
                }
                return false;
              }));

  // Utility class, do not instantiate.
  private EnvironmentFilter() {}

  /**
   * Given a map (environment variable name: environment variable value) pairs, returns a map
   * without the variables which we should not pass to child processes (buck.py, javac, etc.)
   *
   * <p>Keeping the environment map clean helps us avoid jettisoning the parser cache, as we have to
   * rebuild it any time the environment changes.
   */
  public static ImmutableMap<String, String> filteredEnvironment(
      ImmutableMap<String, String> environment, Platform platform) {
    ImmutableMap.Builder<String, String> filteredEnvironmentBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> envEntry : environment.entrySet()) {
      String key = envEntry.getKey();
      if (!ENV_TO_REMOVE.contains(key)) {
        if (platform == Platform.WINDOWS) {
          // Windows environment variables are case insensitive.  While an ImmutableMap will throw
          // if we get duplicate key, we don't have to worry about this for Windows.
          filteredEnvironmentBuilder.put(key.toUpperCase(Locale.US), envEntry.getValue());
        } else {
          filteredEnvironmentBuilder.put(envEntry);
        }
      }
    }
    return filteredEnvironmentBuilder.build();
  }
}

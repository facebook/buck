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

import static com.facebook.buck.util.MoreThrowables.getInitialCause;
import static com.facebook.buck.util.MoreThrowables.getThrowableOrigin;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.concurrent.ThreadIdToCommandIdMapper;
import com.facebook.buck.util.environment.BuckBuildType;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractErrorLogRecord {

  private static final ThreadIdToCommandIdMapper MAPPER =
      GlobalStateManager.singleton().getThreadIdToCommandIdMapper();
  private static final CommandIdToIsDaemonMapper IS_DAEMON_MAPPER =
      GlobalStateManager.singleton().getCommandIdToIsDaemonMapper();
  private static final CommandIdToIsSuperConsoleEnabledMapper IS_SUPERCONSOLE_ENABLED_MAPPER =
      GlobalStateManager.singleton().getCommandIdToIsSuperConsoleEnabledMapper();
  private static final CommandIdToIsRemoteExecutionMapper IS_REMOTE_EXECUTION_MAPPER =
      GlobalStateManager.singleton().getCommandIdToIsRemoteExecutionMapper();
  private static final Logger LOG = Logger.get(AbstractErrorLogRecord.class);

  public abstract LogRecord getRecord();

  public abstract ImmutableList<String> getLogs();

  @Value.Derived
  public ImmutableMap<String, String> getTraits() {
    String logger = getRecord().getLoggerName();
    String hostname = "unknown";
    try {
      hostname = HostnameFetching.getHostname();
    } catch (IOException e) {
      LOG.debug(e, "Unable to fetch hostname");
    }
    return ImmutableMap.<String, String>builder()
        .put("severity", getRecord().getLevel().toString())
        .put("logger", logger != null ? logger : "unknown")
        .put("buckGitCommit", System.getProperty("buck.git_commit", "unknown"))
        .put("javaVersion", System.getProperty("java.version", "unknown"))
        .put("os", System.getProperty("os.name", "unknown"))
        .put("osVersion", System.getProperty("os.version", "unknown"))
        .put("user", System.getProperty("user.name", "unknown"))
        .put("buckBinaryBuildType", BuckBuildType.CURRENT_BUCK_BUILD_TYPE.get().toString())
        .put("hostname", hostname)
        .put("isConsoleEnabled", getIsSuperConsoleEnabled().map(Object::toString).orElse("null"))
        .put("isDaemon", getIsDaemon().map(Object::toString).orElse("null"))
        .put("commandId", getBuildUuid().orElse("null"))
        .put("isRemoteExecution", getIsRemoteExecution().map(Object::toString).orElse("null"))
        .build();
  }

  @Value.Derived
  public String getMessage() {
    Optional<String> initialErr = Optional.empty();
    Optional<String> initialErrorMsg = Optional.empty();
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      initialErr = Optional.ofNullable(getInitialCause(throwable).getClass().getName());
      if (throwable.getMessage() != null) {
        initialErrorMsg = Optional.ofNullable(getInitialCause(throwable).getLocalizedMessage());
      }
    }

    Optional<String> errorMsg;
    String message = getRecord().getMessage();
    Object[] parameters = getRecord().getParameters();
    if (message != null && parameters != null) {
      errorMsg = Optional.ofNullable(String.format(message, parameters));
    } else {
      errorMsg = Optional.ofNullable(message);
    }
    StringBuilder sb = new StringBuilder();
    for (Optional<String> field : ImmutableList.of(initialErr, initialErrorMsg, errorMsg)) {
      sb.append(field.orElse(""));
      if (field.isPresent()) {
        sb.append(": ");
      }
    }
    sb.append(getRecord().getLoggerName());
    return sb.toString();
  }

  /**
   * Computes a category key based on relevant LogRecord information. If an exception is present,
   * categorizes on the class + method that threw it. If no exception is found, categorizes on the
   * logger name and the beginning of the message.
   */
  @Value.Default
  public String getCategory() {
    String logger = "";
    if (getRecord().getLoggerName() != null) {
      logger = getRecord().getLoggerName();
    }
    StringBuilder sb = new StringBuilder(logger).append(":");
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      Throwable originalThrowable = getInitialCause(throwable);
      if (originalThrowable.getStackTrace().length > 0) {
        sb.append(extractClassMethod(getThrowableOrigin(originalThrowable)));
        return sb.toString();
      }
    }
    sb.append(truncateMessage(getRecord().getMessage()));
    return sb.toString();
  }

  @Value.Derived
  public long getTime() {
    return TimeUnit.MILLISECONDS.toSeconds(getRecord().getMillis());
  }

  @Value.Derived
  public Optional<String> getLogger() {
    return Optional.ofNullable(getRecord().getLoggerName());
  }

  @Value.Derived
  public Optional<String> getBuildUuid() {
    String buildUuid = MAPPER.threadIdToCommandId(getRecord().getThreadID());
    return Optional.ofNullable(buildUuid);
  }

  @Value.Derived
  public Optional<Boolean> getIsSuperConsoleEnabled() {
    String buildUuid = MAPPER.threadIdToCommandId(getRecord().getThreadID());
    if (buildUuid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        IS_SUPERCONSOLE_ENABLED_MAPPER.commandIdToIsSuperConsoleEnabled(buildUuid));
  }

  @Value.Derived
  public Optional<Boolean> getIsDaemon() {
    String buildUuid = MAPPER.threadIdToCommandId(getRecord().getThreadID());
    if (buildUuid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(IS_DAEMON_MAPPER.commandIdToIsRunningAsDaemon(buildUuid));
  }

  @Value.Derived
  public Optional<Boolean> getIsRemoteExecution() {
    String buildUuid = MAPPER.threadIdToCommandId(getRecord().getThreadID());
    if (buildUuid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        IS_REMOTE_EXECUTION_MAPPER.commandIdToIsRunningAsRemoteExecution(buildUuid));
  }

  @Value.Derived
  public Optional<StackTraceElement[]> getStack() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.ofNullable(throwable.getStackTrace());
    }
    return Optional.empty();
  }

  @Value.Derived
  public Optional<String> getErrorMessage() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null && throwable.getMessage() != null) {
      return Optional.ofNullable(throwable.getMessage());
    }
    return Optional.empty();
  }

  @Value.Derived
  public Optional<String> getInitialError() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.ofNullable(getInitialCause(throwable).getClass().getName());
    }
    return Optional.empty();
  }

  @Value.Derived
  public Optional<String> getInitialErrorMsg() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.ofNullable(getInitialCause(throwable).getLocalizedMessage());
    }
    return Optional.empty();
  }

  @Value.Derived
  public Optional<String> getOrigin() {
    Throwable throwable = getRecord().getThrown();
    if (throwable != null) {
      return Optional.ofNullable(getThrowableOrigin(throwable));
    }
    return Optional.empty();
  }

  /**
   * We expect uploaded log records to contain a stack trace, but if they don't the logged message
   * is important. To address the issue that these records often contain parametrized values, only
   * first word (1 & 2 if first has 2 or less chars) of message is taken into account.
   */
  private String truncateMessage(String name) {
    String[] words = name.split("\\s+");
    if (words.length > 1 && words[0].length() < 3) {
      return words[0] + " " + words[1];
    }
    return words[0];
  }

  /**
   * Extracts minimum valuable information set from lines in the following format:
   * package.classname.method(filename:line_number)
   */
  private String extractClassMethod(String name) {
    if (name != null) {
      return name.split("\\(", 1)[0];
    }
    return "";
  }
}

/*
 * Copyright 2014-present Facebook, Inc.
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

/*
 * Copyright 2010 Proofpoint, Inc.
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

package com.facebook.buck.core.util.log;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINER;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import com.facebook.buck.core.util.log.appendablelogrecord.AppendableLogRecord;
import java.util.logging.Handler;
import java.util.logging.Level;
import javax.annotation.Nullable;

@SuppressWarnings("PMD")
public class Logger {
  private final java.util.logging.Logger logger;

  Logger(java.util.logging.Logger logger) {
    this.logger = logger;
  }

  /**
   * Gets a logger named after a class' fully qualified name.
   *
   * @param clazz the class
   * @return the named logger
   */
  public static Logger get(Class<?> clazz) {
    return get(clazz.getName().replace('$', '.'));
  }

  /**
   * Gets a named logger
   *
   * @param name the name of the logger
   * @return the named logger
   */
  public static Logger get(String name) {
    java.util.logging.Logger logger = java.util.logging.Logger.getLogger(name);
    return new Logger(logger);
  }

  /**
   * Logs a message at VERBOSE level.
   *
   * @param exception an exception associated with the verbose message being logged
   * @param message a literal message to log
   */
  public void verbose(Throwable exception, String message) {
    logger.log(FINER, message, exception);
  }

  /**
   * Logs a message at VERBOSE level.
   *
   * @param message a literal message to log
   */
  public void verbose(String message) {
    logger.finer(message);
  }

  /**
   * Logs a message at VERBOSE level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.verbose("value is %s (%d ms)", value, time);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void verbose(String format, Object... args) {
    verbose(null, format, args);
  }

  /**
   * Logs a message at VERBOSE level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.verbose(e, "value is %s (%d ms)", value, time);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param exception an exception associated with the verbose message being logged
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void verbose(@Nullable Throwable exception, String format, Object... args) {
    logAppendableLogRecord(FINER, "VERBOSE", exception, format, args);
  }

  /**
   * Logs a message at DEBUG level.
   *
   * @param exception an exception associated with the debug message being logged
   * @param message a literal message to log
   */
  public void debug(Throwable exception, String message) {
    logger.log(FINE, message, exception);
  }

  /**
   * Logs a message at DEBUG level.
   *
   * @param message a literal message to log
   */
  public void debug(String message) {
    logger.fine(message);
  }

  /**
   * Logs a message at DEBUG level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.debug("value is %s (%d ms)", value, time);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void debug(String format, Object... args) {
    debug(null, format, args);
  }

  /**
   * Logs a message at DEBUG level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.debug(e, "value is %s (%d ms)", value, time);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param exception an exception associated with the debug message being logged
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void debug(@Nullable Throwable exception, String format, Object... args) {
    logAppendableLogRecord(FINE, "DEBUG", exception, format, args);
  }

  /**
   * Logs a message at INFO level.
   *
   * @param message a literal message to log
   */
  public void info(String message) {
    logger.info(message);
  }

  /**
   * Logs a message at INFO level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.info("value is %s (%d ms)", value, time);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param exception an exception associated with the warning being logged
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void info(@Nullable Throwable exception, String format, Object... args) {
    logAppendableLogRecord(INFO, "INFO", exception, format, args);
  }

  /**
   * Logs a message at INFO level.
   *
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void info(String format, Object... args) {
    info(null, format, args);
  }

  /**
   * Logs a message at WARN level.
   *
   * @param exception an exception associated with the warning being logged
   * @param message a literal message to log
   */
  public void warn(Throwable exception, String message) {
    logger.log(WARNING, message, exception);
  }

  /**
   * Logs a message at WARN level.
   *
   * @param message a literal message to log
   */
  public void warn(String message) {
    logger.warning(message);
  }

  /**
   * Logs given message at given level.
   *
   * @param level level
   * @param message message to log
   */
  public void logWithLevel(Level level, String message) {
    logger.log(level, message);
  }

  /**
   * Logs a message at WARN level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.warn(e, "something bad happened when connecting to %s:%d", host, port);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param exception an exception associated with the warning being logged
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void warn(@Nullable Throwable exception, String format, Object... args) {
    logAppendableLogRecord(WARNING, "WARN", exception, format, args);
  }

  /**
   * Logs a message at WARN level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.warn("something bad happened when connecting to %s:%d", host, port);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void warn(String format, Object... args) {
    warn(null, format, args);
  }

  /**
   * Logs a message at ERROR level.
   *
   * @param exception an exception associated with the error being logged
   * @param message a literal message to log
   */
  public void error(Throwable exception, String message) {
    logger.log(SEVERE, message, exception);
  }

  /**
   * Logs a message at ERROR level.
   *
   * @param message a literal message to log
   */
  public void error(String message) {
    logger.severe(message);
  }

  /**
   * Logs a message at ERROR level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.error(e, "something really bad happened when connecting to %s:%d", host, port);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param exception an exception associated with the error being logged
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void error(@Nullable Throwable exception, String format, Object... args) {
    logAppendableLogRecord(SEVERE, "ERROR", exception, format, args);
  }

  /**
   * Logs a message at ERROR level. The value of {@code exception.getMessage()} will be used as the
   * log message. <br>
   * Usage example:
   *
   * <pre>
   *    logger.error(e);
   * </pre>
   *
   * @param exception an exception associated with the error being logged
   */
  public void error(Throwable exception) {
    if (logger.isLoggable(SEVERE)) {
      logger.log(SEVERE, exception.getMessage(), exception);
    }
  }

  /**
   * Logs a message at ERROR level. <br>
   * Usage example:
   *
   * <pre>
   *    logger.error("something really bad happened when connecting to %s:%d", host, port);
   * </pre>
   *
   * If the format string is invalid or the arguments are insufficient, an error will be logged and
   * execution will continue.
   *
   * @param format a format string compatible with String.format()
   * @param args arguments for the format string
   */
  public void error(String format, Object... args) {
    error(null, format, args);
  }

  private void logAppendableLogRecord(
      Level level,
      String displayLevel,
      @Nullable Throwable exception,
      String format,
      Object... args) {
    if (logger.isLoggable(level)) {
      AppendableLogRecord lr = new AppendableLogRecord(level, displayLevel, format);
      lr.setParameters(args);
      lr.setThrown(exception);
      lr.setLoggerName(logger.getName());
      logger.log(lr);
    }
  }

  public boolean isVerboseEnabled() {
    return logger.isLoggable(FINER);
  }

  public boolean isDebugEnabled() {
    return logger.isLoggable(FINE);
  }

  public Level getLevel() {
    return logger.getLevel();
  }

  public void setLevel(Level newLevel) {
    logger.setLevel(newLevel);
  }

  public boolean isLoggable(Level level) {
    return logger.isLoggable(level);
  }

  public void addHandler(Handler handler) {
    logger.addHandler(handler);
  }

  public Handler[] getHandlers() {
    return logger.getHandlers();
  }
}

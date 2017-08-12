// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.logging;

public class Log {

  static public final boolean ENABLED = false;

  static private final boolean VERBOSE_ENABLED = false;
  static private final boolean INFO_ENABLED = true;
  static private final boolean DEBUG_ENABLED = true;
  static private final boolean WARN_ENABLED = true;

  public static void verbose(Class<?> from, String message, Object... arguments) {
    if (ENABLED && VERBOSE_ENABLED && isClassEnabled(from)) {
      log("VERB", from, message, arguments);
    }
  }

  public static void info(Class<?> from, String message, Object... arguments) {
    if (ENABLED && INFO_ENABLED && isClassEnabled(from)) {
      log("INFO", from, message, arguments);
    }
  }

  public static void debug(Class<?> from, String message, Object... arguments) {
    if (ENABLED && DEBUG_ENABLED && isClassEnabled(from)) {
      log("DBG", from, message, arguments);
    }
  }

  public static void warn(Class<?> from, String message, Object... arguments) {
    if (ENABLED && WARN_ENABLED && isClassEnabled(from)) {
      log("WARN", from, message, arguments);
    }
  }

  private static boolean isClassEnabled(Class<?> clazz) {
    return true;
  }

  synchronized private static void log(String kind, Class<?> from, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    System.out.println("[" + kind + "] {" + from.getSimpleName() + "}: " + message);
  }
}

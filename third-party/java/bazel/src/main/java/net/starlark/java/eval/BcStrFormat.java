package net.starlark.java.eval;

/** Utilities for string format compilation. */
class BcStrFormat {
  /** Check that format string has only one `%s` and no other `%` */
  static int indexOfSinglePercentS(String format) {
    int percent = format.indexOf('%');
    if (percent < 0) {
      return -1;
    }
    if (format.length() >= percent + 2
        && format.charAt(percent + 1) == 's'
        && format.indexOf('%', percent + 2) < 0) {
      return percent;
    } else {
      return -1;
    }
  }
}

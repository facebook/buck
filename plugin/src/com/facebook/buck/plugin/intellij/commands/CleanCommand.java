package com.facebook.buck.plugin.intellij.commands;

import com.intellij.openapi.diagnostic.Logger;

public class CleanCommand {

  private static final Logger LOG = Logger.getInstance(CleanCommand.class);

  private CleanCommand() {}

  public static void clean(BuckRunner buckRunner) {
    int exitCode = buckRunner.execute("clean");
    if (exitCode != 0) {
      LOG.error(buckRunner.getStderr());
      return;
    }
  }
}

package com.facebook.buck.plugin.intellij.commands;

import com.facebook.buck.plugin.intellij.BuckTarget;
import com.intellij.openapi.diagnostic.Logger;

public class BuildCommand {

  private static final Logger LOG = Logger.getInstance(BuildCommand.class);

  private BuildCommand() {}

  public static void build(BuckRunner buckRunner, BuckTarget target) {
    int exitCode = buckRunner.execute("build", target.getName());
    if (exitCode != 0) {
      LOG.error(buckRunner.getStderr());
      return;
    }
  }
}

package com.facebook.buck.plugin.intellij.commands;

import com.facebook.buck.plugin.intellij.BuckTarget;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.diagnostic.Logger;

public class TargetsCommand {

  private static final Logger LOG = Logger.getInstance(TargetsCommand.class);

  private TargetsCommand() {}

  public static ImmutableList<BuckTarget> getTargets(BuckRunner buckRunner) {
    int exitCode = buckRunner.execute("targets");
    if (exitCode != 0) {
      LOG.error(buckRunner.getStderr());
      return ImmutableList.of();
    }

    // Parse output
    String targetsText = buckRunner.getStdout();
    ImmutableList<String> targetNames = ImmutableList.copyOf(targetsText.split("\n"));

    // Create BuckTarget objects
    ImmutableList.Builder<BuckTarget> builder = ImmutableList.builder();
    for (String targetName : targetNames) {
      builder.add(new BuckTarget(targetName));
    }
    ImmutableList<BuckTarget> targets = builder.build();
    return targets;
  }
}

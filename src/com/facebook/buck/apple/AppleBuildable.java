package com.facebook.buck.apple;

import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.SourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A buildable that has configuration ready for Xcode-like build systems.
 */
public interface AppleBuildable extends Buildable {

  /**
   * Returns a path to the info.plist to be bundled with a binary or framework.
   */
  Path getInfoPlist();

  /**
   * Returns a set of Xcode configuration rules.
   */
  ImmutableSet<XcodeRuleConfiguration> getConfigurations();

  /**
   * Returns a list of sources, potentially grouped for display in Xcode.
   */
  ImmutableList<GroupedSource> getSrcs();

  /**
   * Returns a list of per-file build flags, e.g. -fobjc-arc.
   */
  ImmutableMap<SourcePath, String> getPerFileFlags();

  /**
   * Returns the set of frameworks to link with the target.
   */
  ImmutableSortedSet<String> getFrameworks();
}

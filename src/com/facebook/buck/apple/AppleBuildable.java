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

/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;

import java.util.List;

public class RunCommandOptions extends AbstractCommandOptions {
  /**
   * Expected usage:
   * <pre>
   *   buck run //java/com/facebook/tools/munge:munge --mungearg /tmp/input
   * </pre>
   */
  @Argument
  private List<String> arguments = Lists.newArrayList();

  public RunCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public List<String> getArguments() { return arguments; }

  /** @return the arguments (if any) to be passed to the target command. */
  public List<String> getTargetArguments() {
    return arguments.subList(1, arguments.size());
  }

  public boolean hasTargetSpecified() {
    return arguments.size() > 0;
  }

  /** @return the normalized target name for command to run. */
  public String getTarget() {
      return getCommandLineBuildTargetNormalizer().normalize(arguments.get(0));
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.build;

/**
 * The descriptor of buck command.
 */
public class BuckCommand {

  public static final BuckCommand BUILD = new BuckCommand("build");
  public static final BuckCommand INSTALL = new BuckCommand("install");
  public static final BuckCommand UNINSTALL = new BuckCommand("uninstall");
  public static final BuckCommand KILL = new BuckCommand("kill");

  /**
   * Command name passed to buck.
   */
  private final String name;

  private BuckCommand(String name) {
    this.name = name;
  }

  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}

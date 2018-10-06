/*
 * Copyright 2018-present Facebook, Inc.
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

import java.util.Objects;
import javax.annotation.Nullable;
import org.pf4j.PluginManager;

/**
 * An implementation of {@link Command} that allows keeping {@link PluginManager}.
 *
 * <p>The {@link PluginManager} is not passed through the constructor because instances of {@link
 * Command} are created by the args4j framework by calling the default constructor (the one without
 * arguments). The {@link PluginManager} is saved by calling {@link
 * #setPluginManager(PluginManager)} right after the creation of the command. Also, CLI arguments
 * are parsed on a single thread so we don't need to worry about concurrent access.
 */
public abstract class CommandWithPluginManager implements Command {

  @Nullable private PluginManager pluginManager;

  @Override
  public void setPluginManager(PluginManager pluginManager) {
    this.pluginManager = pluginManager;
  }

  @Override
  public PluginManager getPluginManager() {
    return Objects.requireNonNull(pluginManager);
  }
}

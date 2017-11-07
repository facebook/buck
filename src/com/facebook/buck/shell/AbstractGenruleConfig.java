/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Configuration options that control the behavior of {@code genrule}. */
@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
public abstract class AbstractGenruleConfig implements ConfigView<BuckConfig> {

  private static final String GENRULE_CONFIG_SECTION = "genrule";

  /**
   * Controls whether genrules pass paths to symlinks or to the actual files in {@code SRCS}
   * environment variable.
   *
   * <p>Note: the purpose of this configuration options is to provide gradual migration from the
   * existing logic of passing paths to the actual source files to use the paths to symlinks. The
   * main reason to do so is to allow better isolation of genrules which should enabled running
   * genrules in a sandbox.
   *
   * @return true if genrule should pass symlinks in {@code SRCS} environment variable
   */
  public boolean getUseSymlinksInSources() {
    return getDelegate().getBooleanValue(GENRULE_CONFIG_SECTION, "use_symlinks_in_srcs", false);
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.js;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;

/**
 * A react-native view of {@link BuckConfig}.
 */
public class ReactNativeBuckConfig {

  private final BuckConfig delegate;

  public ReactNativeBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * TODO(natthu): return a Tool instead.
   *
   * @return Path to the react native javascript packager.
   */
  public Optional<SourcePath> getPackager() {
    return delegate.getSourcePath("react-native", "packager");
  }
}

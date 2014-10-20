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

package com.facebook.buck.android;

import com.facebook.buck.cli.BuckConfig;
import com.google.common.base.Optional;

import java.nio.file.Path;

public class ProGuardConfig {

  private final BuckConfig delegate;

  public ProGuardConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return The path to the proguard.jar file that is overridden by the current project.  If not
   * specified, the Android platform proguard.jar will be used.
   */
  public Optional<Path> getProguardJarOverride() {
    Optional<String> pathString = delegate.getValue("tools", "proguard");
    if (pathString.isPresent()) {
      return delegate.checkPathExists(pathString.get(), "Overridden proguard path not found: ");
    }
    return Optional.absent();
  }

  /**
   * @return The upper heap size limit for Proguard if specified.
   */
  public String getProguardMaxHeapSize() {
    return delegate.getValue("tools", "proguard-max-heap-size").or("1024M");
  }

}

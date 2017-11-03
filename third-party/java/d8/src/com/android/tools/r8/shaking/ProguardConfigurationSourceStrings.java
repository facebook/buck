// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import joptsimple.internal.Strings;

public class ProguardConfigurationSourceStrings implements ProguardConfigurationSource {

  private final Path basePath;
  private final List<String> config;

  /**
   * Creates {@link ProguardConfigurationSource} with raw {@param config}, along with
   * {@param basePath}, which allows all other options that use a relative path to reach out
   * to desired paths appropriately.
   */
  public ProguardConfigurationSourceStrings(List<String> config, Path basePath) {
    this.basePath = basePath;
    this.config = config;
  }

  private ProguardConfigurationSourceStrings(List<String> config) {
    this(config, Paths.get("."));
  }

  @VisibleForTesting
  static ProguardConfigurationSourceStrings createConfigurationForTesting(
      List<String> config) {
    return new ProguardConfigurationSourceStrings(config);
  }

  @Override
  public String get() throws IOException {
    return Strings.join(config, System.lineSeparator());
  }

  @Override
  public Path getBaseDirectory() {
    return basePath;
  }

  @Override
  public String getName() {
    return "<no file>";
  }
}

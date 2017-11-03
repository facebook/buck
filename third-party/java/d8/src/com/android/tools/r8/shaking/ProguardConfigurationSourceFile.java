// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ProguardConfigurationSourceFile implements ProguardConfigurationSource {
  private final Path path;

  public ProguardConfigurationSourceFile(Path path) {
    this.path = path;
  }

  @Override
  public String get() throws IOException{
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  @Override
  public Path getBaseDirectory() {
    Path baseDirectory = path.getParent();
    if (baseDirectory == null) {
      // Path parent can be null only if it's root dir or if its a one element path relative to
      // current directory.
      baseDirectory = Paths.get(".");
    }
    return baseDirectory;
  }

  @Override
  public String getName() {
    return path.toString();
  }
}

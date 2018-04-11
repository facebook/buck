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

package com.facebook.buck.rules.modern;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Represents an output path of a Buildable. Can be converted to a Path with an OutputPathResolver.
 */
public class OutputPath implements AddsToRuleKey {
  @AddToRuleKey(stringify = true)
  private final Path path;

  public OutputPath(String name) {
    this(Paths.get(name));
  }

  public OutputPath(Path path) {
    this.path = path;
  }

  public OutputPath resolve(String subPath) {
    return new OutputPath(path.resolve(subPath));
  }

  public OutputPath resolve(Path subPath) {
    return new OutputPath(path.resolve(subPath));
  }

  Path getPath() {
    return path;
  }

  public static Internals internals() {
    return Internals.INSTANCE;
  }

  /**
   * Provides access to internal implementation details of OutputPaths. Using this should be
   * avoided.
   */
  public static class Internals {
    private static final Internals INSTANCE = new Internals();

    public Path getPath(OutputPath outputPath) {
      return outputPath.getPath();
    }
  }
}

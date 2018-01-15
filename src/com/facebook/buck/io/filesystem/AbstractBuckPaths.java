/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractBuckPaths {

  public static BuckPaths createDefaultBuckPaths(Path rootPath) {
    return BuckPaths.of(
        rootPath.getFileSystem().getPath(BuckConstant.getBuckOutputPath().toString()));
  }

  /** The relative path to the directory where Buck will generate its files. */
  @Value.Parameter
  public abstract Path getBuckOut();

  /**
   * The relative path to the directory where Buck will generate its files. This is used when
   * configuring the output directory to some used-defined value and is a stop-gap until we can
   * support configuring all output paths. However, for now, only certain paths below will use this
   * path.
   */
  @Value.Default
  public Path getConfiguredBuckOut() {
    return getBuckOut();
  }

  /** The version the buck output directory was created for */
  @Value.Derived
  public Path getCurrentVersionFile() {
    return getBuckOut().resolve(".currentversion");
  }

  @Value.Derived
  public Path getGenDir() {
    return getConfiguredBuckOut().resolve("gen");
  }

  @Value.Derived
  public Path getResDir() {
    return getConfiguredBuckOut().resolve("res");
  }

  @Value.Derived
  public Path getScratchDir() {
    return getConfiguredBuckOut().resolve("bin");
  }

  @Value.Derived
  public Path getAnnotationDir() {
    return getConfiguredBuckOut().resolve("annotation");
  }

  @Value.Derived
  public Path getLogDir() {
    return getBuckOut().resolve("log");
  }

  @Value.Derived
  public Path getTraceDir() {
    return getLogDir().resolve("traces");
  }

  @Value.Derived
  public Path getCacheDir() {
    return getBuckOut().resolve("cache");
  }

  @Value.Derived
  public Path getTmpDir() {
    return getBuckOut().resolve("tmp");
  }

  @Value.Derived
  public Path getXcodeDir() {
    return getBuckOut().resolve("xcode");
  }

  @Value.Derived
  public Path getTrashDir() {
    // We put a . at the front of the name so Spotlight doesn't try to index the contents on OS X.
    return getBuckOut().resolve(".trash");
  }

  @Value.Derived
  public Path getOfflineLogDir() {
    return getLogDir().resolve("offline");
  }

  @Value.Derived
  public Path getRemoteSandboxDir() {
    return getBuckOut().resolve("remote_sandbox");
  }

  @Value.Derived
  public Path getLastOutputDir() {
    return getConfiguredBuckOut().resolve("last");
  }

  @Value.Derived
  public Path getEmbeddedCellsBuckOutBaseDir() {
    return getBuckOut().resolve("cells");
  }
}

/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.filesystem;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.config.Config;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class BuckPaths {

  /**
   * Default value for {@link #shouldIncludeTargetConfigHash()} when it is not specified by user.
   */
  public static final boolean DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH = false;

  public static BuckPaths createDefaultBuckPaths(
      CanonicalCellName cellName, Path rootPath, boolean buckOutIncludeTargetConfigHash) {
    Path buckOut = rootPath.getFileSystem().getPath(BuckConstant.getBuckOutputPath().toString());
    return BuckPaths.of(cellName, buckOut, buckOut, buckOutIncludeTargetConfigHash);
  }

  /** Is hashed buck-out enabled? Must be queried using root cell buckconfig. */
  public static boolean getBuckOutIncludeTargetConfigHashFromRootCellConfig(Config config) {
    return config.getBooleanValue(
        "project",
        "buck_out_include_target_config_hash",
        BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH);
  }

  public abstract CanonicalCellName getCellName();

  /** The relative path to the directory where Buck will generate its files. */
  public abstract Path getBuckOut();

  @Value.Check
  protected void checkBuckOut() {
    Preconditions.checkArgument(!getBuckOut().isAbsolute());
  }

  /**
   * The relative path to the directory where Buck will generate its files. This is used when
   * configuring the output directory to some used-defined value and is a stop-gap until we can
   * support configuring all output paths. However, for now, only certain paths below will use this
   * path.
   */
  public abstract Path getConfiguredBuckOut();

  /** Whether to include the target configuration hash on buck-out. */
  public abstract boolean shouldIncludeTargetConfigHash();

  /** The version the buck output directory was created for */
  @Value.Derived
  public Path getCurrentVersionFile() {
    return getConfiguredBuckOut().resolve(".currentversion");
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
  public Path getJournalDir() {
    return getLogDir().resolve("journal");
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

  @Value.Derived
  public Path getProjectRootDir() {
    return getBuckOut().resolve("project_root");
  }

  public Path getSymlinkPathForDir(Path unconfiguredDirInBuckOut) {
    return getConfiguredBuckOut().resolve(getBuckOut().relativize(unconfiguredDirInBuckOut));
  }

  public static BuckPaths of(
      CanonicalCellName cellName,
      Path buckOut,
      Path configuredBuckOut,
      boolean shouldIncludeTargetConfigHash) {
    return ImmutableBuckPaths.of(
        cellName, buckOut, configuredBuckOut, shouldIncludeTargetConfigHash);
  }

  public BuckPaths withConfiguredBuckOut(Path configuredBuckOut) {
    if (getConfiguredBuckOut().equals(configuredBuckOut)) {
      return this;
    }
    return of(getCellName(), getBuckOut(), configuredBuckOut, shouldIncludeTargetConfigHash());
  }

  public BuckPaths withBuckOut(Path buckOut) {
    if (getBuckOut().equals(buckOut)) {
      return this;
    }
    return of(getCellName(), buckOut, getConfiguredBuckOut(), shouldIncludeTargetConfigHash());
  }
}

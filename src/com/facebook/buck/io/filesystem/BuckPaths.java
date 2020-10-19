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
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.config.Config;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Common Buck paths + Config and cell related data. */
@BuckStyleValue
public abstract class BuckPaths extends BaseBuckPaths {

  /**
   * Default value for {@link #shouldIncludeTargetConfigHash()} when it is not specified by user.
   */
  public static final boolean DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH = false;

  public abstract CanonicalCellName getCellName();

  @Override
  public abstract RelPath getBuckOut();

  @Override
  public abstract RelPath getConfiguredBuckOut();

  @Override
  public abstract boolean shouldIncludeTargetConfigHash();

  public static BuckPaths createDefaultBuckPaths(
      CanonicalCellName cellName, Path rootPath, boolean buckOutIncludeTargetConfigHash) {
    RelPath buckOut =
        RelPath.of(rootPath.getFileSystem().getPath(BuckConstant.getBuckOutputPath().toString()));
    return of(cellName, buckOut, buckOut, buckOutIncludeTargetConfigHash);
  }

  /** Is hashed buck-out enabled? Must be queried using root cell buckconfig. */
  public static boolean getBuckOutIncludeTargetConfigHashFromRootCellConfig(Config config) {
    return config.getBooleanValue(
        "project",
        "buck_out_include_target_config_hash",
        DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH);
  }

  public static BuckPaths of(
      CanonicalCellName cellName,
      RelPath buckOut,
      RelPath configuredBuckOut,
      boolean shouldIncludeTargetConfigHash) {
    return ImmutableBuckPaths.ofImpl(
        cellName, buckOut, configuredBuckOut, shouldIncludeTargetConfigHash);
  }

  /** Replace {@link #getConfiguredBuckOut()} field. */
  public BuckPaths withConfiguredBuckOut(RelPath configuredBuckOut) {
    if (getConfiguredBuckOut().equals(configuredBuckOut)) {
      return this;
    }
    return of(getCellName(), getBuckOut(), configuredBuckOut, shouldIncludeTargetConfigHash());
  }

  /** Replace {@link #getBuckOut()} field. */
  public BuckPaths withBuckOut(RelPath buckOut) {
    if (getBuckOut().equals(buckOut)) {
      return this;
    }
    return of(getCellName(), buckOut, getConfiguredBuckOut(), shouldIncludeTargetConfigHash());
  }

  @Value.Derived
  public Path getEmbeddedCellsBuckOutBaseDir() {
    return getBuckOut().resolve("cells");
  }
}

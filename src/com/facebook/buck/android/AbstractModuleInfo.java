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

package com.facebook.buck.android;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@BuckStyleImmutable
@Value.Immutable(copy = false)
/** This class contains files needed to build a apk module within a app bundle. */
public abstract class AbstractModuleInfo {

  @Value.Parameter
  public abstract String getModuleName();

  @Value.Parameter
  @Nullable
  public abstract Path getResourceApk();

  @Value.Parameter
  public abstract ImmutableSet<Path> getDexFile();

  @Value.Parameter
  public abstract ImmutableMap<Path, String> getAssetDirectories();

  @Value.Parameter
  public abstract ImmutableSet<Path> getNativeLibraryDirectories();

  @Value.Parameter
  public abstract ImmutableSet<Path> getZipFiles();

  @Value.Parameter
  public abstract ImmutableSet<Path> getJarFilesThatMayContainResources();

  public boolean isBaseModule() {
    return "base".equals(getModuleName());
  }
}

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

package com.facebook.buck.jvm.core;

import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import javax.annotation.Nullable;

/** The default implementation of {@code BaseJavaAbiInfo}. */
public class DefaultBaseJavaAbiInfo implements BaseJavaAbiInfo {

  private final String buildTarget;

  @Nullable private volatile ImmutableSet<Path> contentPaths;

  public DefaultBaseJavaAbiInfo(String buildTarget) {
    this.buildTarget = Objects.requireNonNull(buildTarget);
  }

  @Override
  public String getUnflavoredBuildTargetName() {
    return buildTarget;
  }

  @Override
  public boolean jarContains(String path) {
    return Objects.requireNonNull(contentPaths, "Must call setContentPaths() first.")
        .contains(Paths.get(path));
  }

  public void setContentPaths(@Nullable ImmutableSet<Path> contentPaths) {
    this.contentPaths = contentPaths;
  }

  @Nullable
  public ImmutableSet<Path> getContentPaths() {
    return contentPaths;
  }
}

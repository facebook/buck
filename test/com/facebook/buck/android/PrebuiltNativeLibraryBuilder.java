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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class PrebuiltNativeLibraryBuilder
    extends AbstractNodeBuilder<
        PrebuiltNativeLibraryDescriptionArg.Builder,
        PrebuiltNativeLibraryDescriptionArg,
        PrebuiltNativeLibraryDescription,
        PrebuiltNativeLibrary> {

  private PrebuiltNativeLibraryBuilder(BuildTarget target) {
    this(target, new FakeProjectFilesystem());
  }

  private PrebuiltNativeLibraryBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    super(new PrebuiltNativeLibraryDescription(), target, filesystem);
  }

  public static PrebuiltNativeLibraryBuilder newBuilder(BuildTarget buildTarget) {
    return new PrebuiltNativeLibraryBuilder(buildTarget);
  }

  public static PrebuiltNativeLibraryBuilder newBuilder(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return new PrebuiltNativeLibraryBuilder(buildTarget, filesystem);
  }

  public PrebuiltNativeLibraryBuilder setIsAsset(boolean isAsset) {
    getArgForPopulating().setIsAsset(isAsset);
    return this;
  }

  public PrebuiltNativeLibraryBuilder setNativeLibs(@Nullable Path nativeLibs) {
    getArgForPopulating().setNativeLibs(nativeLibs);
    return this;
  }
}

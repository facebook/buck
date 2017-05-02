/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.AbstractNodeBuilderWithMutableArg;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public class AndroidResourceBuilder
    extends AbstractNodeBuilderWithMutableArg<
        AndroidResourceDescription.Arg, AndroidResourceDescription, AndroidResource> {

  private AndroidResourceBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    super(new AndroidResourceDescription(false), target, filesystem);
  }

  public static AndroidResourceBuilder createBuilder(BuildTarget target) {
    return new AndroidResourceBuilder(target, new FakeProjectFilesystem());
  }

  public static AndroidResourceBuilder createBuilder(
      BuildTarget target, ProjectFilesystem filesystem) {
    return new AndroidResourceBuilder(target, filesystem);
  }

  public AndroidResourceBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }

  public AndroidResourceBuilder setRes(SourcePath res) {
    arg.res = Optional.of(Either.ofLeft(res));
    return this;
  }

  public AndroidResourceBuilder setRes(Path res) {
    return setRes(new PathSourcePath(new FakeProjectFilesystem(), res));
  }

  public AndroidResourceBuilder setAssets(SourcePath assets) {
    arg.assets = Optional.of(Either.ofLeft(assets));
    return this;
  }

  public AndroidResourceBuilder setAssets(ImmutableSortedMap<String, SourcePath> assets) {
    arg.assets = Optional.of(Either.ofRight(assets));
    return this;
  }

  public AndroidResourceBuilder setRDotJavaPackage(String rDotJavaPackage) {
    arg.rDotJavaPackage = Optional.of(rDotJavaPackage);
    return this;
  }

  public AndroidResourceBuilder setManifest(SourcePath manifest) {
    arg.manifest = Optional.of(manifest);
    return this;
  }
}

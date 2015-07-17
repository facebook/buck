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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class AndroidResourceBuilder extends AbstractNodeBuilder<AndroidResourceDescription.Arg> {

  private AndroidResourceBuilder(BuildTarget target) {
    super(new AndroidResourceDescription(), target);
  }

  public static AndroidResourceBuilder createBuilder(BuildTarget target) {
    return new AndroidResourceBuilder(target);
  }

  public AndroidResourceBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

  public AndroidResourceBuilder setRes(SourcePath res) {
    arg.res = Optional.of(res);
    return this;
  }

  public AndroidResourceBuilder setRes(Path res) {
    return setRes(new PathSourcePath(new FakeProjectFilesystem(), res));
  }

  public AndroidResourceBuilder setRDotJavaPackage(String rDotJavaPackage) {
    arg.rDotJavaPackage = Optional.of(rDotJavaPackage);
    return this;
  }

  public AndroidResourceBuilder setAssets(Path assets) {
    arg.assets = Optional.of(assets);
    return this;
  }

  public AndroidResourceBuilder setManifest(SourcePath manifest) {
    arg.manifest = Optional.of(manifest);
    return this;
  }

  public AndroidResourceBuilder setHasWhitelistedStrings(boolean hasWhitelistedStrings) {
    arg.hasWhitelistedStrings = Optional.of(hasWhitelistedStrings);
    return this;
  }

}

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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.AbstractNodeBuilderWithMutableArg;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class JsBundleBuilder
    extends AbstractNodeBuilderWithMutableArg<
        JsBundleDescription.Arg, JsBundleDescription, JsBundle> {
  private static final JsBundleDescription bundleDescription = new JsBundleDescription();

  JsBundleBuilder(
      BuildTarget target,
      BuildTarget worker,
      Either<ImmutableSet<String>, String> entry,
      ProjectFilesystem filesystem) {
    super(bundleDescription, target, filesystem);
    arg.entry = entry;
    arg.bundleName = Optional.empty();
    arg.worker = worker;
    arg.rDotJavaPackage = Optional.of("com.example");
  }

  JsBundleBuilder setBundleName(String bundleName) {
    arg.bundleName = Optional.of(bundleName);
    return this;
  }

  JsBundleBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = deps;
    return this;
  }
}

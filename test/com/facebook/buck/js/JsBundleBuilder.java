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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class JsBundleBuilder
    extends AbstractNodeBuilder<
        JsBundleDescriptionArg.Builder, JsBundleDescriptionArg, JsBundleDescription, JsBundle> {
  private static final JsBundleDescription bundleDescription =
      new JsBundleDescription(new ToolchainProviderBuilder().build());

  JsBundleBuilder(
      BuildTarget target,
      BuildTarget worker,
      Either<ImmutableSet<String>, String> entry,
      ProjectFilesystem filesystem) {
    super(bundleDescription, target, filesystem);
    getArgForPopulating().setEntry(entry);
    getArgForPopulating().setWorker(worker);
    getArgForPopulating().setAndroidPackage("com.example");
  }

  JsBundleBuilder setBundleName(String bundleName) {
    getArgForPopulating().setBundleName(bundleName);
    return this;
  }

  JsBundleBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  JsBundleBuilder setBundleNameForFlavor(Iterable<Pair<Flavor, String>> bundleNameForFlavor) {
    getArgForPopulating().setBundleNameForFlavor(bundleNameForFlavor);
    return this;
  }

  JsBundleBuilder setExtraJson(String format, Macro... macros) {
    getArgForPopulating().setExtraJson(StringWithMacrosUtils.format(format, macros));
    return this;
  }
}

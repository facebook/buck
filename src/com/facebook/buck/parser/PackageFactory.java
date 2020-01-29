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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.parser.VisibilityPatterns;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Generic factory to create {@link Package} */
public class PackageFactory {

  /** Prevent initialization */
  private PackageFactory() {}

  /** Create a {@link Package} from the {@code rawPackage} */
  public static Package create(
      Cell cell, Path packageFile, PackageMetadata rawPackage, Optional<Package> parentPackage) {

    String visibilityDefinerDescription =
        String.format("the package() at %s", packageFile.toString());

    ImmutableSet.Builder<VisibilityPattern> visibilityBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> withinViewBuilder = ImmutableSet.builder();

    parentPackage.ifPresent(
        pkg -> {
          visibilityBuilder.addAll(pkg.getVisibilityPatterns());
          withinViewBuilder.addAll(pkg.getWithinViewPatterns());
        });

    visibilityBuilder.addAll(
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            VisibilityAttributes.VISIBILITY,
            rawPackage.getVisibility(),
            packageFile,
            () -> visibilityDefinerDescription));

    withinViewBuilder.addAll(
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            VisibilityAttributes.WITHIN_VIEW,
            rawPackage.getWithinView(),
            packageFile,
            () -> visibilityDefinerDescription));

    return Package.of(visibilityBuilder.build(), withinViewBuilder.build());
  }
}

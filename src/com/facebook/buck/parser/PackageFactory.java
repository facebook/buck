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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.visibility.VisibilityDefiningPath;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.parser.VisibilityPatterns;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/** Generic factory to create {@link Package} */
public class PackageFactory {

  /** Prevent initialization */
  private PackageFactory() {}

  /** Create a {@link Package} from the {@code rawPackage} */
  public static Package create(
      Cell cell,
      ForwardRelPath packageFile,
      PackageMetadata rawPackage,
      Optional<Package> parentPackage) {

    VisibilityDefiningPath definingPath = VisibilityDefiningPath.of(packageFile, false);

    String visibilityDefinerDescription =
        String.format("the package() at %s", definingPath.getPath());

    ImmutableSet.Builder<VisibilityPattern> visibilityBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<VisibilityPattern> withinViewBuilder = ImmutableSet.builder();

    parentPackage.ifPresent(
        pkg -> {
          if (rawPackage.getInherit()) {
            visibilityBuilder.addAll(pkg.getVisibilityPatterns());
            withinViewBuilder.addAll(pkg.getWithinViewPatterns());
          }
        });

    visibilityBuilder.addAll(
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            CommonParamNames.VISIBILITY.getSnakeCase(),
            rawPackage.getVisibility(),
            definingPath,
            () -> visibilityDefinerDescription));

    withinViewBuilder.addAll(
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            CommonParamNames.WITHIN_VIEW.getSnakeCase(),
            rawPackage.getWithinView(),
            definingPath,
            () -> visibilityDefinerDescription));

    return Package.of(
        rawPackage.getInherit(), visibilityBuilder.build(), withinViewBuilder.build());
  }
}

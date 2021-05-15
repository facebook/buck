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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.parser.api.PackageMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.Optional;
import org.junit.Test;

public class PackageFactoryTest {

  private final Cells cell = new TestCellBuilder().build();

  private Package createGenericPackage() {
    ForwardRelPath packageFile = ForwardRelPath.of("foo/PACKAGE");

    PackageMetadata rawPackage =
        PackageMetadata.of(false, ImmutableList.of("//a/..."), ImmutableList.of("//b/..."));

    return PackageFactory.create(cell.getRootCell(), packageFile, rawPackage, Optional.empty());
  }

  @Test
  public void createPackageWorks() {
    Package pkg = createGenericPackage();

    assertEquals(
        "//a/...", Iterables.getFirst(pkg.getVisibilityPatterns(), null).getRepresentation());
    assertEquals(
        "//b/...", Iterables.getFirst(pkg.getWithinViewPatterns(), null).getRepresentation());
  }

  @Test
  public void createWithParentNoInherit() {
    Package parentPkg = createGenericPackage();

    ForwardRelPath packageFile = ForwardRelPath.of("foo/bar/PACKAGE");

    PackageMetadata rawPackage =
        PackageMetadata.of(false, ImmutableList.of("//c/..."), ImmutableList.of("//d/..."));

    Package pkg =
        PackageFactory.create(cell.getRootCell(), packageFile, rawPackage, Optional.of(parentPkg));

    assertEquals(1, pkg.getVisibilityPatterns().size());
    assertEquals("//c/...", pkg.getVisibilityPatterns().asList().get(0).getRepresentation());

    assertEquals(1, pkg.getWithinViewPatterns().size());
    assertEquals("//d/...", pkg.getWithinViewPatterns().asList().get(0).getRepresentation());
  }

  @Test
  public void createWithParentInherit() {
    Package parentPkg = createGenericPackage();

    ForwardRelPath packageFile = ForwardRelPath.of("foo/bar/PACKAGE");

    PackageMetadata rawPackage =
        PackageMetadata.of(true, ImmutableList.of("//c/..."), ImmutableList.of("//d/..."));

    Package pkg =
        PackageFactory.create(cell.getRootCell(), packageFile, rawPackage, Optional.of(parentPkg));

    assertEquals(2, pkg.getVisibilityPatterns().size());
    assertEquals("//a/...", pkg.getVisibilityPatterns().asList().get(0).getRepresentation());
    assertEquals("//c/...", pkg.getVisibilityPatterns().asList().get(1).getRepresentation());

    assertEquals(2, pkg.getWithinViewPatterns().size());
    assertEquals("//b/...", pkg.getWithinViewPatterns().asList().get(0).getRepresentation());
    assertEquals("//d/...", pkg.getWithinViewPatterns().asList().get(1).getRepresentation());
  }
}

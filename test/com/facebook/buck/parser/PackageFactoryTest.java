/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.targetgraph.Package;
import com.facebook.buck.parser.api.ImmutablePackageMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PackageFactoryTest {

  @Test
  public void testCreate() {
    Cell cell = new TestCellBuilder().build();
    Path buildFile = Paths.get("foo/BUCK");

    ImmutablePackageMetadata rawPackage =
        new ImmutablePackageMetadata(ImmutableList.of("//a/..."), ImmutableList.of("//b/..."));

    Package pkg = PackageFactory.create(cell, buildFile, rawPackage);

    assertEquals(
        "//a/...", Iterables.getFirst(pkg.getVisibilityPatterns(), null).getRepresentation());
    assertEquals(
        "//b/...", Iterables.getFirst(pkg.getWithinViewPatterns(), null).getRepresentation());
  }
}

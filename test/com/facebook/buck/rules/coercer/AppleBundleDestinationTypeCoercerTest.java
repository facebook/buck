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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleBundleDestinationTypeCoercerTest {

  @Test
  public void coercingSingleString()
      throws NoSuchFieldException, CoerceFailedException {
    BuildTargetParser buildTargetParser = new BuildTargetParser();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path basePath = Paths.get("base");
    TypeCoercer<String> stringTypeCoercer = new IdentityTypeCoercer<>(String.class);
    TypeCoercer<AppleBundleDestination> destinationTypeCoercer =
        new AppleBundleDestinationTypeCoercer(stringTypeCoercer);


    AppleBundleDestination.SubfolderSpec subfolderSpec =
        AppleBundleDestination.SubfolderSpec.EXECUTABLES;
    AppleBundleDestination destination = destinationTypeCoercer.coerce(
        buildTargetParser,
        filesystem,
        basePath,
        subfolderSpec.toString());

    assertEquals(
        destination,
        ImmutableAppleBundleDestination.of(subfolderSpec, Optional.<String>absent()));
  }

  @Test
  public void coercingTwoStrings()
      throws NoSuchFieldException, CoerceFailedException {
    BuildTargetParser buildTargetParser = new BuildTargetParser();
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path basePath = Paths.get("base");
    TypeCoercer<String> stringTypeCoercer = new IdentityTypeCoercer<>(String.class);
    TypeCoercer<AppleBundleDestination> destinationTypeCoercer =
        new AppleBundleDestinationTypeCoercer(stringTypeCoercer);


    AppleBundleDestination.SubfolderSpec subfolderSpec =
        AppleBundleDestination.SubfolderSpec.PRODUCTS;
    String subpath = "Codecs";
    AppleBundleDestination destination = destinationTypeCoercer.coerce(
        buildTargetParser,
        filesystem,
        basePath,
        ImmutableList.of(subfolderSpec.toString(), subpath));

    assertEquals(
        destination,
        ImmutableAppleBundleDestination.of(subfolderSpec, Optional.of(subpath)));
  }
}

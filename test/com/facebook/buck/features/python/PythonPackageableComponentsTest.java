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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertFalse;

import java.util.Optional;
import org.junit.Test;

public class PythonPackageableComponentsTest {

  @Test
  public void testMergeZipSafe() {
    PythonPackageComponents.Builder builder = new PythonPackageComponents.Builder();
    builder.addZipSafe(Optional.of(true));
    builder.addZipSafe(Optional.of(false));
    assertFalse(builder.build().isZipSafe().get());
  }
}

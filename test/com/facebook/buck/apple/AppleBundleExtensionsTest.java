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

package com.facebook.buck.apple;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * Unit tests for {@link AppleBundleExtensions}.
 */
public class AppleBundleExtensionsTest {
  @Test
  public void octestIsValid() {
    assertTrue(AppleBundleExtensions.pathsHaveValidTestExtensions(Paths.get("foo.octest")));
  }

  @Test
  public void xctestIsValid() {
    assertTrue(AppleBundleExtensions.pathsHaveValidTestExtensions(Paths.get("foo.xctest")));
  }

  @Test
  public void bundleIsNotValid() {
    assertFalse(AppleBundleExtensions.pathsHaveValidTestExtensions(Paths.get("foo.bundle")));
  }

  @Test
  public void missingExtensionIsNotValid() {
    assertFalse(AppleBundleExtensions.pathsHaveValidTestExtensions(Paths.get("foo")));
  }

  @Test
  public void bothOctestAndXctestAreValid() {
    assertTrue(
        AppleBundleExtensions.pathsHaveValidTestExtensions(
            Paths.get("foo.xctest"),
            Paths.get("foo.octest")));
  }

  @Test
  public void oneBadExtensionIsNotValid() {
    assertFalse(
        AppleBundleExtensions.pathsHaveValidTestExtensions(
            Paths.get("foo.xctest"),
            Paths.get("foo.octest"),
            Paths.get("foo")));
  }
}

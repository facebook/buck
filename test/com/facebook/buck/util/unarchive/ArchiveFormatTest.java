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

package com.facebook.buck.util.unarchive;

import org.junit.Assert;
import org.junit.Test;

public class ArchiveFormatTest {

  @Test
  public void returnsCorrectCompressionFormatsBasedOnFilename() {
    Assert.assertEquals(
        ArchiveFormat.ZIP, ArchiveFormat.getFormatFromFilename("foo.bar.zip").get());
  }

  @Test
  public void returnsEmptyWhenNoCompressionFormatCouldBeFoundBasedOnFilename() {
    Assert.assertFalse(ArchiveFormat.getFormatFromFilename("foo.bar").isPresent());
  }

  @Test
  public void returnsCorrectCompressionFormatsBasedOnShortName() {
    Assert.assertEquals(ArchiveFormat.ZIP, ArchiveFormat.getFormatFromShortName("zip").get());
  }

  @Test
  public void returnsEmptyWhenNoCompressionFormatCouldBeFoundBasedOnShortName() {
    Assert.assertFalse(ArchiveFormat.getFormatFromShortName("foo").isPresent());
  }
}

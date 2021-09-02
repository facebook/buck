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

package com.facebook.buck.util.memory;

import org.junit.Assert;
import org.junit.Test;

public class LinuxTimeParserTest {
  @Test
  public void parsingSmokeTest() {
    String contents = "rss 1912\navg 0\npagefaults 0";
    LinuxTimeParser parser = new LinuxTimeParser();
    ResourceUsage rusage = parser.parse(contents);
    Assert.assertEquals(1912, rusage.getMaxResidentSetSize());
  }
}

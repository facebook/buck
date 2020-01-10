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

package com.facebook.buck.sandbox.darwin;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.sandbox.SandboxProperties;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public class DarwinSandboxTest {

  @Test
  public void testCreatingSandboxProfile() throws IOException {
    ImmutableSet<String> allowedToReadPaths =
        ImmutableSet.of("allowed_to_read_1", "allowed_to_read_2");
    ImmutableSet<String> allowedToReadMetadataPaths =
        ImmutableSet.of("allowed_to_read_metadata_1", "allowed_to_read_metadata_2");
    ImmutableSet<String> allowedToWritePaths =
        ImmutableSet.of("allowed_to_write_1", "allowed_to_write_1");

    SandboxProperties sandboxProperties =
        SandboxProperties.builder()
            .addAllAllowedToReadPaths(allowedToReadPaths)
            .addAllAllowedToReadMetadataPaths(allowedToReadMetadataPaths)
            .addAllAllowedToWritePaths(allowedToWritePaths)
            .build();

    List<String> expected =
        Resources.readLines(
            Resources.getResource(getClass(), "sandbox_profile.in"), Charsets.UTF_8);

    List<String> actual =
        Lists.newArrayList(DarwinSandbox.generateSandboxProfileContent(sandboxProperties));

    assertEquals(expected, actual);
  }
}

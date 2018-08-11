/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.common;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class RecordArtifactVerifierTest {

  @Test
  public void testRecordsPaths() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    ImmutableList<Path> allowedPaths =
        ImmutableList.of(Paths.get("first"), Paths.get("second/path"));
    new RecordArtifactVerifier(allowedPaths, builder::add);
    assertEquals(builder.build(), allowedPaths);
  }

  @Test
  public void testGoodPathsAllowed() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    ImmutableList<Path> allowedPaths =
        ImmutableList.of(Paths.get("first"), Paths.get("second/path"));
    RecordArtifactVerifier verifier = new RecordArtifactVerifier(allowedPaths, builder::add);

    verifier.recordArtifact(Paths.get("first"));
    verifier.recordArtifact(Paths.get("second/path"));
    verifier.recordArtifact(Paths.get("first/child1"));
    verifier.recordArtifact(Paths.get("second/path/grand/child1"));
    verifier.recordArtifact(Paths.get("second/path/grand/child2"));
  }

  @Test(expected = RuntimeException.class)
  public void testBadPathDisallowed() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    ImmutableList<Path> allowedPaths =
        ImmutableList.of(Paths.get("first"), Paths.get("second/path"));
    RecordArtifactVerifier verifier = new RecordArtifactVerifier(allowedPaths, builder::add);

    verifier.recordArtifact(Paths.get("other"));
  }

  @Test(expected = RuntimeException.class)
  public void testPathEscapingViaRelativityDisallowed() {
    ImmutableList.Builder<Path> builder = ImmutableList.builder();
    ImmutableList<Path> allowedPaths =
        ImmutableList.of(Paths.get("first"), Paths.get("second/path"));
    RecordArtifactVerifier verifier = new RecordArtifactVerifier(allowedPaths, builder::add);

    verifier.recordArtifact(Paths.get("first/../evil"));
  }
}

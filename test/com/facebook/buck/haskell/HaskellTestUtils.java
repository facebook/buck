/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class HaskellTestUtils {

  private HaskellTestUtils() {}

  /** Assume that we can find a haskell compiler on the system. */
  static HaskellVersion assumeSystemCompiler() throws IOException, InterruptedException {
    HaskellBuckConfig fakeConfig =
        new HaskellBuckConfig(FakeBuckConfig.builder().build(), new ExecutableFinder());
    Optional<Path> compilerOptional = fakeConfig.getSystemCompiler();
    assumeTrue(compilerOptional.isPresent());

    // Find the major version of the haskell compiler.
    ImmutableList<String> cmd = ImmutableList.of(compilerOptional.get().toString(), "--version");
    Process process = Runtime.getRuntime().exec(cmd.toArray(new String[cmd.size()]));
    String output = new String(ByteStreams.toByteArray(process.getInputStream()), Charsets.UTF_8);
    Pattern versionPattern = Pattern.compile(".*version ([0-9]+)(?:[.][0-9]+(?:[.][0-9]+)?)?");
    Matcher matcher = versionPattern.matcher(output.trim());
    assertTrue(
        String.format(
            "Cannot match version from `ghc --version` output (using %s): %s",
            versionPattern, output),
        matcher.matches());
    return HaskellVersion.of(Integer.valueOf(matcher.group(1)));
  }

  static String formatHaskellConfig(HaskellVersion version) {
    return String.format("[haskell]\ncompiler_major_version = %s", version.getMajorVersion());
  }
}

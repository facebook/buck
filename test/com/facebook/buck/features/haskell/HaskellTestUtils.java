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

package com.facebook.buck.features.haskell;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ConstantToolProvider;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class HaskellTestUtils {

  public static final HaskellPlatform DEFAULT_PLATFORM =
      HaskellPlatform.builder()
          .setCompiler(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setLinker(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setPackager(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setHaddock(new ConstantToolProvider(new CommandTool.Builder().build()))
          .setHaskellVersion(HaskellVersion.of(8))
          .setShouldCacheLinks(true)
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .setGhciScriptTemplate(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciIservScriptTemplate(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciBinutils(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciGhc(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciIServ(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciIServProf(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciLib(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciCc(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciCxx(
              () -> {
                throw new UnsupportedOperationException();
              })
          .setGhciCpp(
              () -> {
                throw new UnsupportedOperationException();
              })
          .build();

  public static final FlavorDomain<HaskellPlatform> DEFAULT_PLATFORMS =
      FlavorDomain.of("Haskell Platform", DEFAULT_PLATFORM);

  private HaskellTestUtils() {}

  /** Assume that we can find a haskell compiler on the system. */
  static HaskellVersion assumeSystemCompiler() throws IOException {
    ExecutableFinder executableFinder = new ExecutableFinder();
    Optional<Path> compilerOptional =
        executableFinder.getOptionalExecutable(
            Paths.get("ghc"), ImmutableMap.copyOf(System.getenv()));
    assumeTrue(compilerOptional.isPresent());

    // Find the major version of the haskell compiler.
    ImmutableList<String> cmd = ImmutableList.of(compilerOptional.get().toString(), "--version");
    Process process = Runtime.getRuntime().exec(cmd.toArray(new String[cmd.size()]));
    String output = new String(ByteStreams.toByteArray(process.getInputStream()), Charsets.UTF_8);
    Pattern versionPattern = Pattern.compile(".*version ([0-9]+).*");
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

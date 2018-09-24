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

package com.facebook.buck.skylark.function;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.facebook.buck.skylark.parser.BuckGlobals;
import com.facebook.buck.skylark.parser.RuleFunctionFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.syntax.EvalException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class HostInfoTest {

  private void validateSkylarkStruct(SkylarkInfo struct, String topLevel, String trueKey)
      throws EvalException {
    // Assert that all keys are false except the one specified by {@code trueKey}

    SkylarkInfo topLevelStruct = struct.getValue(topLevel, SkylarkInfo.class);
    for (String key : topLevelStruct.getFieldNames()) {
      if (key.equals(trueKey)) {
        continue;
      }
      Assert.assertFalse(
          String.format("%s.%s must be false, but was true", topLevel, key),
          topLevelStruct.getValue(key, Boolean.class));
    }
    Assert.assertTrue(
        String.format("%s.%s must be true, but was false", topLevel, trueKey),
        topLevelStruct.getValue(trueKey, Boolean.class));
  }

  @Test
  public void returnsCorrectArchitectures() throws EvalException {
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.AARCH64),
        "arch",
        "is_aarch64");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.ARM),
        "arch",
        "is_arm");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.ARMEB),
        "arch",
        "is_armeb");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.I386),
        "arch",
        "is_i386");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.MIPS),
        "arch",
        "is_mips");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.MIPS64),
        "arch",
        "is_mips64");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.MIPSEL),
        "arch",
        "is_mipsel");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.MIPSEL64),
        "arch",
        "is_mipsel64");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.POWERPC),
        "arch",
        "is_powerpc");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.PPC64),
        "arch",
        "is_ppc64");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.UNKNOWN),
        "arch",
        "is_unknown");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.X86_64),
        "arch",
        "is_x86_64");
  }

  @Test
  public void returnsCorrectOs() throws EvalException {
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.WINDOWS, () -> Architecture.UNKNOWN),
        "os",
        "is_windows");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.MACOS, () -> Architecture.UNKNOWN),
        "os",
        "is_macos");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.LINUX, () -> Architecture.UNKNOWN),
        "os",
        "is_linux");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.FREEBSD, () -> Architecture.UNKNOWN),
        "os",
        "is_freebsd");
    validateSkylarkStruct(
        HostInfo.createHostInfoStruct(() -> Platform.UNKNOWN, () -> Architecture.UNKNOWN),
        "os",
        "is_unknown");
  }

  @Test
  public void isUseableInBuildFile() throws EvalException, InterruptedException, IOException {
    String expectedOutput = "";
    String macroFile = "";
    SkylarkInfo realHostInfo =
        HostInfo.createHostInfoStruct(Platform::detect, Architecture::detect);

    macroFile =
        "def printer():\n"
            + "    print(\"os.is_linux: {}\".format(native.host_info().os.is_linux))\n"
            + "    print(\"os.is_macos: {}\".format(native.host_info().os.is_macos))\n"
            + "    print(\"os.is_windows: {}\".format(native.host_info().os.is_windows))\n"
            + "    print(\"os.is_freebsd: {}\".format(native.host_info().os.is_freebsd))\n"
            + "    print(\"os.is_unknown: {}\".format(native.host_info().os.is_unknown))\n"
            + "    print(\"arch.is_aarch64: {}\".format(native.host_info().arch.is_aarch64))\n"
            + "    print(\"arch.is_arm: {}\".format(native.host_info().arch.is_arm))\n"
            + "    print(\"arch.is_armeb: {}\".format(native.host_info().arch.is_armeb))\n"
            + "    print(\"arch.is_i386: {}\".format(native.host_info().arch.is_i386))\n"
            + "    print(\"arch.is_mips: {}\".format(native.host_info().arch.is_mips))\n"
            + "    print(\"arch.is_mips64: {}\".format(native.host_info().arch.is_mips64))\n"
            + "    print(\"arch.is_mipsel: {}\".format(native.host_info().arch.is_mipsel))\n"
            + "    print(\"arch.is_mipsel64: {}\".format(native.host_info().arch.is_mipsel64))\n"
            + "    print(\"arch.is_powerpc: {}\".format(native.host_info().arch.is_powerpc))\n"
            + "    print(\"arch.is_ppc64: {}\".format(native.host_info().arch.is_ppc64))\n"
            + "    print(\"arch.is_unknown: {}\".format(native.host_info().arch.is_unknown))\n"
            + "    print(\"arch.is_x86_64: {}\".format(native.host_info().arch.is_x86_64))\n";

    expectedOutput =
        "os.is_linux: False\n"
            + "os.is_macos: False\n"
            + "os.is_windows: False\n"
            + "os.is_freebsd: False\n"
            + "os.is_unknown: False\n"
            + "arch.is_aarch64: False\n"
            + "arch.is_arm: False\n"
            + "arch.is_armeb: False\n"
            + "arch.is_i386: False\n"
            + "arch.is_mips: False\n"
            + "arch.is_mips64: False\n"
            + "arch.is_mipsel: False\n"
            + "arch.is_mipsel64: False\n"
            + "arch.is_powerpc: False\n"
            + "arch.is_ppc64: False\n"
            + "arch.is_unknown: False\n"
            + "arch.is_x86_64: False\n";
    // Make sure we set the current system's os/arch to True
    SkylarkInfo realHostOs = realHostInfo.getValue("os", SkylarkInfo.class);
    SkylarkInfo realHostArch = realHostInfo.getValue("arch", SkylarkInfo.class);
    String trueOsKey =
        realHostOs
            .getFieldNames()
            .stream()
            .filter(
                k -> {
                  try {
                    return realHostOs.getValue(k, Boolean.class);
                  } catch (EvalException e) {
                    throw new RuntimeException(e);
                  }
                })
            .findFirst()
            .get();

    String trueArchKey =
        realHostArch
            .getFieldNames()
            .stream()
            .filter(
                k -> {
                  try {
                    return realHostArch.getValue(k, Boolean.class);
                  } catch (EvalException e) {
                    throw new RuntimeException(e);
                  }
                })
            .findFirst()
            .get();
    expectedOutput =
        expectedOutput
            .replace("os." + trueOsKey + ": False", "os." + trueOsKey + ": True")
            .replace("arch." + trueArchKey + ": False", "arch." + trueArchKey + ": True");

    EventCollector eventHandler = new EventCollector(EnumSet.of(EventKind.DEBUG));
    String buildFile = "load(\"//:file.bzl\", \"printer\")\n";
    buildFile += "printer()\n";

    evaluateProject(macroFile, buildFile, eventHandler);

    Assert.assertEquals(
        expectedOutput.trim(),
        Streams.stream(eventHandler.iterator())
            .map(Event::getMessage)
            .collect(Collectors.joining("\n")));
  }

  private void evaluateProject(String macroFile, String buildFile, EventHandler eventHandler)
      throws IOException, InterruptedException {
    ProjectFilesystem fs = FakeProjectFilesystem.createRealTempFilesystem();
    Cell cell = new TestCellBuilder().setFilesystem(fs).build();
    Files.write(fs.resolve("BUCK"), buildFile.getBytes(Charsets.UTF_8));
    Files.write(fs.resolve("file.bzl"), macroFile.getBytes(Charsets.UTF_8));

    SkylarkProjectBuildFileParser parser = createParser(cell.getFilesystem(), eventHandler);
    parser.getBuildFileManifest(fs.resolve("BUCK"));
  }

  private SkylarkProjectBuildFileParser createParser(
      ProjectFilesystem filesystem, EventHandler eventHandler) {
    ProjectBuildFileParserOptions options =
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(filesystem.getRootPath())
            .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
            .setIgnorePaths(ImmutableSet.of())
            .setBuildFileName("BUCK")
            .setBuildFileImportWhitelist(ImmutableList.of())
            .setPythonInterpreter("skylark")
            .build();
    RuleFunctionFactory ruleFunctionFactory =
        new RuleFunctionFactory(new DefaultTypeCoercerFactory());
    return SkylarkProjectBuildFileParser.using(
        options,
        BuckEventBusForTests.newInstance(),
        SkylarkFilesystem.using(filesystem),
        BuckGlobals.builder()
            .setDescriptions(options.getDescriptions())
            .setDisableImplicitNativeRules(options.getDisableImplicitNativeRules())
            .setRuleFunctionFactory(ruleFunctionFactory)
            .build(),
        eventHandler,
        NativeGlobber::create);
  }
}

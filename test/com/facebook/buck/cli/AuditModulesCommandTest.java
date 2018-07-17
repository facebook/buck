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

package com.facebook.buck.cli;

import static com.facebook.buck.util.MoreStringsForTests.equalToIgnoringPlatformNewlines;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.module.BuckModuleManager;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.MoreStringsForTests;
import com.facebook.buck.util.string.MoreStrings;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AuditModulesCommandTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private TestConsole console;
  private BuckModuleManager moduleManager;

  @Before
  public void setUp() throws IOException, InterruptedException {
    console = new TestConsole();

    ImmutableMap.Builder<String, ModuleInformation> modules = ImmutableMap.builder();
    modules.put("module1.id", new ModuleInformation("hash1", ImmutableSortedSet.of()));
    modules.put("module2.id", new ModuleInformation("hash1", ImmutableSortedSet.of("module1.id")));
    modules.put(
        "module3.id",
        new ModuleInformation("hash1", ImmutableSortedSet.of("module1.id", "module2.id")));

    moduleManager = new TestBuckModuleManager(modules.build());
  }

  @Test
  public void testBuildInfoPrintedInJsonFormat() throws IOException {
    AuditModulesCommand.collectAndDumpModuleInformation(console, moduleManager, true);
    String output = console.getTextWrittenToStdOut();

    String expected =
        Resources.toString(
            Resources.getResource(
                AuditModulesCommandTest.class, "testdata/audit_modules/stdout-json.in"),
            Charsets.UTF_8);

    assertThat(output, is(equalToIgnoringPlatformNewlines(expected)));
  }

  @Test
  public void testBuildInfoPrintedInPlainFormat() throws IOException {
    AuditModulesCommand.collectAndDumpModuleInformation(console, moduleManager, false);
    List<String> output =
        MoreStrings.lines(console.getTextWrittenToStdOut())
            .stream()
            .map(String::trim)
            .map(MoreStringsForTests::normalizeNewlines)
            .collect(Collectors.toList());

    String expected =
        Resources.toString(
            Resources.getResource(
                AuditModulesCommandTest.class, "testdata/audit_modules/stdout-raw.in"),
            Charsets.UTF_8);

    assertEquals(output, MoreStrings.lines(expected));
  }

  private static class ModuleInformation {
    public final String hash;
    public final ImmutableSortedSet<String> dependencies;

    private ModuleInformation(String hash, ImmutableSortedSet<String> dependencies) {
      this.hash = hash;
      this.dependencies = dependencies;
    }
  }

  private static class TestBuckModuleManager implements BuckModuleManager {

    private final ImmutableMap<String, ModuleInformation> modules;

    private TestBuckModuleManager(ImmutableMap<String, ModuleInformation> modules) {
      this.modules = modules;
    }

    @Override
    public boolean isClassInModule(Class<?> cls) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getModuleHash(Class<?> cls) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getModuleHash(String moduleId) {
      return modules.get(moduleId).hash;
    }

    @Override
    public ImmutableSortedSet<String> getModuleIds() {
      return ImmutableSortedSet.copyOf(Ordering.natural(), modules.keySet());
    }

    @Override
    public ImmutableSortedSet<String> getModuleDependencies(String moduleId) {
      return modules.get(moduleId).dependencies;
    }
  }
}

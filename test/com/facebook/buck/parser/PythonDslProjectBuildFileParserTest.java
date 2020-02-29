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

package com.facebook.buck.parser;

import static com.facebook.buck.parser.config.ParserConfig.DEFAULT_BUILD_FILE_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.config.impl.PluginBasedKnownConfigurationDescriptionsFactory;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownNativeRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.io.watchman.WatchmanDiagnosticEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.UserDefinedRuleLoader;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.parser.options.UserDefinedRulesState;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParserTestUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class PythonDslProjectBuildFileParserTest {

  private Cells cell;
  private KnownNativeRuleTypes knownNativeRuleTypes;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void createCell() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    cell = new TestCellBuilder().setFilesystem(filesystem).build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownNativeRuleTypesFactory knownTypesFactory =
        new DefaultKnownNativeRuleTypesFactory(
            new DefaultProcessExecutor(new TestConsole()),
            pluginManager,
            new TestSandboxExecutionStrategyFactory(),
            PluginBasedKnownConfigurationDescriptionsFactory.createFromPlugins(pluginManager));
    knownNativeRuleTypes = knownTypesFactory.create(cell.getRootCell());
  }

  private static FakeProcess fakeProcessWithJsonOutput(
      int returnCode,
      List<Object> values,
      Optional<List<Object>> diagnostics,
      Optional<String> stdout) {
    Map<String, Object> outputToSerialize = new LinkedHashMap<>();
    outputToSerialize.put("values", values);
    if (diagnostics.isPresent()) {
      outputToSerialize.put("diagnostics", diagnostics.get());
    }
    byte[] serialized;
    try {
      serialized = ObjectMappers.WRITER.writeValueAsBytes(outputToSerialize);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return new FakeProcess(
        returnCode,
        new ByteArrayOutputStream(),
        new ByteArrayInputStream(serialized),
        new ByteArrayInputStream(stdout.orElse("").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void whenSubprocessReturnsSuccessThenProjectBuildFileParserClosesCleanly()
      throws IOException, BuildFileParseException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccess()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must not throw.
    }
  }

  @Test
  public void whenSubprocessReturnsFailureThenProjectBuildFileParserDoesNotThrowOnClose()
      throws IOException, BuildFileParseException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsError()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must not throw.
    }
  }

  @Test
  public void whenSubprocessPrintsWarningToStderrThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    List<ConsoleEvent> consoleEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onConsoleEvent(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessAndPrintsToStderr(
            buckEventBus)) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo").getPath());
    }
    assertThat(consoleEvents.get(1).getMessage(), Matchers.containsString("| Don't Panic!"));
  }

  @Test
  public void whenSubprocessReturnsWarningThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    List<ConsoleEvent> consoleEvents = new ArrayList<>();
    List<WatchmanDiagnosticEvent> watchmanDiagnosticEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void on(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }

      @Subscribe
      public void on(WatchmanDiagnosticEvent event) {
        watchmanDiagnosticEvents.add(event);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessWithWarning(
            buckEventBus, "This is a warning", "parser")) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo").getPath());
    }
    assertThat(
        consoleEvents,
        Matchers.contains(
            Matchers.hasToString("Warning raised by BUCK file parser: This is a warning")));
    assertThat(
        "Should not receive any watchman diagnostic events",
        watchmanDiagnosticEvents,
        Matchers.empty());
  }

  @Test
  public void whenSubprocessReturnsNewWatchmanWarningThenDiagnosticEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    List<WatchmanDiagnosticEvent> watchmanDiagnosticEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void on(WatchmanDiagnosticEvent consoleEvent) {
        watchmanDiagnosticEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessWithWarning(
            buckEventBus, "This is a watchman warning", "watchman")) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo").getPath());
    }
    assertThat(
        watchmanDiagnosticEvents,
        Matchers.contains(
            Matchers.hasToString(Matchers.containsString("This is a watchman warning"))));
  }

  @Test
  public void whenSubprocessReturnsErrorThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    List<ConsoleEvent> consoleEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onConsoleEvent(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessWithError(
            buckEventBus, "This is an error", "parser")) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo").getPath());
    }
    assertThat(
        consoleEvents,
        Matchers.contains(
            Matchers.hasToString("Error raised by BUCK file parser: This is an error")));
  }

  @Test
  public void whenSubprocessReturnsSyntaxErrorInFileBeingParsedThenExceptionContainsFileNameOnce()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    String expectedPath = cell.getRootCell().getRoot().resolve("foo/BUCK").toString();
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse "
            + expectedPath
            + ":\n"
            + "Syntax error on line 23, column 16:\n"
            + "java_test(name=*@!&#(!@&*()\n"
            + "               ^");

    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsErrorWithException(
            BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
            "This is an error",
            "parser",
            ImmutableMap.<String, Object>builder()
                .put("type", "SyntaxError")
                .put("value", "You messed up")
                .put("filename", expectedPath)
                .put("lineno", 23)
                .put("offset", 16)
                .put("text", "java_test(name=*@!&#(!@&*()\n")
                .put(
                    "traceback",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "filename", "foo/BUCK",
                            "line_number", 23,
                            "function_name", "<module>",
                            "text", "java_test(name=*@!&#(!@&*()\n")))
                .build())) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo/BUCK").getPath());
    }
  }

  @Test
  public void whenSubprocessReturnsSyntaxErrorWithoutOffsetThenExceptionIsFormattedWithoutColumn()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    String expectedPath = cell.getRootCell().getRoot().resolve("foo/BUCK").toString();
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse "
            + expectedPath
            + ":\n"
            + "Syntax error on line 23:\n"
            + "java_test(name=*@!&#(!@&*()");

    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsErrorWithException(
            BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
            "This is an error",
            "parser",
            ImmutableMap.<String, Object>builder()
                .put("type", "SyntaxError")
                .put("value", "You messed up")
                .put("filename", expectedPath)
                .put("lineno", 23)
                .put("text", "java_test(name=*@!&#(!@&*()\n")
                .put(
                    "traceback",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "filename", "foo/BUCK",
                            "line_number", 23,
                            "function_name", "<module>",
                            "text", "java_test(name=*@!&#(!@&*()\n")))
                .build())) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo/BUCK").getPath());
    }
  }

  @Test
  public void whenSubprocessReturnsSyntaxErrorInDifferentFileThenExceptionContainsTwoFileNames()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance(FakeClock.doNotCare());
    List<ConsoleEvent> consoleEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onConsoleEvent(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    String expectedPath = cell.getRootCell().getRoot().resolve("foo/BUCK").toString();
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse "
            + expectedPath
            + ":\n"
            + "Syntax error in bar/BUCK\n"
            + "Line 42, column 24:\n"
            + "def some_helper_method(!@87*@!#\n"
            + "                       ^");

    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsErrorWithException(
            buckEventBus,
            "This is an error",
            "parser",
            ImmutableMap.<String, Object>builder()
                .put("type", "SyntaxError")
                .put("value", "You messed up")
                .put("filename", "bar/BUCK")
                .put("lineno", 42)
                .put("offset", 24)
                .put("text", "def some_helper_method(!@87*@!#\n")
                .put(
                    "traceback",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "filename", "bar/BUCK",
                            "line_number", 42,
                            "function_name", "<module>",
                            "text", "def some_helper_method(!@87*@!#\n"),
                        ImmutableMap.of(
                            "filename",
                            expectedPath,
                            "line_number",
                            23,
                            "function_name",
                            "<module>",
                            "text",
                            "some_helper_method(name=*@!&#(!@&*()\n")))
                .build())) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo/BUCK").getPath());
    }
  }

  @Test
  public void whenSubprocessReturnsNonSyntaxErrorThenExceptionContainsFullStackTrace()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);
    String expectedPath = cell.getRootCell().getRoot().resolve("foo/BUCK").toString();
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse "
            + expectedPath
            + ":\n"
            + "ZeroDivisionError: integer division or modulo by zero\n"
            + "Call stack:\n"
            + "  File \"bar/BUCK\", line 42, in lets_divide_by_zero\n"
            + "    foo / bar\n"
            + "\n"
            + "  File \"foo/BUCK\", line 23\n"
            + "    lets_divide_by_zero()\n"
            + "\n");

    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsErrorWithException(
            BuckEventBusForTests.newInstance(FakeClock.doNotCare()),
            "This is an error",
            "parser",
            ImmutableMap.<String, Object>builder()
                .put("type", "ZeroDivisionError")
                .put("value", "integer division or modulo by zero")
                .put("filename", "bar/BUCK")
                .put("lineno", 42)
                .put("offset", 24)
                .put("text", "foo / bar\n")
                .put(
                    "traceback",
                    ImmutableList.of(
                        ImmutableMap.of(
                            "filename", "bar/BUCK",
                            "line_number", 42,
                            "function_name", "lets_divide_by_zero",
                            "text", "foo / bar\n"),
                        ImmutableMap.of(
                            "filename", "foo/BUCK",
                            "line_number", 23,
                            "function_name", "<module>",
                            "text", "lets_divide_by_zero()\n")))
                .build())) {
      buildFileParser.initIfNeeded();
      buildFileParser.getManifest(cell.getRootCell().getRoot().resolve("foo/BUCK").getPath());
    }
  }

  @Test
  public void loadsUserDefinedRulesViaSkylarkWhenReturnedFromParser()
      throws InterruptedException, IOException {

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRootCell().getRoot(), knownNativeRuleTypes);

    ImmutableMap<String, Object> returnedTarget =
        ImmutableMap.<String, Object>of(
            "name", "bar", "buck.type", "//foo:udr.bzl:my_rule", "val", 5);

    ImmutableList<String> extensionContents =
        ImmutableList.of(
            "def _impl(ctx):",
            "    pass",
            "my_rule = rule(",
            "    implementation=_impl,",
            "    attrs={\"v\": attr.int(default=1)}",
            ")");

    filesystem.mkdirs(Paths.get("foo"));
    filesystem.writeLinesToPath(extensionContents, Paths.get("foo", "udr.bzl"));

    TestProjectBuildFileParserFactory.TestSkylarkParser udrLoader =
        buildFileParserFactory.createSkylarkParser(cell.getRootCell());

    // Ensure we've got nothing preloaded
    assertNull(udrLoader.knownTypes(cell.getRootCell()).getRule("//foo:udr.bzl:my_rule"));

    BuildFileManifest out;
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createHybridParserThatReturnsSuccessAndTargets(
            ImmutableList.of(returnedTarget), udrLoader)) {
      buildFileParser.initIfNeeded();
      out =
          buildFileParser.getManifest(
              cell.getRootCell().getRoot().resolve("foo").resolve("BUCK").getPath());
    }

    assertEquals(5L, out.getTargets().get("bar").get("val"));
    assertEquals("bar", out.getTargets().get("bar").get("name"));
    assertNotNull(udrLoader.knownTypes(cell.getRootCell()).getRule("//foo:udr.bzl:my_rule"));
  }

  /**
   * ProjectBuildFileParser test double which counts the number of times rules are parsed to test
   * caching logic in Parser.
   */
  private static class TestProjectBuildFileParserFactory {
    private final AbsPath projectRoot;
    private final KnownNativeRuleTypes ruleTypes;

    public TestProjectBuildFileParserFactory(AbsPath projectRoot, KnownNativeRuleTypes ruleTypes) {
      this.projectRoot = projectRoot;
      this.ruleTypes = ruleTypes;
    }

    public PythonDslProjectBuildFileParser createNoopParserThatAlwaysReturnsError() {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      1, ImmutableList.of(), Optional.empty(), Optional.empty()),
              new TestConsole()),
          BuckEventBusForTests.newInstance());
    }

    PythonDslProjectBuildFileParser createHybridParserThatReturnsSuccessAndTargets(
        ImmutableList<ImmutableMap<String, Object>> targets, UserDefinedRuleLoader udrLoader) {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      0,
                      ImmutableList.builder()
                          .addAll(targets)
                          .add(ImmutableMap.of(MetaRules.INCLUDES, ImmutableList.of()))
                          .add(ImmutableMap.of(MetaRules.CONFIGS, ImmutableMap.of()))
                          .add(ImmutableMap.of(MetaRules.ENV, ImmutableMap.of()))
                          .build(),
                      Optional.of(ImmutableList.of()),
                      Optional.empty()),
              new TestConsole()),
          BuckEventBusForTests.newInstance(),
          udrLoader);
    }

    public PythonDslProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccess() {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      0,
                      ImmutableList.of(MetaRules.INCLUDES, MetaRules.CONFIGS, MetaRules.ENV),
                      Optional.empty(),
                      Optional.empty()),
              new TestConsole()),
          BuckEventBusForTests.newInstance());
    }

    public PythonDslProjectBuildFileParser
        createNoopParserThatAlwaysReturnsSuccessAndPrintsToStderr(BuckEventBus buckEventBus) {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      0, ImmutableList.of(), Optional.empty(), Optional.of("Don't Panic!")),
              new TestConsole()),
          buckEventBus);
    }

    public PythonDslProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccessWithWarning(
        BuckEventBus buckEventBus, String warning, String source) {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      0,
                      ImmutableList.of(),
                      Optional.of(
                          ImmutableList.of(
                              ImmutableMap.of(
                                  "level", "warning", "message", warning, "source", source))),
                      Optional.empty()),
              new TestConsole()),
          buckEventBus);
    }

    public PythonDslProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccessWithError(
        BuckEventBus buckEventBus, String error, String source) {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      0,
                      ImmutableList.of(),
                      Optional.of(
                          ImmutableList.of(
                              ImmutableMap.of(
                                  "level", "error", "message", error, "source", source))),
                      Optional.empty()),
              new TestConsole()),
          buckEventBus);
    }

    public PythonDslProjectBuildFileParser createNoopParserThatAlwaysReturnsErrorWithException(
        BuckEventBus buckEventBus,
        String error,
        String source,
        ImmutableMap<String, Object> exception) {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      1,
                      ImmutableList.of(),
                      Optional.of(
                          ImmutableList.of(
                              ImmutableMap.of(
                                  "level",
                                  "fatal",
                                  "message",
                                  error,
                                  "source",
                                  source,
                                  "exception",
                                  exception))),
                      Optional.empty()),
              new TestConsole()),
          buckEventBus);
    }

    TestSkylarkParser createSkylarkParser(Cell cell) {
      return new TestSkylarkParser(getOptions("fake-python"), cell.getFilesystem(), cell);
    }

    private class TestSkylarkParser implements UserDefinedRuleLoader {

      private final KnownRuleTypesProvider knownRuleTypesProvider;
      private final SkylarkProjectBuildFileParser parser;

      private TestSkylarkParser(
          ProjectBuildFileParserOptions options, ProjectFilesystem filesystem, Cell cell) {
        this.knownRuleTypesProvider = new KnownRuleTypesProvider(cell1 -> ruleTypes);
        this.parser =
            SkylarkProjectBuildFileParserTestUtils.createParserWithOptions(
                SkylarkFilesystem.using(filesystem),
                new PrintingEventHandler(EventKind.ALL_EVENTS),
                options,
                knownRuleTypesProvider,
                cell);
      }

      @Override
      public void loadExtensionsForUserDefinedRules(Path buildFile, BuildFileManifest manifest) {
        parser.loadExtensionsForUserDefinedRules(buildFile, manifest);
      }

      KnownUserDefinedRuleTypes knownTypes(Cell cell) {
        return knownRuleTypesProvider.getUserDefinedRuleTypes(cell);
      }
    }

    private class TestPythonDslProjectBuildFileParser extends PythonDslProjectBuildFileParser {

      public TestPythonDslProjectBuildFileParser(
          String pythonInterpreter, ProcessExecutor processExecutor, BuckEventBus buckEventBus) {
        this(pythonInterpreter, processExecutor, buckEventBus, Optional.empty());
      }

      public TestPythonDslProjectBuildFileParser(
          String pythonInterpreter,
          ProcessExecutor processExecutor,
          BuckEventBus buckEventBus,
          UserDefinedRuleLoader udrLoader) {
        this(pythonInterpreter, processExecutor, buckEventBus, Optional.of(udrLoader));
      }

      public TestPythonDslProjectBuildFileParser(
          String pythonInterpreter,
          ProcessExecutor processExecutor,
          BuckEventBus buckEventBus,
          Optional<UserDefinedRuleLoader> udrLoader) {
        super(
            getOptions(pythonInterpreter),
            new DefaultTypeCoercerFactory(),
            ImmutableMap.of(),
            buckEventBus,
            processExecutor,
            Optional.empty(),
            udrLoader);
      }
    }

    private ProjectBuildFileParserOptions getOptions(String pythonInterpreter) {
      return ProjectBuildFileParserOptions.builder()
          .setProjectRoot(projectRoot)
          .setPythonInterpreter(pythonInterpreter)
          .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
          .setIgnorePaths(ImmutableSet.of())
          .setBuildFileName(DEFAULT_BUILD_FILE_NAME)
          .setDefaultIncludes(ImmutableSet.of("//java/com/facebook/defaultIncludeFile"))
          .setDescriptions(ruleTypes.getDescriptions())
          .setBuildFileImportWhitelist(ImmutableList.of())
          .setUserDefinedRulesState(UserDefinedRulesState.ENABLED)
          .setBuildFileImportWhitelist(ImmutableList.of())
          .build();
    }
  }
}

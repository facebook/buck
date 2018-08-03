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

package com.facebook.buck.parser;

import static com.facebook.buck.parser.ParserConfig.DEFAULT_BUILD_FILE_NAME;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.rules.config.impl.PluginBasedKnownConfigurationDescriptionsFactory;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.watchman.WatchmanDiagnosticEvent;
import com.facebook.buck.json.PythonDslProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.sandbox.TestSandboxExecutionStrategyFactory;
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
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class PythonDslProjectBuildFileParserTest {

  private Cell cell;
  private KnownRuleTypes knownRuleTypes;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void createCell() {
    cell = new TestCellBuilder().build();
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesFactory knownTypesFactory =
        new DefaultKnownRuleTypesFactory(
            new DefaultProcessExecutor(new TestConsole()),
            pluginManager,
            new TestSandboxExecutionStrategyFactory(),
            PluginBasedKnownConfigurationDescriptionsFactory.createFromPlugins(pluginManager));
    knownRuleTypes = knownTypesFactory.create(cell);
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
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccess()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must not throw.
    }
  }

  @Test(expected = BuildFileParseException.class)
  public void whenSubprocessReturnsFailureThenProjectBuildFileParserThrowsOnClose()
      throws IOException, BuildFileParseException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
    try (PythonDslProjectBuildFileParser buildFileParser =
        buildFileParserFactory.createNoopParserThatAlwaysReturnsError()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must throw.
    }
  }

  @Test
  public void whenSubprocessPrintsWarningToStderrThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
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
      buildFileParser.getBuildFileManifest(Paths.get("foo"), new AtomicLong());
    }
    assertThat(consoleEvents.get(1).getMessage(), Matchers.containsString("| Don't Panic!"));
  }

  @Test
  public void whenSubprocessReturnsWarningThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
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
      buildFileParser.getBuildFileManifest(Paths.get("foo"), new AtomicLong());
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
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
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
      buildFileParser.getBuildFileManifest(Paths.get("foo"), new AtomicLong());
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
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
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
      buildFileParser.getBuildFileManifest(Paths.get("foo"), new AtomicLong());
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
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse foo/BUCK:\n"
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
                .put("filename", "foo/BUCK")
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
      buildFileParser.getBuildFileManifest(Paths.get("foo/BUCK"), new AtomicLong());
    }
  }

  @Test
  public void whenSubprocessReturnsSyntaxErrorWithoutOffsetThenExceptionIsFormattedWithoutColumn()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse foo/BUCK:\n"
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
                .put("filename", "foo/BUCK")
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
      buildFileParser.getBuildFileManifest(Paths.get("foo/BUCK"), new AtomicLong());
    }
  }

  @Test
  public void whenSubprocessReturnsSyntaxErrorInDifferentFileThenExceptionContainsTwoFileNames()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);
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
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse foo/BUCK:\n"
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
                            "filename", "foo/BUCK",
                            "line_number", 23,
                            "function_name", "<module>",
                            "text", "some_helper_method(name=*@!&#(!@&*()\n")))
                .build())) {
      buildFileParser.initIfNeeded();
      buildFileParser.getBuildFileManifest(Paths.get("foo/BUCK"), new AtomicLong());
    }
  }

  @Test
  public void whenSubprocessReturnsNonSyntaxErrorThenExceptionContainsFullStackTrace()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), knownRuleTypes);

    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Buck wasn't able to parse foo/BUCK:\n"
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
      buildFileParser.getBuildFileManifest(Paths.get("foo/BUCK"), new AtomicLong());
    }
  }

  /**
   * ProjectBuildFileParser test double which counts the number of times rules are parsed to test
   * caching logic in Parser.
   */
  private static class TestProjectBuildFileParserFactory {
    private final Path projectRoot;
    private final KnownRuleTypes ruleTypes;

    public TestProjectBuildFileParserFactory(Path projectRoot, KnownRuleTypes ruleTypes) {
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

    public PythonDslProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccess() {
      return new TestPythonDslProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              params ->
                  fakeProcessWithJsonOutput(
                      0,
                      ImmutableList.of("__includes", "__configs", "__env"),
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

    private class TestPythonDslProjectBuildFileParser extends PythonDslProjectBuildFileParser {
      public TestPythonDslProjectBuildFileParser(
          String pythonInterpreter, ProcessExecutor processExecutor, BuckEventBus buckEventBus) {
        super(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(projectRoot)
                .setPythonInterpreter(pythonInterpreter)
                .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
                .setIgnorePaths(ImmutableSet.of())
                .setBuildFileName(DEFAULT_BUILD_FILE_NAME)
                .setDefaultIncludes(ImmutableSet.of("//java/com/facebook/defaultIncludeFile"))
                .setDescriptions(ruleTypes.getDescriptions())
                .setBuildFileImportWhitelist(ImmutableList.of())
                .build(),
            new DefaultTypeCoercerFactory(),
            ImmutableMap.of(),
            buckEventBus,
            processExecutor);
      }
    }
  }
}

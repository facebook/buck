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

package com.facebook.buck.step.buildables;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.stringContainsInOrder;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystemFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.EnvironmentSanitizer;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Rule;
import org.junit.Test;

public class BuildableCommandExecutionStepTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  @Test
  public void canExecuteStep() throws Exception {

    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL binary = Resources.getResource(packageName + "/external_actions_bin_for_tests.jar");
    AbsPath testBinary =
        AbsPath.of(
            temporaryFolder.getRoot().getPath().resolve("external_action.jar").toAbsolutePath());
    try (FileOutputStream stream = new FileOutputStream(testBinary.toFile())) {
      stream.write(Resources.toByteArray(binary));
    }

    HashMap<String, String> envs = new HashMap<>(EnvVariablesProvider.getSystemEnv());
    envs.put("CLASSPATH", testBinary.toString());
    envs.put("BUCK_CLASSPATH", testBinary.toString());
    setEnv(envs);

    BuildableCommandExecutionStep testStep =
        new BuildableCommandExecutionStep(
            BuildableCommand.getDefaultInstance(),
            projectFilesystem,
            ImmutableList.of(JavaBuckConfig.getJavaBinCommand()),
            FakeExternalActionsMain.class.getCanonicalName());
    StepExecutionResult result = testStep.execute(createExecutionContext(projectFilesystem));
    assertThat(result.getExitCode(), equalTo(0));
    assertThat(
        result.getStderr().orElseThrow(IllegalStateException::new),
        stringContainsInOrder("Received args:", "buildable_command_"));
  }

  @SuppressWarnings({"PMD.BlacklistedSystemGetenv", "unchecked"})
  private static void setEnv(Map<String, String> newenv) throws Exception {
    try {
      Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
      Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
      theEnvironmentField.setAccessible(true);
      Map<String, String> env = (Map<String, String>) theEnvironmentField.get(null);
      env.putAll(newenv);
      Field theCaseInsensitiveEnvironmentField =
          processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment");
      theCaseInsensitiveEnvironmentField.setAccessible(true);
      Map<String, String> cienv =
          (Map<String, String>) theCaseInsensitiveEnvironmentField.get(null);
      cienv.putAll(newenv);
    } catch (NoSuchFieldException e) {
      Map<String, String> env = System.getenv();
      for (Class<?> cl : Collections.class.getDeclaredClasses()) {
        if (cl.getName().endsWith("$UnmodifiableMap")) {
          Field field = cl.getDeclaredField("m");
          field.setAccessible(true);
          Object obj = field.get(env);
          Map<String, String> map = (Map<String, String>) obj;
          map.clear();
          map.putAll(newenv);
        }
      }
    }
  }

  private StepExecutionContext createExecutionContext(ProjectFilesystem projectFilesystem) {
    AbsPath rootPath = projectFilesystem.getRootPath();
    return StepExecutionContext.builder()
        .setConsole(Console.createNullConsole())
        .setBuckEventBus(BuckEventBusForTests.newInstance())
        .setPlatform(Platform.UNKNOWN)
        .setEnvironment(EnvironmentSanitizer.getSanitizedEnvForTests(ImmutableMap.of()))
        .setBuildCellRootPath(Paths.get("cell"))
        .setProcessExecutor(
            new DefaultProcessExecutor(
                new Console(
                    Verbosity.STANDARD_INFORMATION, System.out, System.err, new Ansi(true))))
        .setProjectFilesystemFactory(new FakeProjectFilesystemFactory())
        .setRuleCellRoot(rootPath)
        .setActionId("test_action_id")
        .setClock(FakeClock.doNotCare())
        .setWorkerToolPools(new ConcurrentHashMap<>())
        .build();
  }
}

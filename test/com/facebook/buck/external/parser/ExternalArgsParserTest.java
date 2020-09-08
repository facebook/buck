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

package com.facebook.buck.external.parser;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.external.model.ParsedArgs;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ExternalArgsParserTest {
  @Rule public final ExpectedException exception = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void throwsIfNullArgs() {
    exception.expect(NullPointerException.class);
    exception.expectMessage("Expected 1 arg. Received null args");
    new ExternalArgsParser().parse(null);
  }

  @Test
  public void throwsIfNotOneArg() {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Expected 1 arg. Received 2");
    new ExternalArgsParser().parse(new String[] {"one", "two"});
  }

  @Test
  public void throwsIfIOException() {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Cannot read buildable command");
    new ExternalArgsParser().parse(new String[] {"nonexistent_path"});
  }

  @Test
  public void throwsIfClassNotFound() throws Exception {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Cannot find external actions class: com.facebook.buck.android.foo");

    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of(""))
            .putAllEnv(ImmutableMap.of())
            .setExternalActionClass("com.facebook.buck.android.foo")
            .build();
    File tempFile = temporaryFolder.newFile("tmp_file").toFile();
    try (OutputStream outputStream = new FileOutputStream(tempFile)) {
      buildableCommand.writeTo(outputStream);
    }

    new ExternalArgsParser().parse(new String[] {tempFile.getAbsolutePath()});
  }

  @Test
  public void commandLineArgsCanBeParsed() throws Exception {
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of("somefile"))
            .putAllEnv(ImmutableMap.of())
            .setExternalActionClass(TestExternalActionClassFoo.class.getName())
            .build();

    File tempFile = temporaryFolder.newFile("tmp_file").toFile();
    try (OutputStream outputStream = new FileOutputStream(tempFile)) {
      buildableCommand.writeTo(outputStream);
    }

    ParsedArgs parsedArgs =
        new ExternalArgsParser().parse(new String[] {tempFile.getAbsolutePath()});

    assertThat(parsedArgs.getExternalActionClass(), equalTo(TestExternalActionClassFoo.class));
    assertThat(parsedArgs.getBuildableCommand(), equalTo(buildableCommand));
  }

  private static class TestExternalActionClassFoo implements ExternalAction {
    @Override
    public ImmutableList<IsolatedStep> getSteps(BuildableCommand buildableCommand) {
      return ImmutableList.of();
    }
  }
}

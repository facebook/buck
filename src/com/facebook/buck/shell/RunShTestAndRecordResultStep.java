/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.rules.TestResultSummary;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class RunShTestAndRecordResultStep implements Step {

  private String pathToShellScript;
  private String pathToTestResultFile;

  public RunShTestAndRecordResultStep(String pathToShellScript, String pathToTestResultFile) {
    this.pathToShellScript = Preconditions.checkNotNull(pathToShellScript);
    this.pathToTestResultFile = Preconditions.checkNotNull(pathToTestResultFile);
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return pathToShellScript;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return pathToShellScript;
  }

  @Override
  public int execute(ExecutionContext context) {
    ShellStep test = new ShellStep() {
      @Override
      public String getShortName(ExecutionContext context) {
        return pathToShellScript;
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(
          ExecutionContext context) {
        return ImmutableList.of(pathToShellScript);
      }

      @Override
      protected boolean shouldRecordStdout() {
        return true;
      }
    };
    int exitCode = test.execute(context);

    // Write test result.
    TestResultSummary summary = new TestResultSummary(
        pathToShellScript,
        "sh_test",
        /* isSuccess */ exitCode == 0,
        /* time */ -1L, // TODO(mbolin): Time sh_test
        /* message */ null,
        /* stacktrace */ null,
        test.getStdOut(),
        /* stderr */ null);

    ObjectMapper mapper = new ObjectMapper();
    try {
      mapper.writeValue(
          Files.newWriter(new File(pathToTestResultFile), Charsets.UTF_8),
          summary);
    } catch (JsonGenerationException e) {
      Throwables.propagate(e);
    } catch (JsonMappingException e) {
      Throwables.propagate(e);
    } catch (FileNotFoundException e) {
      Throwables.propagate(e);
    } catch (IOException e) {
      Throwables.propagate(e);
    }

    // Even though the test may have failed, this command executed successfully, so its exit code
    // should be zero.
    return 0;
  }

}

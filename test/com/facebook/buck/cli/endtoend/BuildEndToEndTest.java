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

package com.facebook.buck.cli.endtoend;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.endtoend.EndToEndEnvironment;
import com.facebook.buck.testutil.endtoend.EndToEndRunner;
import com.facebook.buck.testutil.endtoend.EndToEndTestDescriptor;
import com.facebook.buck.testutil.endtoend.EndToEndWorkspace;
import com.facebook.buck.testutil.endtoend.Environment;
import com.facebook.buck.testutil.endtoend.EnvironmentFor;
import com.google.common.collect.ImmutableMap;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(EndToEndRunner.class)
public class BuildEndToEndTest {

  @Environment
  public static EndToEndEnvironment getBaseEnvironment() {
    return new EndToEndEnvironment()
        .addTemplates("cli")
        .withCommand("build")
        .addLocalConfigSet(
            ImmutableMap.of("parser", ImmutableMap.of("default_build_file_syntax", "SKYLARK")))
        .addLocalConfigSet(
            ImmutableMap.of("parser", ImmutableMap.of("default_build_file_syntax", "PYTHON_DSL")));
  }

  @EnvironmentFor(testNames = {"shouldRewriteFailureMessagesAndAppendThem"})
  public static EndToEndEnvironment setTargetPathThatCallsFail() {
    return getBaseEnvironment().withTargets("//parse_failure/fail_message:fail");
  }

  @EnvironmentFor(testNames = {"shouldRewriteFailureMessagesForInvalidTargets"})
  public static EndToEndEnvironment setTargetPathThatHasBadTargets() {
    return getBaseEnvironment().withTargets("//parse_failure/invalid_deps:main");
  }

  @Test
  public void shouldRewriteFailureMessagesAndAppendThem(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOption(
        "ui",
        "error_message_augmentations",
        "\"name ([-\\\\w]*) provided\" => \"You pizza'd when you should have french fried on $1\"");

    Pattern expected =
        Pattern.compile(
            "Invalid name fail-py provided$.*^You pizza'd when you should have french fried on fail-py",
            Pattern.MULTILINE | Pattern.DOTALL);

    ProcessResult result = workspace.runBuckCommand(test);
    result.assertFailure();
    Assert.assertTrue(
        String.format("'%s' was not contained in '%s'", expected.pattern(), result.getStderr()),
        expected.matcher(result.getStderr()).find());
  }

  @Test
  public void shouldRewriteFailureMessagesForInvalidTargets(
      EndToEndTestDescriptor test, EndToEndWorkspace workspace) throws Exception {
    workspace.addBuckConfigLocalOption(
        "ui",
        "error_message_augmentations",
        "\"The rule (//\\\\S+)-cxx could not be found.\" => \"Please make sure that $1 "
            + "is a cxx library. If it is not, add it to extra_deps instead\"");

    Pattern expected =
        Pattern.compile(
            "The rule //parse_failure/invalid_deps:main-cxx could not be found\\..*"
                + "Please make sure that //parse_failure/invalid_deps:main is a cxx library. "
                + "If it is not, add it to extra_deps instead",
            Pattern.MULTILINE | Pattern.DOTALL);

    ProcessResult result = workspace.runBuckCommand(test);
    result.assertFailure();
    Assert.assertTrue(
        String.format("'%s' was not contained in '%s'", expected.pattern(), result.getStderr()),
        expected.matcher(result.getStderr()).find());
  }
}

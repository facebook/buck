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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class PlistProcessStepTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static final Path INPUT_PATH = Paths.get("input.plist");
  private static final Path MERGE_PATH = Paths.get("merge.plist");
  private static final Path OUTPUT_PATH = Paths.get("output.plist");

  @Test
  public void testFailsWithInvalidInput() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep =
        new PlistProcessStep(
            projectFilesystem,
            INPUT_PATH,
            Optional.empty(),
            OUTPUT_PATH,
            ImmutableMap.of(),
            ImmutableMap.of(),
            PlistProcessStep.OutputFormat.XML);

    projectFilesystem.writeContentsToPath("<html>not a <b>plist</b></html>", INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();
    thrown.expect(IOException.class);
    thrown.expectMessage(containsString("not a property list"));
    plistProcessStep.execute(executionContext);
  }

  @Test
  public void testFailsWithEmptyFileInput() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep =
        new PlistProcessStep(
            projectFilesystem,
            INPUT_PATH,
            Optional.empty(),
            OUTPUT_PATH,
            ImmutableMap.of(),
            ImmutableMap.of(),
            PlistProcessStep.OutputFormat.XML);

    projectFilesystem.writeContentsToPath("", INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString("input.plist: the content of the plist is invalid or empty."));
    plistProcessStep.execute(executionContext);
  }

  @Test
  public void testOverrideReplacesExistingKey() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep =
        new PlistProcessStep(
            projectFilesystem,
            INPUT_PATH,
            Optional.empty(),
            OUTPUT_PATH,
            ImmutableMap.of(),
            ImmutableMap.of("Key1", new NSString("OverrideValue")),
            PlistProcessStep.OutputFormat.XML);

    NSDictionary dict = new NSDictionary();
    dict.put("Key1", "Value1");
    dict.put("Key2", "Value2");
    projectFilesystem.writeContentsToPath(dict.toXMLPropertyList(), INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int errorCode = plistProcessStep.execute(executionContext).getExitCode();
    assertThat(errorCode, equalTo(0));

    dict.put("Key1", "OverrideValue");
    assertThat(
        projectFilesystem.readFileIfItExists(OUTPUT_PATH),
        equalTo(Optional.of(dict.toXMLPropertyList())));
  }

  @Test
  public void testAdditionDoesNotReplaceExistingKey() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep =
        new PlistProcessStep(
            projectFilesystem,
            INPUT_PATH,
            Optional.empty(),
            OUTPUT_PATH,
            ImmutableMap.of("Key1", new NSString("OverrideValue")),
            ImmutableMap.of(),
            PlistProcessStep.OutputFormat.XML);

    NSDictionary dict = new NSDictionary();
    dict.put("Key1", "Value1");
    dict.put("Key2", "Value2");
    projectFilesystem.writeContentsToPath(dict.toXMLPropertyList(), INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int errorCode = plistProcessStep.execute(executionContext).getExitCode();
    assertThat(errorCode, equalTo(0));

    assertThat(
        projectFilesystem.readFileIfItExists(OUTPUT_PATH),
        equalTo(Optional.of(dict.toXMLPropertyList())));
  }

  @Test
  public void testHandlesNonDictionaryPlists() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep =
        new PlistProcessStep(
            projectFilesystem,
            INPUT_PATH,
            Optional.empty(),
            OUTPUT_PATH,
            ImmutableMap.of(),
            ImmutableMap.of("Key1", new NSString("OverrideValue")),
            PlistProcessStep.OutputFormat.XML);

    NSArray array = new NSArray(new NSString("Value1"), new NSString("Value2"));
    projectFilesystem.writeContentsToPath(array.toXMLPropertyList(), INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int errorCode = plistProcessStep.execute(executionContext).getExitCode();
    assertThat(errorCode, equalTo(0));

    assertThat(
        projectFilesystem.readFileIfItExists(OUTPUT_PATH),
        equalTo(Optional.of(array.toXMLPropertyList())));
  }

  @Test
  public void testMergeFromFileReplacesExistingKey() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep =
        new PlistProcessStep(
            projectFilesystem,
            INPUT_PATH,
            Optional.of(MERGE_PATH),
            OUTPUT_PATH,
            ImmutableMap.of(),
            ImmutableMap.of(),
            PlistProcessStep.OutputFormat.XML);

    NSDictionary dict = new NSDictionary();
    dict.put("Key1", "Value1");
    dict.put("Key2", "Value2");
    projectFilesystem.writeContentsToPath(dict.toXMLPropertyList(), INPUT_PATH);

    NSDictionary overrideDict = new NSDictionary();
    overrideDict.put("Key1", "OverrideValue");
    projectFilesystem.writeContentsToPath(overrideDict.toXMLPropertyList(), MERGE_PATH);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    int errorCode = plistProcessStep.execute(executionContext).getExitCode();
    assertThat(errorCode, equalTo(0));

    dict.put("Key1", "OverrideValue");
    assertThat(
        projectFilesystem.readFileIfItExists(OUTPUT_PATH),
        equalTo(Optional.of(dict.toXMLPropertyList())));
  }
}

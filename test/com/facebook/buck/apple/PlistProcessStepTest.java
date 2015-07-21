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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import com.dd.plist.NSObject;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;

import java.nio.file.Paths;
import java.nio.file.Path;

public class PlistProcessStepTest {

  private static final Path INPUT_PATH = Paths.get("input.plist");
  private static final Path OUTPUT_PATH = Paths.get("output.plist");

  @Test
  public void testFailsWithInvalidInput() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep = new PlistProcessStep(
        INPUT_PATH,
        OUTPUT_PATH,
        ImmutableMap.<String, NSObject>of(),
        ImmutableMap.<String, NSObject>of(),
        PlistProcessStep.OutputFormat.XML);

    projectFilesystem.writeContentsToPath(
        "<html>not a <b>plist</b></html>",
        INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    int errorCode = plistProcessStep.execute(executionContext);
    assertThat(errorCode, equalTo(1));
  }

  @Test
  public void testOverrideReplacesExistingKey() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep = new PlistProcessStep(
        INPUT_PATH,
        OUTPUT_PATH,
        ImmutableMap.<String, NSObject>of(),
        ImmutableMap.<String, NSObject>of(
            "Key1", new NSString("OverrideValue")),
        PlistProcessStep.OutputFormat.XML);

    NSDictionary dict = new NSDictionary();
    dict.put("Key1", "Value1");
    dict.put("Key2", "Value2");
    projectFilesystem.writeContentsToPath(
        dict.toXMLPropertyList(),
        INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    int errorCode = plistProcessStep.execute(executionContext);
    assertThat(errorCode, equalTo(0));

    dict.put("Key1", "OverrideValue");
    assertThat(
        projectFilesystem.readFileIfItExists(OUTPUT_PATH),
        equalTo(Optional.of(dict.toXMLPropertyList())));
  }

  @Test
  public void testAdditionDoesNotReplaceExistingKey() throws Exception {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    PlistProcessStep plistProcessStep = new PlistProcessStep(
        INPUT_PATH,
        OUTPUT_PATH,
        ImmutableMap.<String, NSObject>of(
            "Key1", new NSString("OverrideValue")),
        ImmutableMap.<String, NSObject>of(),
        PlistProcessStep.OutputFormat.XML);

    NSDictionary dict = new NSDictionary();
    dict.put("Key1", "Value1");
    dict.put("Key2", "Value2");
    projectFilesystem.writeContentsToPath(
        dict.toXMLPropertyList(),
        INPUT_PATH);

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    int errorCode = plistProcessStep.execute(executionContext);
    assertThat(errorCode, equalTo(0));

    assertThat(
        projectFilesystem.readFileIfItExists(OUTPUT_PATH),
        equalTo(Optional.of(dict.toXMLPropertyList())));
  }
}

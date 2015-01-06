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

package com.facebook.buck.parcelable;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GeneratorTest {

  @Test
  public void testGenerate() throws IOException, SAXException {
    Path testData = TestDataHelper.getTestDataDirectory(this).resolve("generator");
    Path parcelableDescriptor = testData.resolve("student.xml");

    String observed = Generator.generateFromPath(parcelableDescriptor);

    String expected =
        new String(Files.readAllBytes(testData.resolve("GeneratedStudent.java")), UTF_8);
    assertEquals(expected, observed);
  }
}

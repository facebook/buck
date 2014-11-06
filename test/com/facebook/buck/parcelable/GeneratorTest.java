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

import static org.junit.Assert.assertEquals;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;

public class GeneratorTest {

  @Test
  public void testGenerate() throws IOException, SAXException {
    File parcelableDescriptor = new File("testdata/com/facebook/buck/parcelable/student.xml");
    String observed = Generator.generateFromFile(parcelableDescriptor);
    String expected = Files.toString(
        new File("testdata/com/facebook/buck/parcelable/GeneratedStudent.java"), Charsets.UTF_8);
    assertEquals(expected, observed);
  }
}

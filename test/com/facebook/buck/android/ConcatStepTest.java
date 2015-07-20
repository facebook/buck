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

package com.facebook.buck.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;

public class ConcatStepTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testConcatFiles() throws IOException {
    // Create three files containing "foo", "bar", and "baz"
    // and see if they are correctly concatenated.
    File dest = temp.newFile();
    List<Path> inputs = new ArrayList<Path>();
    String[] fileContents = {"foo", "bar", "baz"};
    for (int i = 0; i < fileContents.length; i++) {
      File src = temp.newFile();
      PrintStream out = new PrintStream(src);
      out.print(fileContents[i]);
      inputs.add(src.toPath());
      out.close();
    }

    ConcatStep step = new ConcatStep(inputs, dest.toPath());
    // ConcatDexStep doesn't need an ExecutionContext, so we pass in null.
    step.execute(null);
    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(dest)));
    assertEquals(reader.readLine(), "foobarbaz");

    reader.close();

  }

}

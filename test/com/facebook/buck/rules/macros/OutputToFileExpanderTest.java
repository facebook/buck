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

package com.facebook.buck.rules.macros;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class OutputToFileExpanderTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void shouldTakeOutputFromOtherMacroAndOutputItToAFile()
      throws IOException, MacroException {
    File root = tmp.newFolder();

    ProjectFilesystem filesystem = new ProjectFilesystem(root.toPath());

    String text = "cheese" + File.pathSeparator + "peas";

    StringExpander source = new StringExpander(text);
    OutputToFileExpander expander = new OutputToFileExpander(source);
    BuildTarget target = BuildTargetFactory.newInstance("//some:example");
    String result = expander.expand(target, new BuildRuleResolver(), filesystem, "totally ignored");

    assertTrue(result, result.startsWith("@"));
    Path output = Paths.get(result.substring(1));
    // Because we're going to shovel this into a genrule
    assertTrue(output.isAbsolute());
    List <String> seen = Files.readAllLines(output, UTF_8);
    List<String> expected = ImmutableList.of(text);
    assertEquals(expected, seen);
  }

}

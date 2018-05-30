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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OutputToFileExpanderUtilsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void shouldTakeOutputFromOtherMacroAndOutputItToAFile() throws Exception {
    File root = tmp.newFolder();

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(root.toPath());

    String text = "cheese" + File.pathSeparator + "peas";

    StringExpander source = new StringExpander<>(Macro.class, StringArg.of(text));
    BuildTarget target = BuildTargetFactory.newInstance("//some:example");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> node = builder.build();
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(node));
    builder.build(graphBuilder, filesystem);

    MacroHandler handler = new MacroHandler(ImmutableMap.of("@macro", source));
    String result =
        handler.expand(
            target, createCellRoots(filesystem), graphBuilder, "$(@macro totally ignored)");

    assertTrue(result, result.startsWith("@"));
    Path output = Paths.get(result.substring(1));
    // Because we're going to shovel this into a genrule
    assertTrue(output.isAbsolute());
    List<String> seen = Files.readAllLines(output, UTF_8);
    List<String> expected = ImmutableList.of(text);
    assertEquals(expected, seen);
  }
}

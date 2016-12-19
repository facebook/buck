/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;

import com.google.common.io.Files;
import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

@RunWith(CompilerTreeApiTestRunner.class)
public class TreeBackedTypeElementTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private Trees trees;

  @Test
  public void testGetSimpleName() throws IOException {
    TypeElement javacElement = compile("public class Foo {}");
    ClassTree tree = trees.getTree(javacElement);
    TypeElement treesElement = new TreeBackedTypeElement(tree);

    assertEquals(javacElement.getSimpleName(), treesElement.getSimpleName());
    assertEquals(tree.getSimpleName(), treesElement.getSimpleName());
  }

  private TypeElement compile(String source) throws IOException {
    File sourceFile = tempFolder.newFile("Foo.java");
    Files.write(source, sourceFile, StandardCharsets.UTF_8);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> sourceObjects =
        fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));

    JavacTask task =
        (JavacTask) compiler.getTask(null, fileManager, null, null, null, sourceObjects);

    trees = Trees.instance(task);

    return (TypeElement) task.analyze().iterator().next();
  }
}

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

import com.sun.source.tree.ClassTree;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import javax.lang.model.element.TypeElement;

@RunWith(CompilerTreeApiTestRunner.class)
public class TreeBackedTypeElementTest extends CompilerTreeApiTest {

  @Test
  public void testGetSimpleName() throws IOException {
    compile("public class Foo {}");
    TypeElement javacElement = javacElements.getTypeElement("Foo");
    TypeElement treesElement = treesElements.getTypeElement("Foo");
    ClassTree tree = trees.getTree(javacElement);

    assertEquals(javacElement.getSimpleName(), treesElement.getSimpleName());
    assertEquals(tree.getSimpleName(), treesElement.getSimpleName());
  }

  @Test
  public void testGetQualifiedNameUnnamedPackage() throws IOException {
    compile("public class Foo {}");

    TypeElement javacElement = javacElements.getTypeElement("Foo");
    TypeElement treesElement = treesElements.getTypeElement("Foo");

    assertEquals(javacElement.getQualifiedName(), treesElement.getQualifiedName());
  }

}

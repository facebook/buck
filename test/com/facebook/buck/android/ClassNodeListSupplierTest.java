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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

public class ClassNodeListSupplierTest {

  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testOneJar() throws IOException {
    File jar = new File(tmpDir.getRoot(), "primary.jar");
    ZipOutputStream jarOut = new JarOutputStream(new FileOutputStream(jar));
    jarOut.putNextEntry(new JarEntry("com/facebook/buck/android/ClassNodeListSupplierTest.class"));
    writeClassBytes(ClassNodeListSupplierTest.class, jarOut);
    jarOut.close();

    Supplier<ImmutableList<ClassNode>> supplier = ClassNodeListSupplier.createMemoized(
        ImmutableList.of(jar.toPath()));
    ImmutableList<ClassNode> classNodes = supplier.get();

    assertEquals(1, classNodes.size());
    assertEquals(
        Type.getType(ClassNodeListSupplierTest.class).getInternalName(),
        classNodes.get(0).name);

    // Memoized should always return the same object
    assertSame(classNodes, supplier.get());
  }

  private void writeClassBytes(Class<?> type, OutputStream outputStream) throws IOException {
    String resourceName = type.getName().replace('.', '/') + ".class";
    InputStream inputStream = ClassLoader.getSystemResourceAsStream(resourceName);
    ByteStreams.copy(inputStream, outputStream);
  }
}

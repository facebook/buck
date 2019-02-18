/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.lang.psi;

import com.facebook.buck.intellij.ideabuck.lang.BcfgFile;
import com.facebook.buck.intellij.ideabuck.lang.BcfgFileType;
import com.intellij.psi.PsiFileFactory;
import com.intellij.testFramework.LightPlatformCodeInsightTestCase;

public class BcfgElementFactoryTest extends LightPlatformCodeInsightTestCase {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    PsiFileFactory fileFactory = PsiFileFactory.getInstance(getProject());
    assertNotNull(fileFactory);
  }

  public void testCreateProperty() {
    BcfgProperty property = BcfgElementFactory.createProperty(getProject(), "foo", "bar", "baz");
    assertEquals("foo", property.getSection().getName());
    assertEquals("bar", property.getName());
    assertEquals("baz", property.getValue());
  }

  public void testCreateSection() {
    BcfgSection section = BcfgElementFactory.createSection(getProject(), "foo");
    assertEquals("foo", section.getName());
  }

  public void testCreateFile() {
    BcfgFile file = BcfgElementFactory.createFile(getProject(), "[simple]\nexample=this");
    assertEquals(BcfgFileType.INSTANCE, file.getFileType());
  }
}

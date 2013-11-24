/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java.classes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class FileLikesTest {

  @Test
  public void testIsClassFile() {
    assertTrue(FileLikes.isClassFile(new FakeFileLike("com/example/Bar.class")));
    assertFalse(FileLikes.isClassFile(new FakeFileLike("com/example/Bar.txt")));
  }

  @Test(expected = NullPointerException.class)
  public void testIsClassFileRejectsNull() {
    assertTrue(FileLikes.isClassFile(null));
  }

  @Test
  public void testGetFileNameWithoutClassSuffix() {
    assertEquals("com/example/Bar",
        FileLikes.getFileNameWithoutClassSuffix(new FakeFileLike("com/example/Bar.class")));
    assertEquals("com/example/Foo$1",
        FileLikes.getFileNameWithoutClassSuffix(new FakeFileLike("com/example/Foo$1.class")));
  }

  private static class FakeFileLike implements FileLike {

    private final String relativePath;

    private FakeFileLike(String relativePath) {
      this.relativePath = Preconditions.checkNotNull(relativePath);
    }

    @Override
    public String getRelativePath() {
      return relativePath;
    }

    @Override
    public File getContainer() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getSize() {
      throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getInput() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public HashCode fastHash() throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}

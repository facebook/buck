/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ByteBufferReplacerTest {

  @Test
  public void charSets() {
    assumeThat(Charset.defaultCharset(), Matchers.equalTo(Charsets.UTF_8));

    String blob = "something \n \ud003\ud001hello/world \n something";
    String fixedBlob = "something \n \ud003\ud001replaced/// \n something";

    Path original = Paths.get("\ud003\ud001hello/world");
    Path replacement = Paths.get("\ud003\ud001replaced");

    ImmutableList<Charset> charSets =
        ImmutableList.of(
            Charsets.ISO_8859_1,
            Charsets.US_ASCII,
            Charsets.UTF_8,
            Charsets.UTF_16,
            Charsets.UTF_16BE,
            Charsets.UTF_16LE);

    for (Charset charset : charSets) {
      ByteBufferReplacer replacer = ByteBufferReplacer.fromPaths(
          ImmutableMap.of(original, replacement),
          File.separatorChar,
          charset);
      byte[] rawBytes = blob.getBytes(charset);
      int numReplacements = replacer.replace(ByteBuffer.wrap(rawBytes));
      assertEquals(String.format("Charset %s", charset), 1, numReplacements);
      assertArrayEquals(
          String.format(
              "Charset %s: \'%s\' != \'%s\'",
              charset,
              fixedBlob,
              new String(rawBytes, charset)),
          fixedBlob.getBytes(charset),
          rawBytes);
    }
  }

}

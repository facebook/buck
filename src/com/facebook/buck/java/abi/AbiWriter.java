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

package com.facebook.buck.java.abi;

import static javax.lang.model.SourceVersion.RELEASE_7;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

@SupportedSourceVersion(RELEASE_7)
@SupportedAnnotationTypes("*")
@SupportedOptions({AbiWriterProtocol.PARAM_ABI_OUTPUT_FILE})
public class AbiWriter extends AbstractProcessor {

  private SortedSet<String> classes = new TreeSet<>();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    RenderableTypes factory = new RenderableTypes();

    for (Element element : roundEnv.getRootElements()) {
      if (element instanceof TypeElement) {
        Renderable renderable = factory.deriveFor(element);
        StringBuilder builder = new StringBuilder();
        renderable.appendTo(builder);
        classes.add(builder.toString());
      } else if (element instanceof PackageElement) {
        // Only found in package-info classes and therefore do not contribute to the ABI.
        continue;
      } else {
        throw new RuntimeException("Unknown type: " + element.getKind());
      }
    }
    String destFile = processingEnv.getOptions().get(AbiWriterProtocol.PARAM_ABI_OUTPUT_FILE);
    if (destFile != null) {
      writeAbi(new File(destFile));
    }

    // We're not laying claim to any annotations.
    return false;
  }

  private void writeAbi(File file) {
    if (!file.getParentFile().exists()) {
      throw new IllegalArgumentException("Directory for ABI key does not exist: " + file);
    }

    if (file.exists() && !file.delete()) {
      throw new IllegalArgumentException("Unable to delete existing ABI key: " + file);
    }

    String key = computeAbiKey();
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
      out.write(key.getBytes());
      out.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public SortedSet<String> getSummaries() {
    return Collections.unmodifiableSortedSet(classes);
  }

  /**
   * Creates a SHA-1 hash from the ABI information extracted by this {@link AbiWriter}.
   */
  public String computeAbiKey() {
    SortedSet<String> summaries = getSummaries();
    return computeAbiKey(summaries);
  }

  static String computeAbiKey(SortedSet<String> summaries) {
    try {
      MessageDigest digest = MessageDigest.getInstance("sha-1");

      for (String summary : summaries) {
        // "2" is the number of bytes in a java character
        ByteBuffer buffer =
            ByteBuffer.allocate(summary.length() * 2).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < summary.length(); i++) {
          buffer.putChar(summary.charAt(i));
        }
        digest.update(buffer.array());
      }
      byte[] sha1Bytes = digest.digest();

      // This isn't a particularly fast operation. A quick test indicates that it's approximately
      // 3-4 times slower than "new BigInteger(1, sha1Bytes).toString(16)". It does, however, ensure
      // that the resulting string is always 40 characters long and padded with 0 if necessary.
      // To give an indication of speed, on my i7 mbp, 100k string generations takes ~450ms compared
      // to ~150ms. In short, the speed hit isn't going to be the end of the world for our use case.
      return String.format("%040x", new BigInteger(1, sha1Bytes));
    } catch (NoSuchAlgorithmException e) {
      // Note: if we get this we're on a broken JRE and we're not having fun.
      throw new RuntimeException(e);
    }
  }
}

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
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

@SupportedSourceVersion(RELEASE_7)
@SupportedAnnotationTypes("*")
public class AbiWriter extends AbstractProcessor {

  private static final String EMPTY_ABI_KEY = computeAbiKey(new TreeSet<String>());

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
      } else {
        throw new RuntimeException("Unknown type: " + element.getKind());
      }
    }

    String destFile = processingEnv.getOptions().get("buck.abi.file");
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
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(key))) {
      out.write(key.getBytes());
      out.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String getAbiKeyForEmptySources() {
    return EMPTY_ABI_KEY;
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

  private static String computeAbiKey(SortedSet<String> summaries) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");

      for (String summary : summaries) {
        digest.update(summary.getBytes("utf-8"));
      }
      byte[] sha1Bytes = digest.digest();

      return new BigInteger(1, sha1Bytes).toString(16);
    } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
      // Note: if we get either of these that we're on a broken JRE and we're not having fun.
      throw new RuntimeException(e);
    }
  }
}

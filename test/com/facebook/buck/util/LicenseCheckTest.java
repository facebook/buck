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
package com.facebook.buck.util;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
import org.junit.Test;

public class LicenseCheckTest {

  // Files where we're okay with the license not being the normal Facebook apache one. We also
  // exclude all files under "test/**/testdata/"
  private static final ImmutableSet<Path> NON_APACHE_LICENSE_WHITELIST =
      ImmutableSet.of(
          // Because it's not originally our code.
          Paths.get("src/com/facebook/buck/jvm/java/coverage/ReportGenerator.java"),
          Paths.get("src/com/facebook/buck/util/WindowsCreateProcessEscape.java"),
          Paths.get("test/com/facebook/buck/util/WindowsCreateProcessEscapeTest.java"),
          Paths.get("src/com/facebook/buck/util/xml/PositionalXmlHandler.java"));

  private static final ImmutableSet<Path> NON_APACHE_LICENSE_DIRS_WHITELIST =
      ImmutableSet.of(
          // Ignore the generated parsing files for the plugin
          Paths.get("tools/ideabuck/gen/"));

  @Test
  public void ensureAllSrcFilesHaveTheApacheLicense() throws IOException {
    Files.walkFileTree(Paths.get("src"), new JavaCopyrightVisitor(false));
    Files.walkFileTree(Paths.get("test"), new JavaCopyrightVisitor(true));
  }

  private static class JavaCopyrightVisitor extends SimpleFileVisitor<Path> {

    private static final Pattern LICENSE_FRAGMENT =
        Pattern.compile(
            // TODO(simons): This is very lame.
            // The newline character doesn't match "\w", "\\n" so do a non-greedy match until the
            // next part of the copyright.
            "^/\\\\*.*?"
                + "\\\\* Copyright 20\\d\\d-present Facebook, Inc\\..*?"
                + "\\\\* Licensed under the Apache License, Version 2.0 \\(the \"License\"\\); you may.*",
            Pattern.MULTILINE | Pattern.DOTALL);

    private static final Path TEST_DATA = Paths.get("testdata");

    private final boolean ignoreTestData;

    public JavaCopyrightVisitor(boolean ignoreTestData) {
      this.ignoreTestData = ignoreTestData;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      if (!"java".equals(MorePaths.getFileExtension(file))
          ||
          // Ignore dangling symlinks.
          !Files.exists(file)
          || NON_APACHE_LICENSE_WHITELIST.contains(file)) {
        return FileVisitResult.CONTINUE;
      }

      try {
        String asString = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        assertTrue("Check license of: " + file, LICENSE_FRAGMENT.matcher(asString).matches());
      } catch (IOException e) {
        fail("Unable to read: " + file);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      if (ignoreTestData && TEST_DATA.equals(dir.getFileName())) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      if (NON_APACHE_LICENSE_DIRS_WHITELIST.contains(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }
  }
}

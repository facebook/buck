/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.tools.documentation.generator.skylark;

import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.util.environment.Platform;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.Test;

public class SignatureCollectorTest {

  private static final Predicate<ClassInfo> CLASS_INFO_PREDICATE =
      classInfo ->
          classInfo
              .getPackageName()
              .startsWith("com.facebook.buck.tools.documentation.generator.skylark.signatures");

  @Test
  public void findsAtLeastSkylarkSignature() throws Exception {
    // ClassPath$Scanner has issues scanning classpath on Windows and results in
    // "The filename, directory name, or volume label syntax is incorrect" exception.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    assertThat(
        SignatureCollector.getSkylarkCallables(CLASS_INFO_PREDICATE)
            .map(SkylarkCallable::name)
            .collect(Collectors.toList()),
        contains("dummy"));
  }
}

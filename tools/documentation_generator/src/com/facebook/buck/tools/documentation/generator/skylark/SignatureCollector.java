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

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Class responsible discovering Skylark signature metadata in the classpath. */
public class SignatureCollector {
  /**
   * Returns a stream of signatures found in the current classpath.
   *
   * @param classInfoPredicate predicate to use in order to filter out classes that should not be
   *     loaded. It's best to make it as precise as possible to avoid expensive loading - checking
   *     for class name and package is ideal.
   */
  public static Stream<SkylarkSignature> getSkylarkSignatures(
      Predicate<ClassInfo> classInfoPredicate) throws IOException {
    return ClassPath.from(ClassPath.class.getClassLoader())
        .getAllClasses()
        .stream()
        .filter(classInfoPredicate)
        .map(ClassInfo::load)
        .flatMap(clazz -> Arrays.stream(clazz.getDeclaredFields()))
        .map(field -> field.getAnnotation(SkylarkSignature.class))
        .filter(Objects::nonNull);
  }
}

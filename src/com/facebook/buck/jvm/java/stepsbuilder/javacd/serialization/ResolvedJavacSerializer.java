/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization;

import static com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.SerializationUtil.createNotSupportedException;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.jvm.java.ExternalJavac;
import com.facebook.buck.jvm.java.JarBackedJavac;
import com.facebook.buck.jvm.java.JdkProvidedInMemoryJavac;
import com.facebook.buck.jvm.java.Jsr199Javac;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.protobuf.ProtocolStringList;
import java.util.List;

/** {@link ResolvedJavac} to protobuf serializer */
public class ResolvedJavacSerializer {

  private ResolvedJavacSerializer() {}

  /**
   * Serializes {@link ResolvedJavac} into javacd model's {@link
   * com.facebook.buck.javacd.model.ResolvedJavac}.
   */
  public static com.facebook.buck.javacd.model.ResolvedJavac serialize(ResolvedJavac javac) {
    com.facebook.buck.javacd.model.ResolvedJavac.Builder builder =
        com.facebook.buck.javacd.model.ResolvedJavac.newBuilder();

    boolean done = false;

    if (javac instanceof ExternalJavac.ResolvedExternalJavac) {
      ExternalJavac.ResolvedExternalJavac externalJavac =
          (ExternalJavac.ResolvedExternalJavac) javac;
      com.facebook.buck.javacd.model.ResolvedJavac.ExternalJavac.Builder externalJavacBuilder =
          com.facebook.buck.javacd.model.ResolvedJavac.ExternalJavac.newBuilder();

      externalJavacBuilder.setShortName(externalJavac.getShortName());
      for (String item : externalJavac.getCommandPrefix()) {
        externalJavacBuilder.addCommandPrefix(item);
      }

      builder.setExternalJavac(externalJavacBuilder.build());
      done = true;
    }

    if (!done && javac instanceof JarBackedJavac.ResolvedJarBackedJavac) {

      JarBackedJavac.ResolvedJarBackedJavac jarBackedJavac =
          (JarBackedJavac.ResolvedJarBackedJavac) javac;

      com.facebook.buck.javacd.model.ResolvedJavac.JarBackedJavac.Builder jarBackedJavacBuilder =
          com.facebook.buck.javacd.model.ResolvedJavac.JarBackedJavac.newBuilder();
      jarBackedJavacBuilder.setCompilerClassName(jarBackedJavac.getCompilerClassName());
      for (RelPath classpath : jarBackedJavac.getClasspath()) {
        jarBackedJavacBuilder.addResolvedClasspath(RelPathSerializer.serialize(classpath));
      }

      builder.setJarBackedJavac(jarBackedJavacBuilder.build());
      done = true;
    }

    if (!done && javac instanceof Jsr199Javac.ResolvedJsr199Javac) {
      builder.setJcr199Javac(
          com.facebook.buck.javacd.model.ResolvedJavac.JSR199Javac.getDefaultInstance());
      done = true;
    }

    if (!done) {
      throw new IllegalStateException(
          javac.getClass().getSimpleName() + " type is not implemented!");
    }

    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.javacd.model.ResolvedJavac} into {@link
   * ResolvedJavac}.
   */
  public static ResolvedJavac deserialize(com.facebook.buck.javacd.model.ResolvedJavac javac) {
    com.facebook.buck.javacd.model.ResolvedJavac.JavacCase javacCase = javac.getJavacCase();
    switch (javacCase) {
      case EXTERNALJAVAC:
        com.facebook.buck.javacd.model.ResolvedJavac.ExternalJavac externalJavac =
            javac.getExternalJavac();
        String shortName = externalJavac.getShortName();
        ImmutableList<String> commandPrefix = toImmutableList(externalJavac.getCommandPrefixList());

        return new ExternalJavac.ResolvedExternalJavac(shortName, commandPrefix);

      case JARBACKEDJAVAC:
        com.facebook.buck.javacd.model.ResolvedJavac.JarBackedJavac jarBackedJavac =
            javac.getJarBackedJavac();
        String compilerClassName = jarBackedJavac.getCompilerClassName();
        List<com.facebook.buck.javacd.model.RelPath> resolvedClasspathList =
            jarBackedJavac.getResolvedClasspathList();
        ImmutableSortedSet<RelPath> classpath =
            resolvedClasspathList.stream()
                .map(RelPathSerializer::deserialize)
                .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));

        return new JarBackedJavac.ResolvedJarBackedJavac(classpath, compilerClassName);

      case JCR199JAVAC:
        return JdkProvidedInMemoryJavac.createJsr199Javac();

      case JAVAC_NOT_SET:
      default:
        throw createNotSupportedException(javacCase);
    }
  }

  private static ImmutableList<String> toImmutableList(ProtocolStringList list) {
    return ImmutableList.copyOf(list);
  }
}

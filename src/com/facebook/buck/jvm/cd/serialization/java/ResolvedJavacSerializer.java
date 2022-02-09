/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.cd.serialization.java;

import com.facebook.buck.jvm.cd.serialization.SerializationUtil;
import com.facebook.buck.jvm.java.ExternalJavac;
import com.facebook.buck.jvm.java.JdkProvidedInMemoryJavac;
import com.facebook.buck.jvm.java.Jsr199Javac;
import com.facebook.buck.jvm.java.ResolvedJavac;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ProtocolStringList;

/** {@link ResolvedJavac} to protobuf serializer */
public class ResolvedJavacSerializer {

  private ResolvedJavacSerializer() {}

  /**
   * Serializes {@link ResolvedJavac} into javacd model's {@link
   * com.facebook.buck.cd.model.java.ResolvedJavac}.
   */
  public static com.facebook.buck.cd.model.java.ResolvedJavac serialize(ResolvedJavac javac) {
    var builder = com.facebook.buck.cd.model.java.ResolvedJavac.newBuilder();

    boolean done = false;

    if (javac instanceof ExternalJavac.ResolvedExternalJavac) {
      var externalJavac = (ExternalJavac.ResolvedExternalJavac) javac;
      var externalJavacBuilder =
          com.facebook.buck.cd.model.java.ResolvedJavac.ExternalJavac.newBuilder();

      externalJavacBuilder.setShortName(externalJavac.getShortName());
      for (String item : externalJavac.getCommandPrefix()) {
        externalJavacBuilder.addCommandPrefix(item);
      }

      builder.setExternalJavac(externalJavacBuilder.build());
      done = true;
    }

    if (!done && javac instanceof Jsr199Javac.ResolvedJsr199Javac) {
      builder.setJsr199Javac(
          com.facebook.buck.cd.model.java.ResolvedJavac.JSR199Javac.getDefaultInstance());
      done = true;
    }

    if (!done) {
      throw new IllegalStateException(
          javac.getClass().getSimpleName() + " type is not implemented!");
    }

    return builder.build();
  }

  /**
   * Deserializes javacd model's {@link com.facebook.buck.cd.model.java.ResolvedJavac} into {@link
   * ResolvedJavac}.
   */
  public static ResolvedJavac deserialize(com.facebook.buck.cd.model.java.ResolvedJavac javac) {
    var javacCase = javac.getJavacCase();
    switch (javacCase) {
      case EXTERNALJAVAC:
        var externalJavac = javac.getExternalJavac();
        String shortName = externalJavac.getShortName();
        ImmutableList<String> commandPrefix = toImmutableList(externalJavac.getCommandPrefixList());

        return new ExternalJavac.ResolvedExternalJavac(shortName, commandPrefix);

      case JSR199JAVAC:
        return JdkProvidedInMemoryJavac.createJsr199Javac();

      case JAVAC_NOT_SET:
      default:
        throw SerializationUtil.createNotSupportedException(javacCase);
    }
  }

  private static ImmutableList<String> toImmutableList(ProtocolStringList list) {
    return ImmutableList.copyOf(list);
  }
}

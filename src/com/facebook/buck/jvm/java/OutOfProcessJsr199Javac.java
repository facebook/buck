/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.message_ipc.Connection;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import javax.annotation.Nullable;

public abstract class OutOfProcessJsr199Javac implements Javac {
  private static final JavacVersion VERSION = JavacVersion.of("oop in memory");

  @Nullable private Connection<OutOfProcessJavacConnectionInterface> connection;

  @Override
  public JavacVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder("javac(oop) ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);
    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "javac(oop)";
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory javac(oop) may not be used externally");
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory javac(oop) may not be used externally");
  }

  public void setConnection(Connection<OutOfProcessJavacConnectionInterface> connection) {
    this.connection = connection;
  }

  public Connection<OutOfProcessJavacConnectionInterface> getConnection() {
    return Preconditions.checkNotNull(
        connection, "Cannot get connection before calling setConnection");
  }
}

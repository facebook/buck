/*
 * Copyright 2012-present Facebook, Inc.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.io.CharSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

public class FileLikeCharSource extends CharSource {
  private final FileLike fileLike;

  public FileLikeCharSource(FileLike fileLike) {
    this.fileLike = Preconditions.checkNotNull(fileLike);
  }

  @Override
  public Reader openStream() throws IOException {
    return new InputStreamReader(fileLike.getInput(), UTF_8);
  }
}

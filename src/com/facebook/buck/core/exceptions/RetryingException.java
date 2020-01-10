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

package com.facebook.buck.core.exceptions;

import java.io.IOException;
import java.util.List;

public class RetryingException extends IOException {
  public RetryingException(List<IOException> allExceptions) {
    super(generateMessage(allExceptions), allExceptions.get(allExceptions.size() - 1));
  }

  @Override
  public String toString() {
    Class<?> enclosingClass = getClass().getEnclosingClass();
    String name;
    if (enclosingClass != null) {
      name = enclosingClass.getName();
    } else {
      name = getClass().getName();
    }
    return String.format("%s{%s}", name, getMessage());
  }

  private static String generateMessage(List<IOException> exceptions) {
    StringBuilder builder = new StringBuilder();
    builder.append(
        String.format("Too many fails after %1$d retries. Exceptions:", exceptions.size()));
    for (int i = 0; i < exceptions.size(); ++i) {
      builder.append(String.format(" %d:[%s]", i, exceptions.get(i).toString()));
    }
    return builder.toString();
  }
}

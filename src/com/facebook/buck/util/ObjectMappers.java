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

package com.facebook.buck.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk7.Jdk7Module;

public class ObjectMappers {

  private ObjectMappers() {

  }

  public static ObjectMapper newDefaultInstance() {
    ObjectMapper mapper = new ObjectMapper();
    // Add support for serializing Guava collections.
    mapper.registerModule(new GuavaModule());
    // Add support for serializing Path and other JDK 7 objects.
    mapper.registerModule(new Jdk7Module());
    return mapper;
  }
}

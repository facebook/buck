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

package com.facebook.buck.parser;

import com.facebook.buck.parser.thrift.RemoteDaemonicParserState;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.HashSet;
import java.util.Set;

/** A ObjectInputStream that will deserialize only RemoteDaemonicParserState. */
public class ParserStateObjectInputStream extends ObjectInputStream {

  private Set<String> whitelist;

  public ParserStateObjectInputStream(InputStream inputStream) throws IOException {
    super(inputStream);

    whitelist = new HashSet<>();
    whitelist.add(RemoteDaemonicParserState.class.getName());
  }

  @Override
  protected Class<?> resolveClass(ObjectStreamClass desc)
      throws IOException, ClassNotFoundException {
    if (!whitelist.contains(desc.getName())) {
      throw new InvalidClassException(desc.getName(), "Can't deserialize this class");
    }
    return super.resolveClass(desc);
  }
}

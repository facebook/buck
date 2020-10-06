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

package com.facebook.buck.features.project.intellij.targetinfo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** A binary file format that maps module names to a set of target names. */
public class ModuleToTargetsBinaryFile extends HashFile<String, Set<String>> {
  private static final HashFile.Serializer<Set<String>> SERIALIZER =
      new HashFile.Serializer<Set<String>>() {
        @Override
        public void serialize(Set<String> value, DataOutput output) throws IOException {
          output.writeInt(value.size());
          for (String s : value) {
            output.writeUTF(s);
          }
        }

        @Override
        public Set<String> deserialize(DataInput input) throws IOException {
          int size = input.readInt();
          HashSet<String> s = new HashSet<>(size);
          for (int i = 0; i < size; i++) {
            s.add(input.readUTF());
          }
          return s;
        }
      };

  public ModuleToTargetsBinaryFile(Path file) {
    super(HashFile.STRING_SERIALIZER, SERIALIZER, file);
  }
}

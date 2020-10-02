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
import java.util.ArrayList;

/** Binary file format for TargetInfo. */
public class TargetInfoBinaryFile extends HashFile<String, TargetInfo> {
  private static final HashFile.Serializer<TargetInfo> SERIALIZER =
      new HashFile.Serializer<TargetInfo>() {
        @Override
        public void serialize(TargetInfo value, DataOutput output) throws IOException {
          output.writeUTF(value.intellijFilePath);
          output.writeUTF(value.intellijName);
          output.writeByte(value.intellijType == null ? -1 : value.intellijType.ordinal());
          output.writeByte(value.ruleType == null ? -1 : value.ruleType.ordinal());
          output.writeByte(value.moduleLanguage == null ? -1 : value.moduleLanguage.ordinal());
          if (value.generatedSources == null) {
            output.writeInt(-1);
          } else {
            output.writeInt(value.generatedSources.size());
            for (String s : value.generatedSources) {
              output.writeUTF(s);
            }
          }
        }

        @Override
        public TargetInfo deserialize(DataInput input) throws IOException {
          TargetInfo info = new TargetInfo();
          info.intellijFilePath = input.readUTF();
          info.intellijName = input.readUTF();
          byte intellijType = input.readByte();
          if (intellijType >= 0 && intellijType < TargetInfo.IntelliJType.values().length) {
            info.intellijType =
                intellijType == -1 ? null : TargetInfo.IntelliJType.values()[intellijType];
          }
          byte ruleType = input.readByte();
          if (ruleType >= 0 && ruleType < TargetInfo.BuckType.values().length) {
            info.ruleType = ruleType == -1 ? null : TargetInfo.BuckType.values()[ruleType];
          }
          byte moduleLanguage = input.readByte();
          if (moduleLanguage >= 0 && moduleLanguage < TargetInfo.ModuleLanguage.values().length) {
            info.moduleLanguage =
                moduleLanguage == -1 ? null : TargetInfo.ModuleLanguage.values()[moduleLanguage];
          }
          int generatedSourcesCount = input.readInt();
          if (generatedSourcesCount >= 0) {
            info.generatedSources = new ArrayList<>();
            for (int i = 0; i < generatedSourcesCount; i++) {
              info.generatedSources.add(input.readUTF());
            }
          }

          return info;
        }
      };

  public TargetInfoBinaryFile(Path file) {
    super(HashFile.STRING_SERIALIZER, SERIALIZER, file);
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.file;

import com.intellij.openapi.fileTypes.FileNameMatcherEx;
import com.intellij.openapi.fileTypes.FileTypeConsumer;
import com.intellij.openapi.fileTypes.FileTypeFactory;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtilRt;

public class BuckFileTypeFactory extends FileTypeFactory {

  @Override
  public void createFileTypes(FileTypeConsumer fileTypeConsumer) {
    fileTypeConsumer.consume(
        BuckFileType.INSTANCE, new FileNameMatcherEx() {
          @Override
          public String getPresentableString() {
            return BuckFileUtil.getBuildFileName();
          }

          @Override
          public boolean acceptsCharSequence(CharSequence fileName) {
            String buildFileName = BuckFileUtil.getBuildFileName();
            return StringUtilRt.endsWithIgnoreCase(fileName, buildFileName) ||
                Comparing.equal(fileName, buildFileName, true);
          }
        });
  }
}

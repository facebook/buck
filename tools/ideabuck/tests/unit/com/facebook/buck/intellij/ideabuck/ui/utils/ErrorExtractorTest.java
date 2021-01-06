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

package com.facebook.buck.intellij.ideabuck.ui.utils;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class ErrorExtractorTest {
  @Test
  public void testMultiErrors() {

    String str =
        "/Users/petru/project/buck-out/gen/java/com/buckorg/lib__soemthing__output"
            + "/something.jar"
            + "(com/buckorg/something/SomethingEvent.class):-1: warning: Cannot find annotation "
            + "method 'value()'"
            + " in type 'com.google.gson.annotations.SerializedName': class file for "
            + "com.google.gson.annotations.SerializedN"
            + "ame not found\n"
            + "\n"
            + "/Users/petru/someproject/buck-out/gen/java/com/onavo/Something/lib__Something__"
            + "output/Something.jar"
            + "(com/something/Something/SomethingEvent.class):-1: warning: Cannot find annotation "
            + "method 'value()' in type 'com.google.gson.annotations.SerializedName'\n"
            + "\n"
            + "/Users/petru/someproject/buck-out/gen/java/com/onavo/Something/lib__Something__"
            + "output/Something.jar"
            + "(com/something/Something/SomethingEvent.class):-1: warning: Cannot find annotation "
            + "method 'value()' in"
            + " type 'com.google.gson.annotations.SerializedName'\n"
            + "\n"
            + "/Users/petru/someproject/java/com/buckorg/analytics/IfooAnalyticsBatchUploader."
            + "java:73: error: "
            + "cannot find symbol\n"
            + "      UploadTask task = uploadTasks.removeFirst().something();\n"
            + "                                                 ^\n"
            + "  symbol:   method something()\n"
            + "  location: class com.buckorg.analytics.IfooAnalyticsBatchUploader.UploadTask\n"
            + "/Users/petru/someproject/java/com/buckorg/analytics/IfooSomethingUploader.java:"
            + "47: error: "
            + "cannot find symbol\n"
            + "        .build().missing();\n"
            + "                ^\n"
            + "  symbol:   method missing()\n"
            + "  location: class okhttp3.Request\n";

    ErrorExtractor errorExtractor = new ErrorExtractor(str);
    ImmutableList<CompilerErrorItem> errors = errorExtractor.getErrors();

    assertEquals(5, errors.size());
    int noErrors = 0;
    int noWarnings = 0;
    for (CompilerErrorItem errorItem : errors) {
      if (errorItem.getType() == CompilerErrorItem.Type.ERROR) {
        noErrors++;
      } else {
        noWarnings++;
      }
    }
    assertEquals(2, noErrors);
    assertEquals(3, noWarnings);
  }

  @Test
  public void testNoErrors() {
    String str =
        "Lore Ipsum foo bar no errors to be find here/User/petrumarius/foo."
            + "java:1234 error: this is not an error. Just some random text.";

    ErrorExtractor errorExtractor = new ErrorExtractor(str);
    ImmutableList<CompilerErrorItem> errors = errorExtractor.getErrors();

    assertEquals(0, errors.size());
  }

  @Test
  public void testColumn() {
    String err =
        "/Users/petru/someproject/java/com/buckorg/analytics/IfooAnalyticsBatch"
            + "Uploader.java:73: error: cannot find symbol\n"
            + "      UploadTask task = uploadTasks.removeFirst().something();\n"
            + "                                                 ^\n"
            + "  symbol:   method something()\n"
            + "  location: class com.buckorg.analytics.IfooAnalyticsBatchUploader.UploadTask\n";
    int column = 50;
    ErrorExtractor errorExtractor = new ErrorExtractor(err);
    ImmutableList<CompilerErrorItem> errors = errorExtractor.getErrors();

    assertEquals(1, errors.size());
    assertEquals(column, errors.get(0).getColumn());
  }
}

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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

public class IdbOutputParsingTest {

  private static final float EPSILON = 1e-6f;

  private static IdbOutputParsing.IdbResultCallback eventCallbackAddingEventsToList(
      List<Object> streamedObjects) {
    return new IdbOutputParsing.IdbResultCallback() {
      @Override
      public void handleTestResult(ImmutableIdbTestResult result) {
        streamedObjects.add(result);
      }

      @Override
      public void handleEndOfTests() {}
    };
  }

  @Test
  public void checkJsonParsingForTestResultObjects() throws IOException {
    // Get objects from txt file
    String output =
        "{\"bundleName\": \"FBActorKitTests\", \"className\": \"FBActorKitTests\", \"methodName\": \"testActorProfilePictureForActor\", \"logs\": [], \"duration\": 0.008090019226074219, \"passed\": true, \"crashed\": false, \"failureInfo\": {\"message\": \"\", \"file\": \"\", \"line\": 0}, \"activityLogs\": []}\n"
            + "{\"bundleName\": \"FBActorKitTests\", \"className\": \"FBActorKitTests\", \"methodName\": \"testActorProfilePictureForEvent\", \"logs\": [], \"duration\": 0.0002620220184326172, \"passed\": true, \"crashed\": false, \"failureInfo\": {\"message\": \"\", \"file\": \"\", \"line\": 0}, \"activityLogs\": []}\n"
            + "{\"bundleName\": \"FBActorKitTests\", \"className\": \"FBActorKitTests\", \"methodName\": \"testActorProfilePictureForGroup\", \"logs\": [], \"duration\": 0.00017404556274414062, \"passed\": true, \"crashed\": false, \"failureInfo\": {\"message\": \"\", \"file\": \"\", \"line\": 0}, \"activityLogs\": []}\n"
            + "{\"bundleName\": \"FBActorKitTests\", \"className\": \"FBActorKitTests\", \"methodName\": \"testActorProfilePictureForNilActor\", \"logs\": [], \"duration\": 0.00012302398681640625, \"passed\": true, \"crashed\": false, \"failureInfo\": {\"message\": \"\", \"file\": \"\", \"line\": 0}, \"activityLogs\": []}\n"
            + "{\"bundleName\": \"FBActorKitTests\", \"className\": \"FBActorKitTests\", \"methodName\": \"testActorProfilePictureForPage\", \"logs\": [], \"duration\": 0.00038802623748779297, \"passed\": false, \"crashed\": false, \"failureInfo\": {\"message\": \"((pictureType != FBActorProfilePictureTypePage) is true) failed\", \"file\": \"Libraries/FBActorKit/FBActorKitTests/FBActorKitTests.m\", \"line\": 58}, \"activityLogs\": []}\n"
            + "{\"bundleName\": \"FBActorKitTests\", \"className\": \"FBActorKitTests\", \"methodName\": \"testActorProfilePictureForPlace\", \"logs\": [], \"duration\": 0.0002869367599487306, \"passed\": true, \"crashed\": false, \"failureInfo\": {\"message\": \"\", \"file\": \"\", \"line\": 0}, \"activityLogs\": []}";
    String[] testResults = output.split("\n");
    ImmutableSet.Builder<ImmutableIdbTestResult> results = ImmutableSet.builder();
    for (String testResult : testResults) {
      results.add(
          ObjectMappers.READER.readValue(
              ObjectMappers.createParser(testResult.trim()), ImmutableIdbTestResult.class));
    }

    // Get the expected result
    ImmutableIdbFailureInfo empty = ImmutableIdbFailureInfo.of("", "", 0);
    ImmutableIdbFailureInfo failure =
        ImmutableIdbFailureInfo.of(
            "((pictureType != FBActorProfilePictureTypePage) is true) failed",
            "Libraries/FBActorKit/FBActorKitTests/FBActorKitTests.m",
            58);
    ImmutableSet<ImmutableIdbTestResult> expectedResult =
        ImmutableSet.of(
            ImmutableIdbTestResult.of(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForActor",
                new String[0],
                0.008090019226074219f,
                true,
                false,
                empty,
                new String[0]),
            ImmutableIdbTestResult.of(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForEvent",
                new String[0],
                0.0002620220184326172f,
                true,
                false,
                empty,
                new String[0]),
            ImmutableIdbTestResult.of(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForGroup",
                new String[0],
                0.00017404556274414062f,
                true,
                false,
                empty,
                new String[0]),
            ImmutableIdbTestResult.of(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForNilActor",
                new String[0],
                0.00012302398681640625f,
                true,
                false,
                empty,
                new String[0]),
            ImmutableIdbTestResult.of(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForPage",
                new String[0],
                0.00038802623748779297f,
                false,
                false,
                failure,
                new String[0]),
            ImmutableIdbTestResult.of(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForPlace",
                new String[0],
                0.0002869367599487306f,
                true,
                false,
                empty,
                new String[0]));

    assertEquals(results.build(), expectedResult);
  }

  @Test
  public void streamingSimpleSuccess() throws IOException {
    Path jsonPath = TestDataHelper.getTestDataDirectory(this).resolve("idb_output/testResults.txt");
    List<Object> streamedObjects = new ArrayList<>();
    try (Reader jsonReader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      IdbOutputParsing.streamOutputFromReader(
          jsonReader, eventCallbackAddingEventsToList(streamedObjects));
    }
    assertThat(streamedObjects, hasSize(6));

    Iterator<Object> iter = streamedObjects.iterator();
    Object nextStreamedObject;

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(ImmutableIdbTestResult.class));
    ImmutableIdbTestResult result = (ImmutableIdbTestResult) nextStreamedObject;
    assertThat(result.getBundleName(), equalTo("FBActorKitTests"));
    assertThat(result.getClassName(), equalTo("FBActorKitTests"));
    assertThat(result.getMethodName(), equalTo("testActorProfilePictureForActor"));
    assertThat(result.getLogs(), equalTo(new String[0]));
    assertThat(result.getDuration(), equalTo(0.008090019226074219f));
    assertThat(result.getPassed(), equalTo(true));
    assertThat(result.getCrashed(), equalTo(false));
    assertThat(result.getFailureInfo().getMessage(), equalTo(""));
    assertThat(result.getFailureInfo().getFile(), equalTo(""));
    assertThat(result.getFailureInfo().getLine(), equalTo(0));
    assertThat(result.getActivityLogs(), equalTo(new String[0]));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(ImmutableIdbTestResult.class));
    result = (ImmutableIdbTestResult) nextStreamedObject;
    assertThat(result.getBundleName(), equalTo("FBActorKitTests"));
    assertThat(result.getClassName(), equalTo("FBActorKitTests"));
    assertThat(result.getMethodName(), equalTo("testActorProfilePictureForEvent"));
    assertThat(result.getLogs(), equalTo(new String[0]));
    assertThat(result.getDuration(), equalTo(0.0002620220184326172f));
    assertThat(result.getPassed(), equalTo(true));
    assertThat(result.getCrashed(), equalTo(false));
    assertThat(result.getFailureInfo().getMessage(), equalTo(""));
    assertThat(result.getFailureInfo().getFile(), equalTo(""));
    assertThat(result.getFailureInfo().getLine(), equalTo(0));
    assertThat(result.getActivityLogs(), equalTo(new String[0]));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(ImmutableIdbTestResult.class));
    result = (ImmutableIdbTestResult) nextStreamedObject;
    assertThat(result.getBundleName(), equalTo("FBActorKitTests"));
    assertThat(result.getClassName(), equalTo("FBActorKitTests"));
    assertThat(result.getMethodName(), equalTo("testActorProfilePictureForGroup"));
    assertThat(result.getLogs(), equalTo(new String[0]));
    assertThat(result.getDuration(), equalTo(0.00017404556274414062f));
    assertThat(result.getPassed(), equalTo(true));
    assertThat(result.getCrashed(), equalTo(false));
    assertThat(result.getFailureInfo().getMessage(), equalTo(""));
    assertThat(result.getFailureInfo().getFile(), equalTo(""));
    assertThat(result.getFailureInfo().getLine(), equalTo(0));
    assertThat(result.getActivityLogs(), equalTo(new String[0]));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(ImmutableIdbTestResult.class));
    result = (ImmutableIdbTestResult) nextStreamedObject;
    assertThat(result.getBundleName(), equalTo("FBActorKitTests"));
    assertThat(result.getClassName(), equalTo("FBActorKitTests"));
    assertThat(result.getMethodName(), equalTo("testActorProfilePictureForNilActor"));
    assertThat(result.getLogs(), equalTo(new String[0]));
    assertThat(result.getDuration(), equalTo(0.00012302398681640625f));
    assertThat(result.getPassed(), equalTo(true));
    assertThat(result.getCrashed(), equalTo(false));
    assertThat(result.getFailureInfo().getMessage(), equalTo(""));
    assertThat(result.getFailureInfo().getFile(), equalTo(""));
    assertThat(result.getFailureInfo().getLine(), equalTo(0));
    assertThat(result.getActivityLogs(), equalTo(new String[0]));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(ImmutableIdbTestResult.class));
    result = (ImmutableIdbTestResult) nextStreamedObject;
    assertThat(result.getBundleName(), equalTo("FBActorKitTests"));
    assertThat(result.getClassName(), equalTo("FBActorKitTests"));
    assertThat(result.getMethodName(), equalTo("testActorProfilePictureForPage"));
    assertThat(result.getLogs(), equalTo(new String[0]));
    assertThat(result.getDuration(), equalTo(0.00038802623748779297f));
    assertThat(result.getPassed(), equalTo(false));
    assertThat(result.getCrashed(), equalTo(false));
    assertThat(
        result.getFailureInfo().getMessage(),
        equalTo("((pictureType != FBActorProfilePictureTypePage) is true) failed"));
    assertThat(
        result.getFailureInfo().getFile(),
        equalTo("Libraries/FBActorKit/FBActorKitTests/FBActorKitTests.m"));
    assertThat(result.getFailureInfo().getLine(), equalTo(58));
    assertThat(result.getActivityLogs(), equalTo(new String[0]));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(ImmutableIdbTestResult.class));
    result = (ImmutableIdbTestResult) nextStreamedObject;
    assertThat(result.getBundleName(), equalTo("FBActorKitTests"));
    assertThat(result.getClassName(), equalTo("FBActorKitTests"));
    assertThat(result.getMethodName(), equalTo("testActorProfilePictureForPlace"));
    assertThat(result.getLogs(), equalTo(new String[0]));
    assertThat(result.getDuration(), equalTo(0.0002869367599487306f));
    assertThat(result.getPassed(), equalTo(true));
    assertThat(result.getCrashed(), equalTo(false));
    assertThat(result.getFailureInfo().getMessage(), equalTo(""));
    assertThat(result.getFailureInfo().getFile(), equalTo(""));
    assertThat(result.getFailureInfo().getLine(), equalTo(0));
    assertThat(result.getActivityLogs(), equalTo(new String[0]));
  }

  @Test
  public void streamingEmptyReaderDoesNotCauseFailure() {
    List<Object> streamedObjects = new ArrayList<>();
    IdbOutputParsing.streamOutputFromReader(
        new StringReader(""), eventCallbackAddingEventsToList(streamedObjects));
    assertThat(streamedObjects, is(empty()));
  }
}

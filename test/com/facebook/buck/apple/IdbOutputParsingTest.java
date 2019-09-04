/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import org.junit.Test;

public class IdbOutputParsingTest {

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
    ImmutableIdbFailureInfo empty = new ImmutableIdbFailureInfo("", "", 0);
    ImmutableIdbFailureInfo failure =
        new ImmutableIdbFailureInfo(
            "((pictureType != FBActorProfilePictureTypePage) is true) failed",
            "Libraries/FBActorKit/FBActorKitTests/FBActorKitTests.m",
            58);
    ImmutableSet<ImmutableIdbTestResult> expectedResult =
        ImmutableSet.of(
            new ImmutableIdbTestResult(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForActor",
                new String[0],
                0.008090019226074219f,
                true,
                false,
                empty,
                new String[0]),
            new ImmutableIdbTestResult(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForEvent",
                new String[0],
                0.0002620220184326172f,
                true,
                false,
                empty,
                new String[0]),
            new ImmutableIdbTestResult(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForGroup",
                new String[0],
                0.00017404556274414062f,
                true,
                false,
                empty,
                new String[0]),
            new ImmutableIdbTestResult(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForNilActor",
                new String[0],
                0.00012302398681640625f,
                true,
                false,
                empty,
                new String[0]),
            new ImmutableIdbTestResult(
                "FBActorKitTests",
                "FBActorKitTests",
                "testActorProfilePictureForPage",
                new String[0],
                0.00038802623748779297f,
                false,
                false,
                failure,
                new String[0]),
            new ImmutableIdbTestResult(
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
}

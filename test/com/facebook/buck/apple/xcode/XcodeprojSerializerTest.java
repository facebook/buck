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

package com.facebook.buck.apple.xcode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class XcodeprojSerializerTest {

  @Test
  public void testEmptyProject() {
    PBXProject project = new PBXProject("TestProject");
    XcodeprojSerializer xcodeprojSerializer = new XcodeprojSerializer(
        new GidGenerator(ImmutableSet.<String>of()), project);
    NSDictionary rootObject = xcodeprojSerializer.toPlist();

    assertEquals(project.getGlobalID(), ((NSString) rootObject.get("rootObject")).getContent());

    NSDictionary objects = ((NSDictionary) rootObject.get("objects"));
    NSDictionary projectObject = (NSDictionary) objects.get(project.getGlobalID());

    String[] requiredKeys = {
        "mainGroup",
        "targets",
        "buildConfigurationList",
        "compatibilityVersion",
        "attributes",
    };

    for (String key : requiredKeys) {
        assertTrue(projectObject.containsKey(key));
    }
  }
}

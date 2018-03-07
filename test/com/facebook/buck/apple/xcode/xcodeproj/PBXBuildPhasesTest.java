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

package com.facebook.buck.apple.project_generator;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSString;
import com.facebook.buck.apple.xcode.GidGenerator;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class PBXBuildPhasesTest {
  private XcodeprojSerializer serializer;
  private PBXProject project;
  private PBXTarget target;

  @Before
  public void setUp() {
    project = new PBXProject("TestProject");
    serializer = new XcodeprojSerializer(new GidGenerator(), project);
    target = new PBXNativeTarget("TestTarget");
    project.getTargets().add(target);
  }

  @Test
  public void testCopyFilesBuildPhaseWithoutOptionalFields() {
    PBXCopyFilesBuildPhase copyPhase = makeCopyFilesPhase();
    target.getBuildPhases().add(copyPhase);

    NSDictionary projPlist = serializer.toPlist();
    NSDictionary copyPhaseDict = getObjectForGID(copyPhase.getGlobalID(), projPlist);

    assertNull(copyPhaseDict.get("name"));
    assertNull(copyPhaseDict.get("runOnlyForDeploymentPostprocessing"));
  }

  @Test
  public void testCopyFilesBuildPhaseWithNameAndDepoymentPostprocessing() {
    String copyPhaseName = "Test Phase Name";
    PBXCopyFilesBuildPhase copyPhase = makeCopyFilesPhase();
    copyPhase.setRunOnlyForDeploymentPostprocessing(Optional.of(Boolean.TRUE));
    copyPhase.setName(Optional.of(copyPhaseName));
    target.getBuildPhases().add(copyPhase);

    NSDictionary projPlist = serializer.toPlist();
    NSDictionary copyPhaseDict = getObjectForGID(copyPhase.getGlobalID(), projPlist);

    assertTrue(copyPhaseDict.get("name").equals(new NSString(copyPhaseName)));
    assertTrue(copyPhaseDict.get("runOnlyForDeploymentPostprocessing").equals(new NSNumber(1)));
  }

  private NSDictionary getObjectForGID(String gid, NSDictionary projPlist) {
    assertNotNull(projPlist);

    NSDictionary objects = (NSDictionary) projPlist.get("objects");
    assertNotNull(objects);

    NSDictionary object = (NSDictionary) objects.get(gid);
    assertNotNull(object);

    return object;
  }

  private PBXCopyFilesBuildPhase makeCopyFilesPhase() {
    CopyFilePhaseDestinationSpec.Builder destSpecBuilder = CopyFilePhaseDestinationSpec.builder();
    destSpecBuilder.setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS);
    return new PBXCopyFilesBuildPhase(destSpecBuilder.build());
  }
}

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSString;
import com.facebook.buck.apple.xcode.GidGenerator;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
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

    assertEquals(new NSString(copyPhaseName), copyPhaseDict.get("name"));
    assertEquals(new NSNumber(1), copyPhaseDict.get("runOnlyForDeploymentPostprocessing"));
  }

  @Test
  public void testShellScriptBuildPhase() {
    String phaseName = "Test Phase Name";
    String input = "$(SRCROOT)/test.md";
    String output = "$(SRCROOT)/test.html";
    String inputFileList = "$(SRCROOT)/test-extra-inputs.xcfilelist";
    String outputFileList = "$(SRCROOT)/test-extra-outputs.xcfilelist";
    String script = "echo 'test'";

    PBXShellScriptBuildPhase buildPhase = new PBXShellScriptBuildPhase();
    buildPhase.setRunOnlyForDeploymentPostprocessing(Optional.of(Boolean.TRUE));
    buildPhase.setName(Optional.of(phaseName));
    buildPhase.getInputPaths().add(input);
    buildPhase.getOutputPaths().add(output);
    buildPhase.getInputFileListPaths().add(inputFileList);
    buildPhase.getOutputFileListPaths().add(outputFileList);
    buildPhase.setShellScript(script);
    target.getBuildPhases().add(buildPhase);

    NSDictionary projPlist = serializer.toPlist();
    NSDictionary phaseDict = getObjectForGID(buildPhase.getGlobalID(), projPlist);

    assertEquals(new NSString(phaseName), phaseDict.get("name"));
    assertEquals(new NSNumber(1), phaseDict.get("runOnlyForDeploymentPostprocessing"));
    assertEquals(new NSArray(new NSString(input)), phaseDict.get("inputPaths"));
    assertEquals(new NSArray(new NSString(output)), phaseDict.get("outputPaths"));
    assertEquals(new NSArray(new NSString(inputFileList)), phaseDict.get("inputFileListPaths"));
    assertEquals(new NSArray(new NSString(outputFileList)), phaseDict.get("outputFileListPaths"));
    assertEquals(new NSString(script), phaseDict.get("shellScript"));
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

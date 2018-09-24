/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.json;

import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckEventBusForTests.CapturingConsoleEventListener;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

public class TargetCountVerificationParserDecoratorTest {

  private CapturingConsoleEventListener capturingConsoleEventListener;
  private Path path;
  private ProjectBuildFileParser parserMock;
  private ImmutableList<Map<String, Object>> rawTargets;
  private BuckEventBus eventBus;

  @Before
  public void setUp() {
    eventBus = BuckEventBusForTests.newInstance();
    capturingConsoleEventListener = new CapturingConsoleEventListener();
    eventBus.register(capturingConsoleEventListener);
    path = Paths.get("/foo/bar");
    parserMock = EasyMock.createMock(ProjectBuildFileParser.class);

    Map<String, Object> retMap1 = new HashMap<>();
    retMap1.put("a", "a");
    retMap1.put("b", "b");
    retMap1.put("c", "c");
    retMap1.put("d", "d");
    retMap1.put("e", "e");

    ArrayList<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
    list.add(retMap1);
    list.add(retMap1);
    list.add(retMap1);
    list.add(retMap1);
    list.add(retMap1);

    rawTargets = ImmutableList.copyOf(list);
  }

  private void assertWarningIsEmitted() {
    EasyMock.verify(parserMock);

    String expectedWarning =
        String.format(
            "Number of expanded targets - %1$d - in file %2$s exceeds the threshold of %3$d. This could result in really slow builds.",
            5, path.toString(), 3);

    assertThat(
        capturingConsoleEventListener.getLogMessages(), equalTo(singletonList(expectedWarning)));
  }

  private void assertWarningIsNotEmitted() {
    EasyMock.verify(parserMock);

    assertThat(capturingConsoleEventListener.getLogMessages().size(), equalTo(0));
  }

  private TargetCountVerificationParserDecorator newParserDelegate(int threshold) {
    return new TargetCountVerificationParserDecorator(parserMock, threshold, eventBus);
  }

  @Test
  public void givenTargetCountExceedingLimitWhenGetBuildFileManifestIsInvokedAWarningIsEmitted()
      throws Exception {
    EasyMock.expect(parserMock.getBuildFileManifest(path))
        .andReturn(toBuildFileManifest(this.rawTargets));

    TargetCountVerificationParserDecorator parserDelegate = newParserDelegate(3);
    EasyMock.replay(parserMock);
    parserDelegate.getBuildFileManifest(path);

    assertWarningIsEmitted();
  }

  private BuildFileManifest toBuildFileManifest(ImmutableList<Map<String, Object>> rawTargets) {
    return BuildFileManifest.of(
        rawTargets,
        ImmutableSortedSet.of(),
        ImmutableMap.of(),
        Optional.empty(),
        ImmutableList.of());
  }

  @Test
  public void
      givenTargetCountNotExceedingLimitWhenGetBuildFileManifestIsInvokedAWarningIsNotEmitted()
          throws Exception {
    EasyMock.expect(parserMock.getBuildFileManifest(path))
        .andReturn(toBuildFileManifest(rawTargets));

    TargetCountVerificationParserDecorator parserDelegate = newParserDelegate(6);
    EasyMock.replay(parserMock);
    parserDelegate.getBuildFileManifest(path);

    assertWarningIsNotEmitted();
  }

  @Test
  public void parserReportProfileCalled() throws Exception {
    TargetCountVerificationParserDecorator parserDelegate = newParserDelegate(6);
    parserMock.reportProfile();
    EasyMock.expectLastCall();
    EasyMock.replay(parserMock);
    parserDelegate.reportProfile();
    EasyMock.verify(parserMock);
  }

  @Test
  public void parserCloseCalled() throws Exception {
    TargetCountVerificationParserDecorator parserDelegate = newParserDelegate(6);
    parserMock.close();
    EasyMock.expectLastCall();
    EasyMock.replay(parserMock);
    parserDelegate.close();
    EasyMock.verify(parserMock);
  }
}

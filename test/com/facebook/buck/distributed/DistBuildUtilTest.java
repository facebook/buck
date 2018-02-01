/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.ConsoleEventSeverity;
import com.facebook.buck.event.ConsoleEvent;
import java.util.logging.Level;
import org.junit.Assert;
import org.junit.Test;

public class DistBuildUtilTest {
  @Test
  public void regularConsoleEventToDistBuildSlaveConsoleEvent() {
    ConsoleEvent fineEvent = ConsoleEvent.fine("fine message");
    ConsoleEvent infoEvent = ConsoleEvent.info("info message");
    ConsoleEvent warningEvent = ConsoleEvent.warning("warning message");
    ConsoleEvent severeEvent = ConsoleEvent.severe("severe message");

    BuildSlaveEvent fineSlaveEvent = DistBuildUtil.createBuildSlaveConsoleEvent(fineEvent, 42);
    Assert.assertEquals(fineSlaveEvent.getConsoleEvent().getMessage(), fineEvent.getMessage());
    Assert.assertEquals(fineSlaveEvent.getConsoleEvent().getSeverity(), ConsoleEventSeverity.INFO);
    Assert.assertEquals(fineSlaveEvent.getTimestampMillis(), 42);

    BuildSlaveEvent infoSlaveEvent = DistBuildUtil.createBuildSlaveConsoleEvent(infoEvent, 42);
    Assert.assertEquals(infoSlaveEvent.getConsoleEvent().getMessage(), infoEvent.getMessage());
    Assert.assertEquals(infoSlaveEvent.getConsoleEvent().getSeverity(), ConsoleEventSeverity.INFO);
    Assert.assertEquals(infoSlaveEvent.getTimestampMillis(), 42);

    BuildSlaveEvent warningSlaveEvent =
        DistBuildUtil.createBuildSlaveConsoleEvent(warningEvent, 42);
    Assert.assertEquals(
        warningSlaveEvent.getConsoleEvent().getMessage(), warningEvent.getMessage());
    Assert.assertEquals(
        warningSlaveEvent.getConsoleEvent().getSeverity(), ConsoleEventSeverity.WARNING);
    Assert.assertEquals(warningSlaveEvent.getTimestampMillis(), 42);

    BuildSlaveEvent severeSlaveEvent = DistBuildUtil.createBuildSlaveConsoleEvent(severeEvent, 42);
    Assert.assertEquals(severeSlaveEvent.getConsoleEvent().getMessage(), severeEvent.getMessage());
    Assert.assertEquals(
        severeSlaveEvent.getConsoleEvent().getSeverity(), ConsoleEventSeverity.SEVERE);
    Assert.assertEquals(severeSlaveEvent.getTimestampMillis(), 42);
  }

  @Test
  public void distBuildSlaveConsoleEventToRegularConsoleEvent() {
    BuildSlaveEvent slaveConsoleEvent = DistBuildUtil.createBuildSlaveConsoleEvent(21);
    slaveConsoleEvent.getConsoleEvent().setMessage("My message");
    slaveConsoleEvent.setTimestampMillis(0);

    slaveConsoleEvent.getConsoleEvent().setSeverity(ConsoleEventSeverity.INFO);
    ConsoleEvent consoleEvent = DistBuildUtil.createConsoleEvent(slaveConsoleEvent);
    Assert.assertTrue(
        consoleEvent.getMessage().endsWith(slaveConsoleEvent.getConsoleEvent().getMessage()));
    Assert.assertEquals(consoleEvent.getLevel(), Level.INFO);

    slaveConsoleEvent.getConsoleEvent().setSeverity(ConsoleEventSeverity.WARNING);
    consoleEvent = DistBuildUtil.createConsoleEvent(slaveConsoleEvent);
    Assert.assertTrue(
        consoleEvent.getMessage().endsWith(slaveConsoleEvent.getConsoleEvent().getMessage()));
    Assert.assertEquals(consoleEvent.getLevel(), Level.WARNING);

    slaveConsoleEvent.getConsoleEvent().setSeverity(ConsoleEventSeverity.SEVERE);
    consoleEvent = DistBuildUtil.createConsoleEvent(slaveConsoleEvent);
    Assert.assertTrue(
        consoleEvent.getMessage().endsWith(slaveConsoleEvent.getConsoleEvent().getMessage()));
    Assert.assertEquals(consoleEvent.getLevel(), Level.SEVERE);
  }
}

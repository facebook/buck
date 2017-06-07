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

import com.facebook.buck.distributed.thrift.BuildSlaveConsoleEvent;
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

    BuildSlaveConsoleEvent fineSlaveEvent =
        DistBuildUtil.createBuildSlaveConsoleEvent(fineEvent, 42);
    Assert.assertEquals(fineSlaveEvent.getMessage(), fineEvent.getMessage());
    Assert.assertEquals(fineSlaveEvent.getSeverity(), ConsoleEventSeverity.INFO);
    Assert.assertEquals(fineSlaveEvent.getTimestampMillis(), 42);

    BuildSlaveConsoleEvent infoSlaveEvent =
        DistBuildUtil.createBuildSlaveConsoleEvent(infoEvent, 42);
    Assert.assertEquals(infoSlaveEvent.getMessage(), infoEvent.getMessage());
    Assert.assertEquals(infoSlaveEvent.getSeverity(), ConsoleEventSeverity.INFO);
    Assert.assertEquals(infoSlaveEvent.getTimestampMillis(), 42);

    BuildSlaveConsoleEvent warningSlaveEvent =
        DistBuildUtil.createBuildSlaveConsoleEvent(warningEvent, 42);
    Assert.assertEquals(warningSlaveEvent.getMessage(), warningEvent.getMessage());
    Assert.assertEquals(warningSlaveEvent.getSeverity(), ConsoleEventSeverity.WARNING);
    Assert.assertEquals(warningSlaveEvent.getTimestampMillis(), 42);

    BuildSlaveConsoleEvent severeSlaveEvent =
        DistBuildUtil.createBuildSlaveConsoleEvent(severeEvent, 42);
    Assert.assertEquals(severeSlaveEvent.getMessage(), severeEvent.getMessage());
    Assert.assertEquals(severeSlaveEvent.getSeverity(), ConsoleEventSeverity.SEVERE);
    Assert.assertEquals(severeSlaveEvent.getTimestampMillis(), 42);
  }

  @Test
  public void distBuildSlaveConsoleEventToRegularConsoleEvent() {
    BuildSlaveConsoleEvent slaveConsoleEvent = new BuildSlaveConsoleEvent();
    slaveConsoleEvent.setMessage("My message");
    slaveConsoleEvent.setTimestampMillis(0);

    slaveConsoleEvent.setSeverity(ConsoleEventSeverity.INFO);
    ConsoleEvent consoleEvent = DistBuildUtil.createConsoleEvent(slaveConsoleEvent);
    Assert.assertTrue(consoleEvent.getMessage().endsWith(slaveConsoleEvent.getMessage()));
    Assert.assertEquals(consoleEvent.getLevel(), Level.INFO);

    slaveConsoleEvent.setSeverity(ConsoleEventSeverity.WARNING);
    consoleEvent = DistBuildUtil.createConsoleEvent(slaveConsoleEvent);
    Assert.assertTrue(consoleEvent.getMessage().endsWith(slaveConsoleEvent.getMessage()));
    Assert.assertEquals(consoleEvent.getLevel(), Level.WARNING);

    slaveConsoleEvent.setSeverity(ConsoleEventSeverity.SEVERE);
    consoleEvent = DistBuildUtil.createConsoleEvent(slaveConsoleEvent);
    Assert.assertTrue(consoleEvent.getMessage().endsWith(slaveConsoleEvent.getMessage()));
    Assert.assertEquals(consoleEvent.getLevel(), Level.SEVERE);
  }
}

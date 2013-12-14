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

package com.facebook.buck.rules;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.test.TestResults;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;

public abstract class TestRunEvent extends AbstractBuckEvent {

  private final int secret;

  private TestRunEvent(int secret) {
    this.secret = secret;
  }

  public static Started started(boolean isRunAllTests, List<String> targets) {
    return new Started(targets.hashCode(), isRunAllTests, Preconditions.checkNotNull(targets));
  }

  public static Finished finished(List<String> targets, List<TestResults> completedResults) {
    return new Finished(targets.hashCode(), Preconditions.checkNotNull(completedResults));
  }

  @Override
  public boolean eventsArePair(BuckEvent event) {
    if (!(event instanceof TestRunEvent)) {
      return false;
    }

    return this.secret == ((TestRunEvent) event).secret &&
        !Objects.equals(getClass(), event.getClass());
  }

  public static class Started extends TestRunEvent {
    private final boolean runAllTests;
    private final ImmutableList<String> targetNames;

    public Started(int secret, boolean runAllTests, List<String> targetNames) {
      super(secret);
      this.runAllTests = runAllTests;
      this.targetNames = ImmutableList.copyOf(Preconditions.checkNotNull(targetNames));
    }

    public boolean isRunAllTests() {
      return runAllTests;
    }

    public ImmutableList<String> getTargetNames() {
      return targetNames;
    }

    @Override
    public String getEventName() {
      return "RunStarted";
    }

    @Override
    protected String getValueString() {
      return String.format("%d test targets", targetNames.size());
    }
  }

  public static class Finished extends TestRunEvent {

    private final List<TestResults> completedResults;

    public Finished(int secret, List<TestResults> completedResults) {
      super(secret);
      this.completedResults = Preconditions.checkNotNull(completedResults);
    }

    @Override
    public String getEventName() {
      return "RunComplete";
    }

    @Override
    protected String getValueString() {
      return completedResults.toString();
    }

    public List<TestResults> getResults() {
      return completedResults;
    }
  }
}

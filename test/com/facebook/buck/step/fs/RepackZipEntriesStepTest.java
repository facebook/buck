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

package com.facebook.buck.step.fs;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import org.junit.Test;

import java.io.File;
import java.util.Iterator;

public class RepackZipEntriesStepTest {

  @Test
  public void testProvidesAppropriateSubCommands() {
    final String inApk = "source.apk";
    final String outApk = "dest.apk";
    final int compressionLevel = 8;
    final ImmutableSet<String> entries = ImmutableSet.of("resources.arsc");
    final File dir = new File("/tmp/mydir");

    ExecutionContext context = createMock(ExecutionContext.class);
    expect(context.getVerbosity()).andReturn(Verbosity.ALL).times(2);
    replay(context);

    String unzipExpected = Joiner.on(" ").join(new ImmutableList.Builder<String>()
        .add("unzip")
        .add("-o")
        .add("-d").add(dir.getPath())
        .add(inApk)
        .addAll(entries)
        .build());

    ImmutableList<String> zipExpected = new ImmutableList.Builder<String>()
        .add("zip")
        .add("-X")
        .add("-r")
        .add("-"+compressionLevel)
        .add(new File(outApk).getAbsolutePath())
        .addAll(entries)
        .build();

    RepackZipEntriesStep command = new RepackZipEntriesStep(
        inApk,
        outApk,
        entries,
        compressionLevel,
        dir);

    // Go over the subcommands.
    Iterator<Step> iter = Iterators.filter(command.iterator(), Step.class);

    // First entries are unzipped.
    UnzipStep unzipStep = (UnzipStep) iter.next();
    assertEquals(unzipExpected, unzipStep.getDescription(context));

    // A copy of the archive would be created.
    CopyStep copyStep = (CopyStep) iter.next();

    assertEquals(inApk, copyStep.getSource());
    assertEquals(outApk, copyStep.getDestination());
    assertFalse(copyStep.isRecursive());

    // And then the entries would be zipped back in.
    ZipStep zipStep = (ZipStep) iter.next();
    assertEquals(zipExpected, zipStep.getShellCommand(context));

    //ShellStep zipCommand = iter.next();
    assertEquals(zipStep.getWorkingDirectory(), dir);

    verify(context);
  }
}

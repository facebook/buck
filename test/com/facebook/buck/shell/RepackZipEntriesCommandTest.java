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

package com.facebook.buck.shell;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import org.junit.Test;

import java.io.File;
import java.util.Iterator;
import java.util.List;

public class RepackZipEntriesCommandTest {

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

    List<String> unzipExpected = new ImmutableList.Builder<String>()
        .add("unzip")
        .add("-o")
        .add("-d").add(dir.getPath())
        .add(inApk)
        .addAll(entries)
        .build();

    List<String> copyExpected = ImmutableList.of(
        "cp",
        inApk,
        outApk);

    List<String> zipExpected = new ImmutableList.Builder<String>()
        .add("zip")
        .add("-X")
        .add("-r")
        .add("-"+compressionLevel)
        .add(new File(outApk).getAbsolutePath())
        .addAll(entries)
        .build();

    RepackZipEntriesCommand command = new RepackZipEntriesCommand(
        inApk,
        outApk,
        entries,
        compressionLevel,
        dir);

    // Go over the subcommands.
    Iterator<ShellCommand> iter = Iterators.filter(command.iterator(),
        ShellCommand.class);

    // First entries are unzipped.
    MoreAsserts.assertListEquals(unzipExpected, iter.next().getShellCommandInternal(context));

    // A copy of the archive would be created.
    MoreAsserts.assertListEquals(copyExpected, iter.next().getShellCommandInternal(context));

    // And then the entries would be zipped back in.
    ShellCommand zipCommand = iter.next();
    MoreAsserts.assertListEquals(zipExpected, zipCommand.getShellCommandInternal(context));
    assertEquals(zipCommand.getWorkingDirectory(), dir);

    verify(context);
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeDepFileBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class DependencyFileRuleKeyFactoryTest {

  @Test
  public void testKeysWhenInputPathContentsChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver = newRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    SourcePath usedSourcePath = new PathSourcePath(filesystem, Paths.get("usedInput"));
    SourcePath unusedSourcePath = new PathSourcePath(filesystem, Paths.get("unusedInput"));
    SourcePath noncoveredSourcePath = new PathSourcePath(filesystem, Paths.get("noncoveredInput"));
    SourcePath interestingSourcePath = new PathSourcePath(filesystem, Paths.get("interestingIn"));

    testKeysWhenInputContentsChanges(
        ruleFinder,
        pathResolver,
        usedSourcePath,
        unusedSourcePath,
        noncoveredSourcePath,
        interestingSourcePath,
        pathResolver.getAbsolutePath(usedSourcePath),
        pathResolver.getAbsolutePath(unusedSourcePath),
        pathResolver.getAbsolutePath(noncoveredSourcePath),
        pathResolver.getAbsolutePath(interestingSourcePath),
        DependencyFileEntry.fromSourcePath(usedSourcePath, pathResolver));
  }

  @Test
  public void testKeysWhenInputTargetOutputChanges() throws Exception {
    BuildRuleResolver ruleResolver = newRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    BuildTarget usedTarget = BuildTargetFactory.newInstance("//:used");
    BuildTarget unusedTarget = BuildTargetFactory.newInstance("//:unused");
    BuildTarget noncoveredTarget = BuildTargetFactory.newInstance("//:noncovered");
    BuildTarget interestingTarget = BuildTargetFactory.newInstance("//:interesting");
    SourcePath usedSourcePath = new BuildTargetSourcePath(usedTarget);
    SourcePath unusedSourcePath = new BuildTargetSourcePath(unusedTarget);
    SourcePath noncoveredSourcePath = new BuildTargetSourcePath(noncoveredTarget);
    SourcePath interestingSourcePath = new BuildTargetSourcePath(interestingTarget);
    ruleResolver.addToIndex(new FakeBuildRule(usedTarget, pathResolver).setOutputFile("used"));
    ruleResolver.addToIndex(new FakeBuildRule(unusedTarget, pathResolver).setOutputFile("unused"));
    ruleResolver.addToIndex(new FakeBuildRule(noncoveredTarget, pathResolver).setOutputFile("nc"));
    ruleResolver.addToIndex(new FakeBuildRule(interestingTarget, pathResolver).setOutputFile("in"));

    testKeysWhenInputContentsChanges(
        ruleFinder,
        pathResolver,
        usedSourcePath,
        unusedSourcePath,
        noncoveredSourcePath,
        interestingSourcePath,
        pathResolver.getAbsolutePath(usedSourcePath),
        pathResolver.getAbsolutePath(unusedSourcePath),
        pathResolver.getAbsolutePath(noncoveredSourcePath),
        pathResolver.getAbsolutePath(interestingSourcePath),
        DependencyFileEntry.fromSourcePath(usedSourcePath, pathResolver));
  }

  @Test
  public void testKeysWhenInputArchiveMemberChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver = newRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    SourcePath archivePath = new PathSourcePath(filesystem, Paths.get("archive"));
    SourcePath usedSourcePath = new ArchiveMemberSourcePath(archivePath, Paths.get("used"));
    SourcePath unusedSourcePath = new ArchiveMemberSourcePath(archivePath, Paths.get("unused"));
    SourcePath noncoveredSourcePath = new ArchiveMemberSourcePath(archivePath, Paths.get("nc"));
    SourcePath interestingSourcePath =
        new ArchiveMemberSourcePath(archivePath, Paths.get("META-INF"));

    testKeysWhenInputContentsChanges(
        ruleFinder,
        pathResolver,
        usedSourcePath,
        unusedSourcePath,
        noncoveredSourcePath,
        interestingSourcePath,
        Paths.get(pathResolver.getAbsoluteArchiveMemberPath(usedSourcePath).toString()),
        Paths.get(pathResolver.getAbsoluteArchiveMemberPath(unusedSourcePath).toString()),
        Paths.get(pathResolver.getAbsoluteArchiveMemberPath(noncoveredSourcePath).toString()),
        Paths.get(pathResolver.getAbsoluteArchiveMemberPath(interestingSourcePath).toString()),
        DependencyFileEntry.fromSourcePath(usedSourcePath, pathResolver));
  }

  /**
   * Tests all types of changes (or the lack of it): used, unused, noncovered.
   */
  private void testKeysWhenInputContentsChanges(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      SourcePath usedSourcePath,
      SourcePath unusedSourcePath,
      SourcePath noncoveredSourcePath,
      SourcePath interestingSourcePath,
      Path usedAbsolutePath,
      Path unusedAbsolutePath,
      Path noncoveredAbsolutePath,
      Path interestingAbsolutePath,
      DependencyFileEntry usedDepFileEntry
  ) throws Exception {
    testDepFileRuleKeyWhenInputContentsChanges(
        ruleFinder,
        pathResolver,
        usedSourcePath,
        unusedSourcePath,
        noncoveredSourcePath,
        interestingSourcePath,
        usedAbsolutePath,
        unusedAbsolutePath,
        noncoveredAbsolutePath,
        interestingAbsolutePath,
        usedDepFileEntry);
    testManifestKeyWhenInputContentsChanges(
        ruleFinder,
        pathResolver,
        unusedSourcePath,
        noncoveredSourcePath,
        interestingSourcePath,
        unusedAbsolutePath,
        noncoveredAbsolutePath,
        interestingAbsolutePath);
  }

  /**
   * Tests all types of changes (or the lack of it): used, unused, noncovered.
   */
  private void testDepFileRuleKeyWhenInputContentsChanges(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      SourcePath usedSourcePath,
      SourcePath unusedSourcePath,
      SourcePath noncoveredSourcePath,
      SourcePath interestingSourcePath,
      Path usedAbsolutePath,
      Path unusedAbsolutePath,
      Path noncoveredAbsolutePath,
      Path interestingAbsolutePath,
      DependencyFileEntry usedDepFileEntry
  ) throws Exception {
    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.empty(), // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when none of the inputs changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(usedSourcePath, unusedSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when none of the inputs changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(205),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.empty(), // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when only dep-file-covered but unused inputs change.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(205),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(usedSourcePath, unusedSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when only dep-file-covered but unused inputs change.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(105),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.empty(), // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a dep-file-covered and used input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(105),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(usedSourcePath, unusedSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a dep-file-covered and used input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(usedSourcePath, unusedSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a not dep-file-covered input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            interestingSourcePath),
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(205),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.empty(), // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when a covered but unused input is added/removed.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            interestingSourcePath),
        ImmutableList.of(
            usedSourcePath,
            unusedSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a not dep-file-covered input is added/removed.");

    if (interestingSourcePath instanceof ArchiveMemberSourcePath) { // TODO(plamenko)
      testDepFileRuleKey(
          ruleFinder,
          pathResolver,
          ImmutableList.of(
              usedSourcePath,
              unusedSourcePath,
              noncoveredSourcePath),
          ImmutableList.of(
              usedSourcePath,
              unusedSourcePath,
              noncoveredSourcePath,
              interestingSourcePath),
          ImmutableMap.of(// before
              usedAbsolutePath, HashCode.fromInt(100),
              unusedAbsolutePath, HashCode.fromInt(200),
              noncoveredAbsolutePath, HashCode.fromInt(300)),
          ImmutableMap.of(// after
              usedAbsolutePath, HashCode.fromInt(100),
              unusedAbsolutePath, HashCode.fromInt(200),
              noncoveredAbsolutePath, HashCode.fromInt(305),
              interestingAbsolutePath, HashCode.fromInt(400)),
          Optional.of(ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)),
          ImmutableSet.of(interestingSourcePath),
          ImmutableList.of(usedDepFileEntry),
          false, // rule key should change
          ImmutableSet.of(usedSourcePath),
          "Dep file rule key should change when a mandatory input is added/removed.");
    }

    try {
      testDepFileRuleKey(
          ruleFinder,
          pathResolver,
          ImmutableList.of(
              usedSourcePath,
              unusedSourcePath,
              noncoveredSourcePath,
              interestingSourcePath),
          ImmutableMap.of(// before
              usedAbsolutePath, HashCode.fromInt(100),
              unusedAbsolutePath, HashCode.fromInt(200),
              noncoveredAbsolutePath, HashCode.fromInt(300),
              interestingAbsolutePath, HashCode.fromInt(400)),
          ImmutableMap.of(// after
              usedAbsolutePath, HashCode.fromInt(100),
              unusedAbsolutePath, HashCode.fromInt(200),
              noncoveredAbsolutePath, HashCode.fromInt(300),
              interestingAbsolutePath, HashCode.fromInt(400)),
          Optional.of(ImmutableSet.of(unusedSourcePath)), // "used" is not accounted as possible
          ImmutableSet.of(interestingSourcePath),
          ImmutableList.of(usedDepFileEntry),
          true, // rule key should not change
          ImmutableSet.of(usedSourcePath),
          "should throw");
      Assert.fail("Unaccounted inputs should cause an exception.");
    } catch (NoSuchFileException e) { // NOPMD
      // expected
    }
  }

  /**
   * Tests SourcePaths both directly, and when wrapped with a RuleKeyAppendable.
   */
  private void testDepFileRuleKey(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValue,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Optional<ImmutableSet<SourcePath>> possibleInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      ImmutableList<DependencyFileEntry> depFileEntries,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage) throws Exception {
    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        fieldValue, // same field value before and after
        fieldValue, // same field value before and after
        hashesBefore,
        hashesAfter,
        possibleInputPaths,
        interestingInputPaths,
        depFileEntries,
        expectSameKeys,
        expectedDepFileInputsAfter,
        failureMessage);
  }
  private void testDepFileRuleKey(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValueBefore,
      Object fieldValueAfter,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Optional<ImmutableSet<SourcePath>> possibleInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      ImmutableList<DependencyFileEntry> depFileEntries,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage) throws Exception {
    testDepFileRuleKeyImpl(
        ruleFinder,
        pathResolver,
        fieldValueBefore,
        fieldValueAfter,
        hashesBefore,
        hashesAfter,
        possibleInputPaths,
        interestingInputPaths,
        depFileEntries,
        expectSameKeys,
        expectedDepFileInputsAfter,
        failureMessage);
    // make sure the behavior is same if wrapped with RuleKeyAppendable
    testDepFileRuleKeyImpl(
        ruleFinder,
        pathResolver,
        new RuleKeyAppendableWrapped(fieldValueBefore),
        new RuleKeyAppendableWrapped(fieldValueAfter),
        hashesBefore,
        hashesAfter,
        possibleInputPaths,
        interestingInputPaths,
        depFileEntries,
        expectSameKeys,
        expectedDepFileInputsAfter,
        failureMessage);
  }

  private void testDepFileRuleKeyImpl(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValueBefore,
      Object fieldValueAfter,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Optional<ImmutableSet<SourcePath>> possibleInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      ImmutableList<DependencyFileEntry> depFileEntries,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage) throws Exception {
    FakeDepFileBuildRule rule1 = new FakeDepFileBuildRule("//:rule") {
      @AddToRuleKey
      final Object myField = fieldValueBefore;
    };
    rule1.setPossibleInputPaths(possibleInputPaths);
    rule1.setInterestingInputPaths(interestingInputPaths);
    FakeFileHashCache hashCache = new FakeFileHashCache(hashesBefore, true, ImmutableMap.of());
    Pair<RuleKey, ImmutableSet<SourcePath>> res1 =
        new DefaultDependencyFileRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(rule1, depFileEntries);

    FakeDepFileBuildRule rule2 = new FakeDepFileBuildRule("//:rule") {
      @AddToRuleKey
      final Object myField = fieldValueAfter;
    };
    rule2.setPossibleInputPaths(possibleInputPaths);
    rule2.setInterestingInputPaths(interestingInputPaths);
    hashCache = new FakeFileHashCache(hashesAfter, true, ImmutableMap.of());
    Pair<RuleKey, ImmutableSet<SourcePath>> res2 =
        new DefaultDependencyFileRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(rule2, depFileEntries);

    if (expectSameKeys) {
      assertThat(failureMessage, res2.getFirst(), Matchers.equalTo(res1.getFirst()));
    } else {
      assertThat(failureMessage, res2.getFirst(), Matchers.not(Matchers.equalTo(res1.getFirst())));
    }
    assertThat(res2.getSecond(), Matchers.equalTo(expectedDepFileInputsAfter));
  }

  /**
   * Tests all types of changes (or the lack of it): used, unused, noncovered.
   */
  private void testManifestKeyWhenInputContentsChanges(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      SourcePath possibleSourcePath,
      SourcePath noncoveredSourcePath,
      SourcePath interestingSourcePath,
      Path possibleAbsolutePath,
      Path noncoveredAbsolutePath,
      Path interestingAbsolutePath) throws Exception {
    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            possibleSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            possibleAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.empty(), // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(possibleSourcePath, interestingSourcePath),
        "Manifest key should NOT change when none of the inputs changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            possibleSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            possibleAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(possibleSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(possibleSourcePath),
        "Manifest key should NOT change when none of the inputs changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            possibleSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            possibleAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(105),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.empty(), // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(possibleSourcePath, interestingSourcePath),
        "Manifest key should NOT change when only dep-file-covered inputs change.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            possibleSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            possibleAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(105),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(possibleSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(possibleSourcePath),
        "Manifest key should NOT change when only dep-file-covered but unused inputs change.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            possibleSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            possibleAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(possibleSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        false, // rule key should change
        ImmutableSet.of(possibleSourcePath),
        "Manifest key should change when a not dep-file-covered input changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableList.of(
            possibleSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(105),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(possibleSourcePath, interestingSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(possibleSourcePath, interestingSourcePath),
        "Manifest key should NOT change when only dep-file-covered inputs are added/removed.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            possibleSourcePath,
            interestingSourcePath),
        ImmutableList.of(
            possibleSourcePath,
            noncoveredSourcePath,
            interestingSourcePath),
        ImmutableMap.of(// before
            possibleAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of(// after
            possibleAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        Optional.of(ImmutableSet.of(possibleSourcePath, interestingSourcePath)),
        ImmutableSet.of(interestingSourcePath),
        false, // rule key should change
        ImmutableSet.of(possibleSourcePath, interestingSourcePath),
        "Manifest key should change when a not dep-file-covered input is added/removed.");

    if (interestingSourcePath instanceof ArchiveMemberSourcePath) { // TODO(plamenko)
      testManifestKey(
          ruleFinder,
          pathResolver,
          ImmutableList.of(
              possibleSourcePath,
              noncoveredSourcePath),
          ImmutableList.of(
              possibleSourcePath,
              noncoveredSourcePath,
              interestingSourcePath),
          ImmutableMap.of(// before
              possibleAbsolutePath, HashCode.fromInt(100),
              noncoveredAbsolutePath, HashCode.fromInt(300)),
          ImmutableMap.of(// after
              possibleAbsolutePath, HashCode.fromInt(100),
              noncoveredAbsolutePath, HashCode.fromInt(300),
              interestingAbsolutePath, HashCode.fromInt(400)),
          Optional.of(ImmutableSet.of(possibleSourcePath, interestingSourcePath)),
          ImmutableSet.of(interestingSourcePath),
          false, // rule key should change
          ImmutableSet.of(possibleSourcePath, interestingSourcePath),
          "Manifest key should change when a mandatory input is added/removed.");
    }
  }

  /** Tests SourcePaths both directly, and when wrapped with a RuleKeyAppendable. */
  private void testManifestKey(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValue,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Optional<ImmutableSet<SourcePath>> possibleInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage) throws Exception {
    testManifestKey(
        ruleFinder,
        pathResolver,
        fieldValue, // same field value before and after
        fieldValue, // same field value before and after
        hashesBefore,
        hashesAfter,
        possibleInputPaths,
        interestingInputPaths,
        expectSameKeys,
        expectedDepFileInputsAfter,
        failureMessage);
  }
  private void testManifestKey(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValueBefore,
      Object fieldValueAfter,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Optional<ImmutableSet<SourcePath>> possibleInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage) throws Exception {
    testManifestKeyImpl(
        ruleFinder,
        pathResolver,
        fieldValueBefore,
        fieldValueAfter,
        hashesBefore,
        hashesAfter,
        possibleInputPaths,
        interestingInputPaths,
        expectSameKeys,
        expectedDepFileInputsAfter,
        failureMessage);
    // make sure the behavior is same if wrapped with RuleKeyAppendable
    testManifestKeyImpl(
        ruleFinder,
        pathResolver,
        new RuleKeyAppendableWrapped(fieldValueBefore),
        new RuleKeyAppendableWrapped(fieldValueAfter),
        hashesBefore,
        hashesAfter,
        possibleInputPaths,
        interestingInputPaths,
        expectSameKeys,
        expectedDepFileInputsAfter,
        failureMessage);
  }

  private void testManifestKeyImpl(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValueBefore,
      Object fieldValueAfter,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Optional<ImmutableSet<SourcePath>> possibleInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage) throws Exception {
    FakeDepFileBuildRule rule1 = new FakeDepFileBuildRule("//:rule") {
      @AddToRuleKey
      final Object myField = fieldValueBefore;
    };
    rule1.setPossibleInputPaths(possibleInputPaths);
    rule1.setInterestingInputPaths(interestingInputPaths);
    FakeFileHashCache hashCache = new FakeFileHashCache(hashesBefore, true, ImmutableMap.of());
    Pair<RuleKey, ImmutableSet<SourcePath>> res1 =
        new DefaultDependencyFileRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .buildManifestKey(rule1);

    FakeDepFileBuildRule rule2 = new FakeDepFileBuildRule("//:rule") {
      @AddToRuleKey
      final Object myField = fieldValueAfter;
    };
    rule2.setPossibleInputPaths(possibleInputPaths);
    rule2.setInterestingInputPaths(interestingInputPaths);
    hashCache = new FakeFileHashCache(hashesAfter, true, ImmutableMap.of());
    Pair<RuleKey, ImmutableSet<SourcePath>> res2 =
        new DefaultDependencyFileRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .buildManifestKey(rule2);

    if (expectSameKeys) {
      assertThat(failureMessage, res2.getFirst(), Matchers.equalTo(res1.getFirst()));
    } else {
      assertThat(failureMessage, res2.getFirst(), Matchers.not(Matchers.equalTo(res1.getFirst())));
    }
    assertThat(res2.getSecond(), Matchers.equalTo(expectedDepFileInputsAfter));
  }

  private static class RuleKeyAppendableWrapped implements RuleKeyAppendable {

    private final Object field;

    public RuleKeyAppendableWrapped(Object field) {
      this.field = field;
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("field", field);
    }

  }

  private BuildRuleResolver newRuleResolver() {
    return new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
  }

}

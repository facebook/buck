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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.RuleKeyAppendable;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.FakeDepFileBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class DependencyFileRuleKeyFactoryTest {

  @Test
  public void testKeysWhenInputPathContentsChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver = newActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    SourcePath usedSourcePath = PathSourcePath.of(filesystem, Paths.get("usedInput"));
    SourcePath unusedSourcePath = PathSourcePath.of(filesystem, Paths.get("unusedInput"));
    SourcePath noncoveredSourcePath = PathSourcePath.of(filesystem, Paths.get("noncoveredInput"));
    SourcePath interestingSourcePath = PathSourcePath.of(filesystem, Paths.get("interestingIn"));

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
    ActionGraphBuilder graphBuilder = newActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildTarget usedTarget = BuildTargetFactory.newInstance("//:used");
    BuildTarget unusedTarget = BuildTargetFactory.newInstance("//:unused");
    BuildTarget noncoveredTarget = BuildTargetFactory.newInstance("//:noncovered");
    BuildTarget interestingTarget = BuildTargetFactory.newInstance("//:interesting");
    SourcePath usedSourcePath = DefaultBuildTargetSourcePath.of(usedTarget);
    SourcePath unusedSourcePath = DefaultBuildTargetSourcePath.of(unusedTarget);
    SourcePath noncoveredSourcePath = DefaultBuildTargetSourcePath.of(noncoveredTarget);
    SourcePath interestingSourcePath = DefaultBuildTargetSourcePath.of(interestingTarget);
    graphBuilder.addToIndex(new FakeBuildRule(usedTarget).setOutputFile("used"));
    graphBuilder.addToIndex(new FakeBuildRule(unusedTarget).setOutputFile("unused"));
    graphBuilder.addToIndex(new FakeBuildRule(noncoveredTarget).setOutputFile("nc"));
    graphBuilder.addToIndex(new FakeBuildRule(interestingTarget).setOutputFile("in"));

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
    BuildRuleResolver ruleResolver = newActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    SourcePath archivePath = PathSourcePath.of(filesystem, Paths.get("archive"));
    SourcePath usedSourcePath = ArchiveMemberSourcePath.of(archivePath, Paths.get("used"));
    SourcePath unusedSourcePath = ArchiveMemberSourcePath.of(archivePath, Paths.get("unused"));
    SourcePath noncoveredSourcePath = ArchiveMemberSourcePath.of(archivePath, Paths.get("nc"));
    SourcePath interestingSourcePath =
        ArchiveMemberSourcePath.of(archivePath, Paths.get("META-INF"));

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

  /** Tests all types of changes (or the lack of it): used, unused, noncovered. */
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
      DependencyFileEntry usedDepFileEntry)
      throws Exception {
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

  /** Tests all types of changes (or the lack of it): used, unused, noncovered. */
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
      DependencyFileEntry usedDepFileEntry)
      throws Exception {
    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(usedSourcePath, unusedSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when none of the inputs changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when none of the inputs changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(usedSourcePath, unusedSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(205),
            interestingAbsolutePath, HashCode.fromInt(400)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when only dep-file-covered but unused inputs change.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(205),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when only dep-file-covered but unused inputs change.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(usedSourcePath, unusedSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(105),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a dep-file-covered and used input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(105),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a dep-file-covered and used input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a not dep-file-covered input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(405)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when an interesting, but unused input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(405)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when an interesting, but unused input changes.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(usedSourcePath, interestingSourcePath),
        ImmutableList.of(usedSourcePath, unusedSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(205),
            interestingAbsolutePath, HashCode.fromInt(400)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        true, // rule key should remain same
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should NOT change when a covered but unused input is added/removed.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(usedSourcePath, unusedSourcePath, interestingSourcePath),
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when a not dep-file-covered input is added/removed.");

    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(usedSourcePath, unusedSourcePath, noncoveredSourcePath),
        ImmutableList.of(
            usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(300)),
        ImmutableMap.of( // after
            usedAbsolutePath, HashCode.fromInt(100),
            unusedAbsolutePath, HashCode.fromInt(200),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(usedSourcePath, unusedSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        ImmutableList.of(usedDepFileEntry),
        false, // rule key should change
        ImmutableSet.of(usedSourcePath),
        "Dep file rule key should change when an interesting input is added/removed.");

    try {
      testDepFileRuleKey(
          ruleFinder,
          pathResolver,
          ImmutableList.of(
              usedSourcePath, unusedSourcePath, noncoveredSourcePath, interestingSourcePath),
          ImmutableMap.of( // before
              usedAbsolutePath, HashCode.fromInt(100),
              unusedAbsolutePath, HashCode.fromInt(200),
              noncoveredAbsolutePath, HashCode.fromInt(300),
              interestingAbsolutePath, HashCode.fromInt(400)),
          ImmutableMap.of( // after
              usedAbsolutePath, HashCode.fromInt(100),
              unusedAbsolutePath, HashCode.fromInt(200),
              noncoveredAbsolutePath, HashCode.fromInt(300),
              interestingAbsolutePath, HashCode.fromInt(400)),
          ImmutableSet.of(unusedSourcePath)::contains, // "used" is not accounted as covered
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

  /** Tests SourcePaths both directly, and when wrapped with a RuleKeyAppendable. */
  private void testDepFileRuleKey(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValue,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Predicate<SourcePath> coveredInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      ImmutableList<DependencyFileEntry> depFileEntries,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage)
      throws Exception {
    testDepFileRuleKey(
        ruleFinder,
        pathResolver,
        fieldValue, // same field value before and after
        fieldValue, // same field value before and after
        hashesBefore,
        hashesAfter,
        coveredInputPaths,
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
      Predicate<SourcePath> coveredInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      ImmutableList<DependencyFileEntry> depFileEntries,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage)
      throws Exception {
    testDepFileRuleKeyImpl(
        ruleFinder,
        pathResolver,
        fieldValueBefore,
        fieldValueAfter,
        hashesBefore,
        hashesAfter,
        coveredInputPaths,
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
        coveredInputPaths,
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
      Predicate<SourcePath> coveredInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      ImmutableList<DependencyFileEntry> depFileEntries,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage)
      throws Exception {
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    FakeDepFileBuildRule rule1 = new FakeDepFileBuildRuleWithField(fieldValueBefore);
    rule1.setCoveredByDepFilePredicate(coveredInputPaths);
    rule1.setExistenceOfInterestPredicate(interestingInputPaths);
    FakeFileHashCache hashCache = new FakeFileHashCache(hashesBefore, true, ImmutableMap.of());
    RuleKeyAndInputs res1 =
        new DefaultDependencyFileRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder)
            .build(rule1, depFileEntries);

    FakeDepFileBuildRule rule2 = new FakeDepFileBuildRuleWithField(fieldValueAfter);
    rule2.setCoveredByDepFilePredicate(coveredInputPaths);
    rule2.setExistenceOfInterestPredicate(interestingInputPaths);
    hashCache = new FakeFileHashCache(hashesAfter, true, ImmutableMap.of());
    RuleKeyAndInputs res2 =
        new DefaultDependencyFileRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder)
            .build(rule2, depFileEntries);

    if (expectSameKeys) {
      assertThat(failureMessage, res2.getRuleKey(), Matchers.equalTo(res1.getRuleKey()));
    } else {
      assertThat(
          failureMessage, res2.getRuleKey(), Matchers.not(Matchers.equalTo(res1.getRuleKey())));
    }
    assertThat(res2.getInputs(), Matchers.equalTo(expectedDepFileInputsAfter));
  }

  /** Tests all types of changes (or the lack of it): used, unused, noncovered. */
  private void testManifestKeyWhenInputContentsChanges(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      SourcePath coveredSourcePath,
      SourcePath noncoveredSourcePath,
      SourcePath interestingSourcePath,
      Path coveredAbsolutePath,
      Path noncoveredAbsolutePath,
      Path interestingAbsolutePath)
      throws Exception {
    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when none of the inputs changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when none of the inputs changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(105),
            interestingAbsolutePath, HashCode.fromInt(400)),
        (SourcePath path) -> true, // all inputs are covered by dep file
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when only dep-file-covered inputs change.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(105),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when only dep-file-covered but unused inputs change.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(305),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        false, // rule key should change
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should change when a not dep-file-covered input changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(405)),
        (SourcePath path) -> true, // all files covered
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should change
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when an interesting, but covered input changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(405)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should change
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when an interesting, but covered input changes.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(noncoveredSourcePath, interestingSourcePath),
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(105),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        true, // rule key should remain same
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should NOT change when only dep-file-covered inputs are added/removed.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, interestingSourcePath),
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        false, // rule key should change
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should change when a not dep-file-covered input is added/removed.");

    testManifestKey(
        ruleFinder,
        pathResolver,
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath),
        ImmutableList.of(coveredSourcePath, noncoveredSourcePath, interestingSourcePath),
        ImmutableMap.of( // before
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300)),
        ImmutableMap.of( // after
            coveredAbsolutePath, HashCode.fromInt(100),
            noncoveredAbsolutePath, HashCode.fromInt(300),
            interestingAbsolutePath, HashCode.fromInt(400)),
        ImmutableSet.of(coveredSourcePath, interestingSourcePath)::contains,
        ImmutableSet.of(interestingSourcePath),
        false, // rule key should change
        ImmutableSet.of(coveredSourcePath, interestingSourcePath),
        "Manifest key should change when a mandatory input is added/removed.");
  }

  /** Tests SourcePaths both directly, and when wrapped with a RuleKeyAppendable. */
  private void testManifestKey(
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver,
      Object fieldValue,
      ImmutableMap<Path, HashCode> hashesBefore,
      ImmutableMap<Path, HashCode> hashesAfter,
      Predicate<SourcePath> coveredInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage)
      throws Exception {
    testManifestKey(
        ruleFinder,
        pathResolver,
        fieldValue, // same field value before and after
        fieldValue, // same field value before and after
        hashesBefore,
        hashesAfter,
        coveredInputPaths,
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
      Predicate<SourcePath> coveredInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage)
      throws Exception {
    testManifestKeyImpl(
        ruleFinder,
        pathResolver,
        fieldValueBefore,
        fieldValueAfter,
        hashesBefore,
        hashesAfter,
        coveredInputPaths,
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
        coveredInputPaths,
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
      Predicate<SourcePath> coveredInputPaths,
      ImmutableSet<SourcePath> interestingInputPaths,
      boolean expectSameKeys,
      ImmutableSet<SourcePath> expectedDepFileInputsAfter,
      String failureMessage)
      throws Exception {
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    FakeDepFileBuildRule rule1 = new FakeDepFileBuildRuleWithField(fieldValueBefore);
    rule1.setCoveredByDepFilePredicate(coveredInputPaths);
    rule1.setExistenceOfInterestPredicate(interestingInputPaths);
    FakeFileHashCache hashCache = new FakeFileHashCache(hashesBefore, true, ImmutableMap.of());
    RuleKeyAndInputs res1 =
        new DefaultDependencyFileRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder)
            .buildManifestKey(rule1);

    FakeDepFileBuildRule rule2 = new FakeDepFileBuildRuleWithField(fieldValueAfter);
    rule2.setCoveredByDepFilePredicate(coveredInputPaths);
    rule2.setExistenceOfInterestPredicate(interestingInputPaths);
    hashCache = new FakeFileHashCache(hashesAfter, true, ImmutableMap.of());
    RuleKeyAndInputs res2 =
        new DefaultDependencyFileRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder)
            .buildManifestKey(rule2);

    if (expectSameKeys) {
      assertThat(failureMessage, res2.getRuleKey(), Matchers.equalTo(res1.getRuleKey()));
    } else {
      assertThat(
          failureMessage, res2.getRuleKey(), Matchers.not(Matchers.equalTo(res1.getRuleKey())));
    }
    assertThat(res2.getInputs(), Matchers.equalTo(expectedDepFileInputsAfter));
  }

  @Test
  public void testKeysGetHashed() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    RuleKeyFieldLoader fieldLoader =
        new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create());
    BuildRuleResolver ruleResolver = newActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    SourcePath unusedSourcePath = PathSourcePath.of(filesystem, Paths.get("input0"));
    SourcePath sourcePath = PathSourcePath.of(filesystem, Paths.get("input"));
    DependencyFileEntry dependencyFileEntry =
        DependencyFileEntry.fromSourcePath(sourcePath, pathResolver);

    ImmutableMap<Path, HashCode> hashes =
        ImmutableMap.of(pathResolver.getAbsolutePath(sourcePath), HashCode.fromInt(42));

    Predicate<SourcePath> coveredPredicate =
        ImmutableSet.of(sourcePath, unusedSourcePath)::contains;

    FakeDepFileBuildRule rule1 =
        new FakeDepFileBuildRule("//:rule") {
          @AddToRuleKey final Object myField1 = sourcePath;
          @AddToRuleKey final Object myField2 = unusedSourcePath;
        };
    rule1.setCoveredByDepFilePredicate(coveredPredicate);
    FakeFileHashCache hashCache = new FakeFileHashCache(hashes, true, ImmutableMap.of());
    RuleKeyAndInputs res1 =
        new DefaultDependencyFileRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder)
            .build(rule1, ImmutableList.of(dependencyFileEntry));

    FakeDepFileBuildRule rule2 =
        new FakeDepFileBuildRule("//:rule") {
          @AddToRuleKey final Object myField1 = unusedSourcePath;
          @AddToRuleKey final Object myField2 = sourcePath;
        };
    rule2.setCoveredByDepFilePredicate(coveredPredicate);
    hashCache = new FakeFileHashCache(hashes, true, ImmutableMap.of());
    RuleKeyAndInputs res2 =
        new DefaultDependencyFileRuleKeyFactory(fieldLoader, hashCache, pathResolver, ruleFinder)
            .build(rule2, ImmutableList.of(dependencyFileEntry));

    assertThat(res2.getRuleKey(), Matchers.not(Matchers.equalTo(res1.getRuleKey())));
    assertThat(res2.getInputs(), Matchers.equalTo(ImmutableSet.of(sourcePath)));
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

  private ActionGraphBuilder newActionGraphBuilder() {
    return new TestActionGraphBuilder();
  }

  private static class FakeDepFileBuildRuleWithField extends FakeDepFileBuildRule {
    @AddToRuleKey final Object myField;

    public FakeDepFileBuildRuleWithField(Object value) {
      super("//:rule");
      myField = value;
    }
  }
}

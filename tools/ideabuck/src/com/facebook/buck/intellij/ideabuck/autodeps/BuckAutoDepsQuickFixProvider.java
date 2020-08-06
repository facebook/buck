/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.intellij.codeInsight.daemon.QuickFixActionRegistrar;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.quickfix.UnresolvedReferenceQuickFixProvider;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * Provides the {@link BuckAddDependencyIntention} when it is possible to deduce the source for a
 * class that isn't known to the IntelliJ project.
 */
public class BuckAutoDepsQuickFixProvider
    extends UnresolvedReferenceQuickFixProvider<PsiJavaCodeReferenceElement>
    implements HighPriorityAction {

  private static Logger LOGGER = Logger.getInstance(BuckAutoDepsQuickFixProvider.class);

  private static final String KEEP_DEFAULT_AUTODEPS = "ideabuck.quickfix.autodeps.showdefault";

  @Override
  public void registerFixes(
      @NotNull PsiJavaCodeReferenceElement referenceElement,
      @NotNull QuickFixActionRegistrar quickFixActionRegistrar) {
    List<IntentionAction> fixes = findFixesForReference(referenceElement);
    fixes.forEach(quickFixActionRegistrar::register);
    if (fixes.isEmpty() || Boolean.parseBoolean(System.getProperty(KEEP_DEFAULT_AUTODEPS))) {
      // Don't unregister the default if:
      //  1) We can't replace it with anything.
      //  2) The user has requested to keep it.
      return;
    }

    // If we think we can add both a Buck dependency and an IntelliJ module dependency,
    // unregister the default fix, which only adds an IntelliJ module dependency.
    quickFixActionRegistrar.unregister(
        new Condition<IntentionAction>() {
          private static final String ADD_MODULE_DEPENDENCY_FIX_CLASSNAME =
              "com.intellij.codeInsight.daemon.impl.quickfix.AddModuleDependencyFix";

          @Override
          public boolean value(IntentionAction intentionAction) {
            String className = intentionAction.getClass().getName();
            if (ADD_MODULE_DEPENDENCY_FIX_CLASSNAME.equals(className)) {
              return true;
            }
            return false;
          }
        });
  }

  private List<IntentionAction> findFixesForReference(
      PsiJavaCodeReferenceElement referenceElement) {
    Project project = referenceElement.getProject();
    String className = referenceElement.getQualifiedName();
    List<IntentionAction> results = new ArrayList<>();
    if (className != null) {
      findPsiClasses(project, className).stream()
          .map(psiClass -> BuckAddDependencyIntention.create(referenceElement, psiClass))
          .filter(Objects::nonNull)
          .forEach(results::add);
    }
    return results;
  }

  private static Set<PsiClass> findPsiClasses(Project project, String className) {
    GlobalSearchScope scope = GlobalSearchScope.everythingScope(project);
    JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
    PsiShortNamesCache psiShortNamesCache = PsiShortNamesCache.getInstance(project);
    Set<PsiClass> results = new HashSet<>();
    BuckAutoDepsSearchableClassNameContributor.EP_NAME.getExtensions(project).stream()
        .filter(contributor -> contributor.isApplicable(project, className))
        .forEach(
            contributor ->
                contributor
                    .getSearchableClassNames(project, className)
                    .forEach(
                        name ->
                            Stream.concat(
                                    Stream.of(javaPsiFacade.findClasses(name, scope)),
                                    Stream.of(psiShortNamesCache.getClassesByName(name, scope)))
                                .distinct()
                                .forEach(
                                    psiClass -> {
                                      if (!results.contains(psiClass)
                                          && contributor.filter(project, className, psiClass)) {
                                        results.add(psiClass);
                                      }
                                    })));
    return results;
  }

  @NotNull
  @Override
  public Class<PsiJavaCodeReferenceElement> getReferenceClass() {
    return PsiJavaCodeReferenceElement.class;
  }
}

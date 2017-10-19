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

package com.facebook.buck.intellij.ideabuck.fixup;

import com.facebook.buck.ide.intellij.projectview.shared.SharedConstants;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiManager;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class BulkFileListenerDispatcher implements BulkFileListener {

  private static final Logger LOG = Logger.getInstance(BulkFileListenerDispatcher.class);

  // We need to use Reflection to call into the Android plugin
  private Class<?> androidFacetClass;
  private Method getAllResourceDirectoriesMethod;
  private boolean haveReflected = false;

  // region BulkFileListener overrides

  @Override
  public void before(@NotNull List<? extends VFileEvent> list) {}

  @Override
  public void after(@NotNull List<? extends VFileEvent> list) {
    for (VFileEvent event : list) {
      VFileCreateEvent fileCreateEvent;
      if (event instanceof VFileCreateEvent) {
        fileCreateEvent = (VFileCreateEvent) event;
      } else {
        continue;
      }

      Project project = getProject(fileCreateEvent);

      if (project == null) {
        continue; // This is not a file creation event we need to do any fixup on
      }

      ModuleManager moduleManager = ModuleManager.getInstance(project);
      Module[] modules = moduleManager.getModules();
      if (modules.length != 1) {
        continue; // This is NOT a Project View
      }
      Module module = modules[0];

      if (!module.getName().equals(SharedConstants.ROOT_MODULE_NAME)) {
        continue; // This is NOT a Project View
      }

      FacetManager facetManager = FacetManager.getInstance(module);
      Facet[] facets = facetManager.getAllFacets();
      if (facets.length != 1) {
        continue; // This is NOT a Project View
      }
      Facet facet = facets[0];

      if (!facet.getName().equals("Android")) {
        continue; // This is NOT a Project View
      }

      List<VirtualFile> resourceDirectories = getAllResourceDirectories(facet);
      if (resourceDirectories == null || resourceDirectories.size() != 1) {
        continue; // This is NOT a Project View
      }
      VirtualFile resourceDirectory = resourceDirectories.get(0);

      boolean inProjectViewResourceDirectory =
          fileCreateEvent.getPath().startsWith(resourceDirectory.getPath());

      FileCreateHandler handler = null;
      if (inProjectViewResourceDirectory) {
        handler = new MoveResourceFiles();
      } else if (Files.isDirectory(Paths.get(fileCreateEvent.getPath()))) {
        handler = new HandlePackageCreation();
      }
      if (handler != null) {
        handler.onFileCreate(fileCreateEvent, facet);
      }
    }
  }

  // region BulkFileListener private utilities

  private List<VirtualFile> getAllResourceDirectories(Facet facet) {
    Class<? extends Facet> facetClass = facet.getClass();

    if (!haveReflected) {
      Class<?> clazz = null;
      Method method = null;
      try {
        clazz =
            Class.forName(
                "org.jetbrains.android.facet.AndroidFacet", true, facetClass.getClassLoader());
      } catch (Exception e) {
        // Leave clazz equal to null
        log("getAllResourceDirectories(): Exception %s in Class.forName()", e);
      }

      if (clazz != null) {
        try {
          method = clazz.getMethod("getAllResourceDirectories", (Class<?>[]) null);
        } catch (Exception e) {
          // Leave method equal to null
          log("getAllResourceDirectories(): Exception %s in clazz.getMethod()", e);
        }
      }

      androidFacetClass = clazz;
      getAllResourceDirectoriesMethod = method;
      haveReflected = true;
    }

    if (androidFacetClass == null || getAllResourceDirectoriesMethod == null) {
      if (androidFacetClass == null) {
        log("getAllResourceDirectories(): No clazz");
      }
      if (getAllResourceDirectoriesMethod == null) {
        log("getAllResourceDirectories(): No method");
      }
      return null;
    }

    try {
      return (List<VirtualFile>) getAllResourceDirectoriesMethod.invoke(facet);
    } catch (Exception e) {
      log("getAllResourceDirectories(): Exception %s calling facet.getAllResourceDirectories()", e);
      return null;
    }
  }

  private static Project getProject(VFileEvent event) {
    Object requestor = event.getRequestor();
    if (requestor instanceof PsiManager) {
      PsiManager psiManager = (PsiManager) requestor;
      return psiManager.getProject();
    }
    return null;
  }

  // endregion BulkFileListener private utilities

  // endregion BulkFileListener overrides

  // region Log messages

  private static void log(String pattern, Object... parameters) {
    LOG.info(String.format(pattern, parameters));
  }

  // endregion Log messages
}

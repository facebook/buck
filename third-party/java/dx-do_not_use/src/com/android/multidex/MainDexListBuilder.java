/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.multidex;

import com.android.dx.cf.attrib.AttRuntimeVisibleAnnotations;
import com.android.dx.cf.direct.DirectClassFile;
import com.android.dx.cf.iface.Attribute;
import com.android.dx.cf.iface.FieldList;
import com.android.dx.cf.iface.HasAttribute;
import com.android.dx.cf.iface.MethodList;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * This is a command line tool used by mainDexClasses script to build a main dex classes list. First
 * argument of the command line is an archive, each class file contained in this archive is used to
 * identify a class that can be used during secondary dex installation, those class files
 * are not opened by this tool only their names matter. Other arguments must be zip files or
 * directories, they constitute in a classpath in with the classes named by the first argument
 * will be searched. Each searched class must be found. On each of this classes are searched for
 * their dependencies to other classes. The tool also browses for classes annotated by runtime
 * visible annotations and adds them to the list/ Finally the tools prints on standard output a list
 * of class files names suitable as content of the file argument --main-dex-list of dx.
 */
public class MainDexListBuilder {
    private static final String CLASS_EXTENSION = ".class";

    private static final int STATUS_ERROR = 1;

    private static final String EOL = System.getProperty("line.separator");

    private static final String USAGE_MESSAGE =
            "Usage:" + EOL + EOL +
            "Short version: Don't use this." + EOL + EOL +
            "Slightly longer version: This tool is used by mainDexClasses script to build" + EOL +
            "the main dex list." + EOL;

    private Set<String> filesToKeep = new HashSet<String>();

    public static void main(String[] args) {

        if (args.length != 2) {
            printUsage();
            System.exit(STATUS_ERROR);
        }

        try {

            MainDexListBuilder builder = new MainDexListBuilder(args[0], args[1]);
            Set<String> toKeep = builder.getMainDexList();
            printList(toKeep);
        } catch (IOException e) {
            System.err.println("A fatal error occured: " + e.getMessage());
            System.exit(STATUS_ERROR);
            return;
        }
    }

    public MainDexListBuilder(String rootJar, String pathString) throws IOException {
        ZipFile jarOfRoots = null;
        Path path = null;
        try {
            try {
                jarOfRoots = new ZipFile(rootJar);
            } catch (IOException e) {
                throw new IOException("\"" + rootJar + "\" can not be read as a zip archive. ("
                        + e.getMessage() + ")", e);
            }
            path = new Path(pathString);

            ClassReferenceListBuilder mainListBuilder = new ClassReferenceListBuilder(path);
            mainListBuilder.addRoots(jarOfRoots);
            for (String className : mainListBuilder.getClassNames()) {
                filesToKeep.add(className + CLASS_EXTENSION);
            }
            keepAnnotated(path);
        } finally {
            try {
                jarOfRoots.close();
            } catch (IOException e) {
                // ignore
            }
            if (path != null) {
                for (ClassPathElement element : path.elements) {
                    try {
                        element.close();
                    } catch (IOException e) {
                        // keep going, lets do our best.
                    }
                }
            }
        }
    }

    /**
     * Returns a list of classes to keep. This can be passed to dx as a file with --main-dex-list.
     */
    public Set<String> getMainDexList() {
        return filesToKeep;
    }

    private static void printUsage() {
        System.err.print(USAGE_MESSAGE);
    }

    private static void printList(Set<String> fileNames) {
        for (String fileName : fileNames) {
            System.out.println(fileName);
        }
    }

    /**
     * Keep classes annotated with runtime annotations.
     */
    private void keepAnnotated(Path path) throws FileNotFoundException {
        for (ClassPathElement element : path.getElements()) {
            forClazz:
                for (String name : element.list()) {
                    if (name.endsWith(CLASS_EXTENSION)) {
                        DirectClassFile clazz = path.getClass(name);
                        if (hasRuntimeVisibleAnnotation(clazz)) {
                            filesToKeep.add(name);
                        } else {
                            MethodList methods = clazz.getMethods();
                            for (int i = 0; i<methods.size(); i++) {
                                if (hasRuntimeVisibleAnnotation(methods.get(i))) {
                                    filesToKeep.add(name);
                                    continue forClazz;
                                }
                            }
                            FieldList fields = clazz.getFields();
                            for (int i = 0; i<fields.size(); i++) {
                                if (hasRuntimeVisibleAnnotation(fields.get(i))) {
                                    filesToKeep.add(name);
                                    continue forClazz;
                                }
                            }
                        }
                    }
                }
        }
    }

    private boolean hasRuntimeVisibleAnnotation(HasAttribute element) {
        Attribute att = element.getAttributes().findFirst(
                AttRuntimeVisibleAnnotations.ATTRIBUTE_NAME);
        return (att != null && ((AttRuntimeVisibleAnnotations)att).getAnnotations().size()>0);
    }
}

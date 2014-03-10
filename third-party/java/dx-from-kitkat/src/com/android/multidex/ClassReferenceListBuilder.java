/*
 * Copyright (C) 2013 The Android Open Source Project
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

import com.android.dx.cf.direct.DirectClassFile;
import com.android.dx.cf.direct.StdAttributeFactory;
import com.android.dx.rop.cst.Constant;
import com.android.dx.rop.cst.ConstantPool;
import com.android.dx.rop.cst.CstType;
import com.android.dx.rop.type.Type;
import com.android.dx.rop.type.TypeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * This is a command line tool used by mainDexClasses script to find direct class references to
 * other classes. First argument of the command line is an archive, each class file contained in
 * this archive is used to identify a class whose references are to be searched, those class files
 * are not opened by this tool only their names matter. Other arguments must be zip files or
 * directories, they constitute in a classpath in with the classes named by the first argument
 * will be searched. Each searched class must be found. On each of this classes are searched for
 * their dependencies to other classes. Finally the tools prints on standard output a list of class
 * files names suitable as content of the file argument --main-dex-list of dx.
 */
public class ClassReferenceListBuilder {

    private static final String CLASS_EXTENSION = ".class";

    private static final int STATUS_ERROR = 1;

    private static final String EOL = System.getProperty("line.separator");

    private static String USAGE_MESSAGE =
            "Usage:" + EOL + EOL +
            "Short version: Don't use this." + EOL + EOL +
            "Slightly longer version: This tool is used by mainDexClasses script to find direct"
            + EOL +
            "references of some classes." + EOL;

    private Path path;
    private Set<String> toKeep = new HashSet<String>();

    private ClassReferenceListBuilder(Path path) {
        this.path = path;
    }

    public static void main(String[] args) {

        if (args.length != 2) {
            printUsage();
            System.exit(STATUS_ERROR);
        }

        ZipFile jarOfRoots;
        try {
            jarOfRoots = new ZipFile(args[0]);
        } catch (IOException e) {
            System.err.println("\"" + args[0] + "\" can not be read as a zip archive. ("
                    + e.getMessage() + ")");
            System.exit(STATUS_ERROR);
            return;
        }

        Path path = null;
        try {
            path = new Path(args[1]);

            ClassReferenceListBuilder builder = new ClassReferenceListBuilder(path);
            builder.addRoots(jarOfRoots);

            printList(builder.toKeep);
        } catch (IOException e) {
            System.err.println("A fatal error occured: " + e.getMessage());
            System.exit(STATUS_ERROR);
            return;
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

    private static void printUsage() {
        System.err.print(USAGE_MESSAGE);
    }

    private static ClassPathElement getClassPathElement(File file)
            throws ZipException, IOException {
        if (file.isDirectory()) {
            return new FolderPathElement(file);
        } else if (file.isFile()) {
            return new ArchivePathElement(new ZipFile(file));
        } else if (file.exists()) {
            throw new IOException(file.getAbsolutePath() +
                    " is not a directory neither a zip file");
        } else {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
    }

    private static void printList(Set<String> toKeep) {
        for (String classDescriptor : toKeep) {
            System.out.print(classDescriptor);
            System.out.println(CLASS_EXTENSION);
        }
    }

    private void addRoots(ZipFile jarOfRoots) throws IOException {

        // keep roots
        for (Enumeration<? extends ZipEntry> entries = jarOfRoots.entries();
                entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(CLASS_EXTENSION)) {
                toKeep.add(name.substring(0, name.length() - CLASS_EXTENSION.length()));
            }
        }

        // keep direct references of roots (+ direct references hierarchy)
        for (Enumeration<? extends ZipEntry> entries = jarOfRoots.entries();
                entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(CLASS_EXTENSION)) {
                DirectClassFile classFile;
                try {
                    classFile = path.getClass(name);
                } catch (FileNotFoundException e) {
                    throw new IOException("Class " + name +
                            " is missing form original class path " + path, e);
                }

                addDependencies(classFile.getConstantPool());
            }
        }
    }

    private void addDependencies(ConstantPool pool) {
        int entryCount = pool.size();
        for (Constant constant : pool.getEntries()) {
            if (constant instanceof CstType) {
                Type type = ((CstType) constant).getClassType();
                String descriptor = type.getDescriptor();
                if (descriptor.endsWith(";")) {
                    int lastBrace = descriptor.lastIndexOf('[');
                    if (lastBrace < 0) {
                        addClassWithHierachy(descriptor.substring(1, descriptor.length()-1));
                    } else {
                        assert descriptor.length() > lastBrace + 3
                        && descriptor.charAt(lastBrace + 1) == 'L';
                        addClassWithHierachy(descriptor.substring(lastBrace + 2,
                                descriptor.length() - 1));
                    }
                }
            }
        }
    }

    private void addClassWithHierachy(String classBinaryName) {
        if (toKeep.contains(classBinaryName)) {
            return;
        }

        String fileName = classBinaryName + CLASS_EXTENSION;
        try {
            DirectClassFile classFile = path.getClass(fileName);
            toKeep.add(classBinaryName);
            CstType superClass = classFile.getSuperclass();
            if (superClass != null) {
                addClassWithHierachy(superClass.getClassType().getClassName());
            }

            TypeList interfaceList = classFile.getInterfaces();
            int interfaceNumber = interfaceList.size();
            for (int i = 0; i < interfaceNumber; i++) {
                addClassWithHierachy(interfaceList.getType(i).getClassName());
            }
        } catch (FileNotFoundException e) {
            // Ignore: The referenced type is not in the path it must be part of the libraries.
        }
    }

    private static class Path {
        private List<ClassPathElement> elements = new ArrayList<ClassPathElement>();
        private String definition;
        private ByteArrayOutputStream baos = new ByteArrayOutputStream(40 * 1024);
        private byte[] readBuffer = new byte[20 * 1024];

        public Path(String definition) throws IOException {
            this.definition = definition;
            for (String filePath : definition.split(Pattern.quote(File.pathSeparator))) {
                try {
                    addElement(getClassPathElement(new File(filePath)));
                } catch (IOException e) {
                    throw new IOException("\"" + filePath + "\" can not be used as a classpath"
                            + " element. ("
                            + e.getMessage() + ")", e);
                }
            }
        }

        private static byte[] readStream(InputStream in, ByteArrayOutputStream baos, byte[] readBuffer)
                throws IOException {
            try {
                for (;;) {
                    int amt = in.read(readBuffer);
                    if (amt < 0) {
                        break;
                    }

                    baos.write(readBuffer, 0, amt);
                }
            } finally {
                in.close();
            }
            return baos.toByteArray();
        }

        @Override
        public String toString() {
            return definition;
        }

        private void addElement(ClassPathElement element) {
            assert element != null;
            elements.add(element);
        }

        private DirectClassFile getClass(String path) throws FileNotFoundException {
            DirectClassFile classFile = null;
            for (ClassPathElement element : elements) {
                try {
                    InputStream in = element.open(path);
                    try {
                        byte[] bytes = readStream(in, baos, readBuffer);
                        baos.reset();
                        classFile = new DirectClassFile(bytes, path, false);
                        classFile.setAttributeFactory(StdAttributeFactory.THE_ONE);
                        break;
                    } finally {
                        in.close();
                    }
                } catch (IOException e) {
                    // search next element
                }
            }
            if (classFile == null) {
                throw new FileNotFoundException(path);
            }
            return classFile;
        }
    }


}

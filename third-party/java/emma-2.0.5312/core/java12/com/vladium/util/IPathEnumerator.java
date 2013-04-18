/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IPathEnumerator.java,v 1.1.1.1.2.1 2004/07/16 23:32:04 vlad_r Exp $
 */
package com.vladium.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import com.vladium.logging.Logger;
import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IPathEnumerator
{
    // public: ................................................................
    
    // TODO: archives inside archives? (.war ?)
    
    public static interface IPathHandler
    {
        void handleDirStart (File pathDir, File dir); // not generated for path dirs themselves
        void handleFile (File pathDir, File file); 
        void handleDirEnd (File pathDir, File dir);

        /**
         * Called just after the enumerator's zip input stream for this archive
         * is opened and the manifest entry is read.
         */        
        void handleArchiveStart (File parentDir, File archive, Manifest manifest);
        
        void handleArchiveEntry (JarInputStream in, ZipEntry entry);
        
        /**
         * Called after the enumerator's zip input stream for this archive
         * has been closed.
         */
        void handleArchiveEnd (File parentDir, File archive);
        
    } // end of nested interface
    
    
    void enumerate () throws IOException;

    
    public static abstract class Factory
    {
        public static IPathEnumerator create (final File [] path, final boolean canonical, final IPathHandler handler)
        {
            return new PathEnumerator (path, canonical, handler);
        }
        
        private static final class PathEnumerator implements IPathEnumerator
        {
            public void enumerate () throws IOException
            {
                final IPathHandler handler = m_handler;
                
                for (m_pathIndex = 0; m_pathIndex < m_path.size (); ++ m_pathIndex) // important not to cache m_path.size()
                {
                    final File f = (File) m_path.get (m_pathIndex);
                    
                    if (! f.exists ())
                    {
                        if (IGNORE_INVALID_ENTRIES)
                            continue;
                        else
                            throw new IllegalArgumentException ("path entry does not exist: [" + f + "]");
                    }
                    
                    
                    if (f.isDirectory ())
                    {
                        if (m_verbose) m_log.verbose ("processing dir path entry [" + f.getAbsolutePath () + "] ...");
                        
                        m_currentPathDir = f;
                        enumeratePathDir (null);
                    }
                    else
                    {
                        final String name = f.getName ();
                        final String lcName = name.toLowerCase ();
                        
                        if (lcName.endsWith (".zip") || lcName.endsWith (".jar"))
                        {
                            if (m_verbose) m_log.verbose ("processing archive path entry [" + f.getAbsolutePath () + "] ...");
                            
                            final File parent = f.getParentFile (); // could be null
                            final File archive = new File (name);
                            m_currentPathDir = parent;
                        
                            // move to enumeratePathArchive(): handler.handleArchiveStart (parent, archive);
                            enumeratePathArchive (name);
                            handler.handleArchiveEnd (parent, archive); // note: it is important that this is called after the zip stream has been closed
                        }
                        else if (! IGNORE_INVALID_ENTRIES)
                        {
                            throw new IllegalArgumentException ("path entry is not a directory or an archive: [" + f + "]");
                        }
                    }
                }
            }
            
            PathEnumerator (final File [] path, final boolean canonical, final IPathHandler handler)
            {
                m_path = new ArrayList (path.length);
                for (int p = 0; p < path.length; ++ p) m_path.add (path [p]);
                
                m_canonical = canonical;
                
                if (handler == null) throw new IllegalArgumentException ("null input: handler");
                m_handler = handler;
                
                m_processManifest = true; // TODO
                
                if (m_processManifest)
                {
                    m_pathSet = new HashSet (path.length);
                    for (int p = 0; p < path.length; ++ p)
                    {
                        m_pathSet.add (path [p].getPath ()); // set of [possibly canonical] paths
                    }
                }
                else
                {
                    m_pathSet = null;
                }
                
                m_log = Logger.getLogger (); // each path enumerator caches its logger at creation time
                m_verbose = m_log.atVERBOSE ();
                m_trace1 = m_log.atTRACE1 ();
            }
            
            
            private void enumeratePathDir (final String dir)
                throws IOException
            {
                final boolean trace1 = m_trace1;
                
                final File currentPathDir = m_currentPathDir;
                final File fullDir = dir != null ? new File (currentPathDir, dir) : currentPathDir;
                
                final String [] children = fullDir.list ();
                final IPathHandler handler = m_handler;
                
                for (int c = 0, cLimit = children.length; c < cLimit; ++ c)
                {
                    final String childName = children [c];
                    
                    final File child = dir != null ? new File (dir, childName) : new File (childName);
                    final File fullChild = new File (fullDir, childName);
                    
                    if (fullChild.isDirectory ())
                    {
                        handler.handleDirStart (currentPathDir, child);
                        if (trace1) m_log.trace1 ("enumeratePathDir", "recursing into [" + child.getName () + "] ...");
                        enumeratePathDir (child.getPath ());
                        handler.handleDirEnd (currentPathDir, child);
                    }
                    else
                    {
//                        final String lcName = childName.toLowerCase ();
//                        
//                        if (lcName.endsWith (".zip") || lcName.endsWith (".jar"))
//                        {
//                            handler.handleArchiveStart (currentPathDir, child);
//                            enumeratePathArchive (child.getPath ());
//                            handler.handleArchiveEnd (currentPathDir, child);
//                        }
//                        else
                        {
                            if (trace1) m_log.trace1 ("enumeratePathDir", "processing file [" + child.getName () + "] ...");
                            handler.handleFile (currentPathDir, child);
                        }
                    }
                }
            }
            
            private void enumeratePathArchive (final String archive)
                throws IOException
            {
                final boolean trace1 = m_trace1;
                
                final File fullArchive = new File (m_currentPathDir, archive);
                
                JarInputStream in = null;
                try
                {
                    // note: Sun's JarFile uses native code and has been known to 
                    // crash the JVM in some builds; however, it uses random file
                    // access and can find "bad" manifests that are not the first
                    // entries in their archives (which JarInputStream can't do);
                    // [bugs: 4263225, 4696354, 4338238]
                    //
                    // there is really no good solution here but as a compromise
                    // I try to read the manifest again via a JarFile if the stream
                    // returns null for it:
                    
                    in = new JarInputStream (new BufferedInputStream (new FileInputStream (fullArchive), 32 * 1024));
                    
                    final IPathHandler handler = m_handler;
                    
                    Manifest manifest = in.getManifest (); // can be null
                    if (manifest == null) manifest = readManifestViaJarFile (fullArchive); // can be null 
                    
                    handler.handleArchiveStart (m_currentPathDir, new File (archive), manifest);
                    
                    // note: this loop does not skip over the manifest-related
                    // entries [the handler needs to be smart about that]
                    for (ZipEntry entry; (entry = in.getNextEntry ()) != null; )
                    {
                        // TODO: handle nested archives
                        
                        if (trace1) m_log.trace1 ("enumeratePathArchive", "processing archive entry [" + entry.getName () + "] ...");
                        handler.handleArchiveEntry (in, entry);
                        in.closeEntry ();
                    }
                    
                    
                    // TODO: this needs major testing
                    if (m_processManifest)
                    {
                        // note: JarInputStream only reads the manifest if it the
                        // first jar entry
                        if (manifest == null) manifest = in.getManifest ();
                        if (manifest != null)
                        {
                            final Attributes attributes = manifest.getMainAttributes ();
                            if (attributes != null)
                            {
                                // note: Sun's documentation says that multiple Class-Path:
                                // entries are merged sequentially (http://java.sun.com/products/jdk/1.2/docs/guide/extensions/spec.html)
                                // however, their own code does not implement this 
                                final String jarClassPath = attributes.getValue (Attributes.Name.CLASS_PATH);
                                if (jarClassPath != null)
                                {
                                    final StringTokenizer tokenizer = new StringTokenizer (jarClassPath);
                                    for (int p = 1; tokenizer.hasMoreTokens (); )
                                    {
                                        final String relPath = tokenizer.nextToken ();
                                        
                                        final File archiveParent = fullArchive.getParentFile ();
                                        final File path = archiveParent != null ? new File (archiveParent, relPath) : new File (relPath);
                                        
                                        final String fullPath = m_canonical ? Files.canonicalizePathname (path.getPath ()) : path.getPath ();
                                        
                                        if (m_pathSet.add (fullPath))
                                        {
                                            if (m_verbose) m_log.verbose ("  added manifest Class-Path entry [" + path + "]");
                                            m_path.add (m_pathIndex + (p ++), path); // insert after the current m_path entry
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                catch (FileNotFoundException fnfe) // ignore: this should not happen
                {
                    if ($assert.ENABLED) throw fnfe;
                }
                finally
                {
                    if (in != null) try { in.close (); } catch (Exception ignore) {}
                }
            }
            
            
            // see comments at the start of enumeratePathArchive()
            
            private static Manifest readManifestViaJarFile (final File archive)
            {
                Manifest result = null;
                
                JarFile jarfile = null;
                try
                {
                    jarfile = new JarFile (archive, false); // 3-arg constructor is not in J2SE 1.2
                    result = jarfile.getManifest ();
                }
                catch (IOException ignore)
                {
                }
                finally
                {
                    if (jarfile != null) try { jarfile.close (); } catch (IOException ignore) {} 
                }
                
                return result;
            } 
            

            private final ArrayList /* File */ m_path;
            private final boolean m_canonical;
            private final Set /* String */ m_pathSet;
            private final IPathHandler m_handler;
            private final boolean m_processManifest;
            
            private final Logger m_log;
            private boolean m_verbose, m_trace1;
            
            private int m_pathIndex;
            private File m_currentPathDir;
            
            // if 'true', non-existent or non-archive or non-directory path entries
            // will be silently ignored: 
            private static final boolean IGNORE_INVALID_ENTRIES = true; // this is consistent with the normal JVM behavior
                        
        } // end of nested class
        
    } // end of nested class
        
} // end of interface
// ----------------------------------------------------------------------------
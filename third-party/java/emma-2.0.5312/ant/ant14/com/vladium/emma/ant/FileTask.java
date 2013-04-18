/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: FileTask.java,v 1.2.2.1 2004/07/08 10:52:10 vlad_r Exp $
 */
package com.vladium.emma.ant;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;

import com.vladium.emma.ant.XFileSet;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class FileTask extends NestedTask
{
    // public: ................................................................
    
    
//    public static final class FileElement
//    {
//        public FileElement (final List /* File */ files)
//        {
//            m_files = files;
//        }
//        
//        public void setFile (final File file)
//        {
//            if (file != null) m_files.add (file);
//        }
//        
//        
//        private final List /* File */ m_files;
//        
//    } // end of nested class
    
    
    // infileset|fileset element:

    public final void addInfileset (final XFileSet set)
    {
        if (set != null) m_dataFileSets.add (set);
    }
    
    public final void addFileset (final XFileSet set)
    {
        if (set != null) m_dataFileSets.add (set);
    }


//    // infile|file element:
//    
//    public final FileElement createInfile ()
//    {
//        return new FileElement (m_dataFiles);
//    }
//    
//    public final FileElement createFile ()
//    {
//        return new FileElement (m_dataFiles);
//    }
    
    // protected: .............................................................
    
    
    protected FileTask (final SuppressableTask parent)
    {
        super (parent);
        
        m_dataFileSets = new ArrayList ();
//        m_dataFiles = new ArrayList ();
    }


    protected final String [] getDataPath (final boolean removeNonexistent)
    {
        final List /* String */ _files = new ArrayList ();
            
        // merge filesets:
        for (Iterator i = m_dataFileSets.iterator (); i.hasNext (); )
        {
            final FileSet set = (FileSet) i.next ();
            final DirectoryScanner ds = set.getDirectoryScanner (project);
            final File dsBaseDir = ds.getBasedir ();
            
            final String [] dsfiles = ds.getIncludedFiles ();
            for (int f = 0; f < dsfiles.length; ++ f)
            {
                _files.add (new File (dsBaseDir, dsfiles [f]).getAbsolutePath ());
            }
        }
        
//        // merge files:
//        for (Iterator i = m_dataFiles.iterator (); i.hasNext (); )
//        {
//            final File file = (File) i.next ();
//            if (! removeNonexistent || file.exists ())
//            {
//                _files.add (file.getAbsolutePath ());
//            }
//        }
        
        if (_files.size () == 0)
            return EMPTY_STRING_ARRAY;
        else
        {            
            final String [] files = new String [_files.size ()];
            _files.toArray (files);
            
            return files;
        }
    }
    
    // package: ...............................................................
    
    // private: ...............................................................
   
    
    private final List /* FileSet */ m_dataFileSets; // never null
//    private final List /* File */ m_dataFiles; // never null
    
    private static final String [] EMPTY_STRING_ARRAY = new String [0];

} // end of class
// ----------------------------------------------------------------------------
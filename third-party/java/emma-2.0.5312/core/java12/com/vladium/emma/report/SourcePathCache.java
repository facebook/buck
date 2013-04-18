/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SourcePathCache.java,v 1.1.1.1 2004/05/09 16:57:39 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class SourcePathCache
{
    // public: ................................................................
    
    // TODO: use soft cache for m_packageCache?
    
    /**
     * @param sourcepath [can be empty]
     */
    public SourcePathCache (final String [] sourcepath, final boolean removeNonExistent)
    {
        if (sourcepath == null) throw new IllegalArgumentException ("null input: sourcepath");
        
        final List _sourcepath = new ArrayList (sourcepath.length);
        for (int i = 0; i < sourcepath.length; ++ i)
        {
            final File dir = new File (sourcepath [i]);
            
            if (! removeNonExistent || (dir.isDirectory () && dir.exists ()))
                _sourcepath.add (dir);
        }
        
        m_sourcepath = new File [_sourcepath.size ()];
        _sourcepath.toArray (m_sourcepath);
        
        m_packageCache = new HashMap ();
    }
    
    /**
     * @param sourcepath [can be empty]
     */
    public SourcePathCache (final File [] sourcepath, final boolean removeNonExistent)
    {
        if (sourcepath == null) throw new IllegalArgumentException ("null input: sourcepath");
        
        final List _sourcepath = new ArrayList (sourcepath.length);
        for (int i = 0; i < sourcepath.length; ++ i)
        {
            final File dir = sourcepath [i];
            
            if (! removeNonExistent || (dir.isDirectory () && dir.exists ()))
                _sourcepath.add (dir);
        }
        
        m_sourcepath = new File [_sourcepath.size ()];
        _sourcepath.toArray (m_sourcepath);
        
        m_packageCache = new HashMap ();
    }
    
    /**
     * @return absolute pathname [null if 'name' was not found in cache]
     */
    public synchronized File find (final String packageVMName, final String name)
    {
        if (packageVMName == null) throw new IllegalArgumentException ("null input: packageVMName");
        if (name == null) throw new IllegalArgumentException ("null input: name");
        
        if (m_sourcepath.length == 0) return null;
        
        CacheEntry entry = (CacheEntry) m_packageCache.get (packageVMName);
        
        if (entry == null)
        {
            entry = new CacheEntry (m_sourcepath.length);
            m_packageCache.put (packageVMName, entry);
        }
        
        final Set [] listings = entry.m_listings;
        for (int p = 0; p < listings.length; ++ p)
        {
            Set listing = listings [p];
            if (listing == null)
            {
                listing = faultListing (m_sourcepath [p], packageVMName);
                listings [p] = listing;
            }
            
            // TODO: this is case-sensitive at this point
            if (listing.contains (name))
            {
                final File relativeFile = new File (packageVMName.replace ('/', File.separatorChar), name);
                
                return new File (m_sourcepath [p], relativeFile.getPath ()).getAbsoluteFile ();
            }
        }
        
        return null;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class CacheEntry
    {
        CacheEntry (final int size)
        {
            m_listings = new Set [size];
        }
        
        
        final Set /* String */ [] m_listings;
        
    } // end of nested class
    
    
    // NOTE: because java.io.* implements file filtering in bytecode
    // there is no real perf advantage in using a filter here (I might
    // as well do list() and filter the result myself 
    
    private static final class FileExtensionFilter implements FileFilter
    {
        public boolean accept (final File file)
        {
            if ($assert.ENABLED) $assert.ASSERT (file != null, "file = null");
            
            if (file.isDirectory ()) return false; // filter out directories

            final String name = file.getName ();
            final int lastDot = name.lastIndexOf ('.');
            if (lastDot <= 0) return false;
            
            // [assertion: lastDot > 0]
            
            // note: match is case sensitive
            return m_extension.equals (name.substring (lastDot));
        }
        
        public String toString ()
        {
            return super.toString () + ", extension = [" + m_extension + "]";
        }
        
        FileExtensionFilter (final String extension)
        {
            if (extension == null)
                throw new IllegalArgumentException ("null input: extension");
            
            // ensure starting '.':
            final String canonical = canonicalizeExtension (extension); 
            
            if (extension.length () <= 1)
                throw new IllegalArgumentException ("empty input: extension");
            
            m_extension = canonical;
        }
        
        private static String canonicalizeExtension (final String extension)
        {
            if (extension.charAt (0) != '.')
                return ".".concat (extension);
            else
                return extension;
        }
        
        
        private final String m_extension;
        
    } // end of nested class
    
    
    private Set /* String */ faultListing (final File dir, final String packageVMName)
    {
        if ($assert.ENABLED) $assert.ASSERT (dir != null, "dir = null");
        if ($assert.ENABLED) $assert.ASSERT (packageVMName != null, "packageVMName = null");
        
        final File packageFile = new File (dir, packageVMName.replace ('/', File.separatorChar));
        
        final File [] listing = packageFile.listFiles (FILE_EXTENSION_FILTER);
        
        if ((listing == null) || (listing.length == 0))
            return Collections.EMPTY_SET;
        else
        {
            final Set result = new HashSet (listing.length);
            for (int f = 0; f < listing.length; ++ f)
            {
                result.add (listing [f].getName ());
            }
            
            return result;
        }
    }
    

    private final File [] m_sourcepath; // never null
    private final Map /* packageVMName:String->CacheEntry */ m_packageCache; // never null
    
    private static final FileExtensionFilter FILE_EXTENSION_FILTER; // set in <clinit>
    
    static
    {
        FILE_EXTENSION_FILTER = new FileExtensionFilter (".java");
    }

} // end of class
// ----------------------------------------------------------------------------
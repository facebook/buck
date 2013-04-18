/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Files.java,v 1.1.1.1.2.1 2004/07/09 01:28:37 vlad_r Exp $
 */
package com.vladium.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class Files
{
    // public: ................................................................
    
    /**
     * No duplicate elimination.
     * 
     * @param atfile
     */
    public static String [] readFileList (final File atfile)
        throws IOException
    {
        if (atfile == null) throw new IllegalArgumentException ("null input: atfile");
        
        List _result = null;
        
        BufferedReader in = null;
        try
        {
            in = new BufferedReader (new FileReader (atfile), 8 * 1024); // uses default encoding
            _result = new LinkedList ();
            
            for (String line; (line = in.readLine ()) != null; )
            {
                line = line.trim ();
                if ((line.length () == 0) || (line.charAt (0) == '#')) continue;
                
                _result.add (line);
            }
        }
        finally
        {
            if (in != null) try { in.close (); } catch (Exception ignore) {}
        }
        
        if ((_result == null) || _result.isEmpty ())
            return IConstants.EMPTY_STRING_ARRAY;
        else
        {
            final String [] result = new String [_result.size ()];
            _result.toArray (result);
            
            return result;
        }
    }
    
    /**
     * Converts an array of path segments to an array of Files. The order
     * of files follows the original order of path segments, except "duplicate"
     * entries are removed. The definition of duplicates depends on 'canonical':
     * <ul>
     *  <li> if 'canonical'=true, the pathnames are canonicalized via {@link #canonicalizePathname}
     *  before they are compared for equality
     *  <li> if 'canonical'=false, the pathnames are compared as case-sensitive strings
     * </ul>
     * 
     * Note that duplicate removal in classpaths affects ClassLoader.getResources().
     * The first mode above makes the most sense, however the last one is what
     * Sun's java.net.URLClassLoader appears to do. Hence the last mode might be
     * necessary for reproducing its behavior in Sun-compatible JVMs.  
     */    
    public static File [] pathToFiles (final String [] path, final boolean canonical)
    {
        if (path == null) throw new IllegalArgumentException ("null input: path");
        if (path.length == 0) return IConstants.EMPTY_FILE_ARRAY;
        
        final List /* Files */ _result = new ArrayList (path.length);
        final Set /* String */ pathnames = new HashSet (path.length);
        
        final String separators = ",".concat (File.pathSeparator);
        
        for (int i = 0; i < path.length; ++ i)
        {
            String segment = path [i];
            if (segment == null) throw new IllegalArgumentException ("null input: path[" + i + "]");
            
            final StringTokenizer tokenizer = new StringTokenizer (segment, separators);
            while (tokenizer.hasMoreTokens ())
            {
                String pathname = tokenizer.nextToken ();
            
                if (canonical) pathname = canonicalizePathname (pathname);
                
                if (pathnames.add (pathname))
                {
                    _result.add (new File (pathname));
                }
            }
        }
        
        final File [] result = new File [_result.size ()];
        _result.toArray (result);
        
        return result;
    }
    
    /**
     * Converts 'pathname' into the canonical OS form. This wrapper function
     * will return the absolute form of 'pathname' if File.getCanonicalPath() fails.
     */
    public static String canonicalizePathname (final String pathname)
    {
        if (pathname == null) throw new IllegalArgumentException ("null input: pathname");
        
        try
        {
            return new File (pathname).getCanonicalPath ();
        }
        catch (Exception e)
        {
            return new File (pathname).getAbsolutePath ();
        }
    }
    
    public static File canonicalizeFile (final File file)
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        try
        {
            return file.getCanonicalFile ();
        }
        catch (Exception e)
        {
            return file.getAbsoluteFile ();
        }
    }

    /**
     * Invariant: (getFileName (file) + getFileExtension (file)).equals (file.getName ()).
     * 
     * @param file File input file descriptor [must be non-null]
     * 
     * @return String file name without the extension [excluding '.' separator]
     * [if 'file' does not appear to have an extension, the full name is returned].
     * 
     * @throws IllegalArgumentException if 'file' is null
     */ 
    public static String getFileName (final File file)
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        final String name = file.getName ();
        int lastDot = name.lastIndexOf ('.');
        if (lastDot < 0) return name;
        
        return name.substring (0, lastDot);
    }
    
    /**
     * Invariant: (getFileName (file) + getFileExtension (file)).equals (file.getName ()).
     * 
     * @param file File input file descriptor [must be non-null]
     * 
     * @return String extension [including '.' separator] or "" if 'file' does not
     * appear to have an extension.
     * 
     * @throws IllegalArgumentException if 'file' is null
     */
    public static String getFileExtension (final File file)
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        final String name = file.getName ();
        int lastDot = name.lastIndexOf ('.');
        if (lastDot < 0) return "";
        
        return name.substring (lastDot);
    }
    
    /**
     * 
     * @param dir [null is ignored]
     * @param file [absolute overrides 'dir']
     * @return
     */
    public static File newFile (final File dir, final File file)
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        if ((dir == null) || file.isAbsolute ()) return file;
        
        return new File (dir, file.getPath ());
    }
    
    /**
     * 
     * @param dir [null is ignored]
     * @param file [absolute overrides 'dir']
     * @return
     */
    public static File newFile (final File dir, final String file)
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        final File fileFile  = new File (file);
        if ((dir == null) || fileFile.isAbsolute ()) return fileFile;
        
        return new File (dir, file);
    }
    
    /**
     * 
     * @param dir [null is ignored]
     * @param file [absolute overrides 'dir']
     * @return
     */
    public static File newFile (final String dir, final String file)
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        final File fileFile  = new File (file);
        if ((dir == null) || fileFile.isAbsolute ()) return fileFile;
        
        return new File (dir, file);
    }
    
    /**
     * Renames 'source' to 'target' [intermediate directories are created if necessary]. If
     * 'target' exists and 'overwrite' is false, the method is a no-op. No exceptions are
     * thrown except for when input is invalid. If the operation fails half-way it can leave
     * some file system artifacts behind.
     * 
     * @return true iff the renaming was actually performed. 
     * 
     * @param source file descriptor [file must exist]
     * @param target target file descriptor [an existing target may get deleted
     * if 'overwrite' is true]
     * @param overwrite if 'true', forces an existing target to be deleted 
     * 
     * @throws IllegalArgumentException if 'source' is null or file does not exist
     * @throws IllegalArgumentException if 'target' is null
     */     
    public static boolean renameFile (final File source, final File target, final boolean overwrite)
    {
        if ((source == null) || ! source.exists ())
            throw new IllegalArgumentException ("invalid input source: [" + source + "]");
        if (target == null)
            throw new IllegalArgumentException ("null input: target");
        
        final boolean targetExists;
        if (! (targetExists = target.exists ()) || overwrite)
        {
            if (targetExists)
            {
                // need to delete the target first or the rename will fail:
                target.delete (); // not checking the result here: let the rename fail later
            }
            else
            {
                // note that File.renameTo() does not create intermediate directories on demand:
                final File targetDir = target.getParentFile ();
                if ((targetDir != null) && ! targetDir.equals (source.getParentFile ()))
                    targetDir.mkdirs (); // TODO: clean this up on failure?
            }
            
            // note: this can fail for a number of reasons, including the target
            // being on a different drive/file system:
            return source.renameTo (target);
        }
        
        return false;
    }
    
    /**
     * A slightly stricter version of File.createTempFile() in J2SDK 1.3: it requires
     * that the caller provide an existing parent directory for the temp file.
     * This defers to File.createTempFile (prefix, extension, parentDir) after
     * normalizing 'extension'.<P>
     * 
     * MT-safety: if several threads use this API concurrently, the temp files
     * created are guaranteed to get created without any collisions and correspond
     * to files that did not exist before. However, if such a temp file is deleted
     * at a later point, this method may reuse its file name. These MT-safety
     * guarantees do not hold if files are created in the same directory outside
     * of this method.
     * 
     * @param parentDir parent dir for the temp file [may not be null and must exist]
     * @param prefix prefix pattern for the temp file name [only the first 3
     * chars are guaranteed to be used]
     * @param extension pattern for the temp file name [null is equivalient to
     * ".tmp"; this is always normalized to start with "."; only the first 3
     * non-"." chars are guaranteed to be used]
     * 
     * @return writeable temp file descriptor [incorporates 'parentDir' in its pathname]
     * @throws IOException if a temp file could not be created
     */
    public static File createTempFile (final File parentDir, final String prefix, String extension)
        throws IOException
    {
        if ((parentDir == null) || ! parentDir.exists ())
            throw new IllegalArgumentException ("invalid parent directory: [" + parentDir + "]");
        if ((prefix == null) || (prefix.length () < 3))
            throw new IllegalArgumentException ("null or less than 3 chars long: " + prefix);
        
        if (extension == null) extension = ".tmp";
        else if (extension.charAt (0) != '.') extension = ".".concat (extension);
        
        return File.createTempFile (prefix, extension, parentDir);
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private Files () {} // prevent subclassing

} // end of class
// ----------------------------------------------------------------------------
/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ClassPathProcessorST.java,v 1.1.1.1.2.1 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.parser.ClassDefParser;
import com.vladium.logging.Logger;
import com.vladium.util.ByteArrayOStream;
import com.vladium.util.Files;
import com.vladium.util.IPathEnumerator;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.data.IMetaData;
import com.vladium.emma.filter.IInclExclFilter;
import com.vladium.emma.instr.InstrVisitor;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ClassPathProcessorST implements IPathEnumerator.IPathHandler, IAppErrorCodes
{
    // public: ................................................................
    
    public void run ()
    {
        long start = System.currentTimeMillis ();
        
        // construct instr path enumerator [throws on illegal input only]:
        final IPathEnumerator enumerator = IPathEnumerator.Factory.create (m_path, m_canonical, this);
        
        // allocate I/O buffers:
        m_readbuf = new byte [BUF_SIZE]; // don't reuse this across run() calls to reset it to the original size
        m_readpos = 0;
        m_baos = new ByteArrayOStream (BUF_SIZE); // don't reuse this across run() calls to reset it to the original size
        
        if (m_log.atINFO ())
        {
            m_log.info ("processing classpath ...");
        }
        
        // actual work is driven by the path enumerator:
        try
        {
            enumerator.enumerate ();
        }
        catch (IOException ioe)
        {
            throw new EMMARuntimeException (INSTR_IO_FAILURE, ioe);
        }
        
        if (m_log.atINFO ())
        {
            final long end = System.currentTimeMillis ();
            
            m_log.info ("[" + m_classCount + " class(es) processed in " + (end - start) + " ms]");
        }
    }
    
    // IPathEnumerator.IPathHandler:

    public void handleArchiveStart (final File parentDir, final File archive, final Manifest manifest)
    {
        m_archiveFile = Files.newFile (parentDir, archive.getPath ());
    }

    public void handleArchiveEntry (final JarInputStream in, final ZipEntry entry)
    {
        if (m_log.atTRACE2 ()) m_log.trace2 ("handleArchiveEntry", "[" + entry.getName () + "]");
        
        final String name = entry.getName ();
        final String lcName = name.toLowerCase ();
        
        if (lcName.endsWith (".class"))
        {
            final String className = name.substring (0, name.length () - 6).replace ('/', '.');
            
            if ((m_coverageFilter == null) || m_coverageFilter.included (className))
            {
                String srcURL = null;
                InputStream clsin = null;
                try
                {
                    readZipEntry (in, entry);
                    
                    srcURL = "jar:".concat (m_archiveFile.toURL ().toExternalForm ()).concat ("!/").concat (name);
                }
                catch (FileNotFoundException fnfe)
                {
                    // ignore: this should never happen
                    if ($assert.ENABLED)
                    {
                        fnfe.printStackTrace (System.out);
                    }
                }
                catch (IOException ioe)
                {
                    // TODO: error code
                    throw new EMMARuntimeException (ioe);
                }
                finally
                {
                    if (clsin != null)
                        try
                        {
                            clsin.close ();
                            clsin = null;
                        }
                        catch (Exception e)
                        {
                            // TODO: error code
                            throw new EMMARuntimeException (e);
                        }
                }
                
                // [original class def read into m_readbuf]
                
                try
                {
                    ClassDef clsDef = ClassDefParser.parseClass (m_readbuf, m_readpos);
                    if (! clsDef.isInterface ()) ++ m_classCount;
                    
                    m_visitor.process (clsDef, false, false, true, m_instrResult); // get metadata only
                    clsDef = null;
                    
                    boolean cacheClassDef = true;
                  
                    if (m_instrResult.m_descriptor != null)
                    {
                        // do not overwrite existing descriptors to support "first
                        // in the classpath wins" semantics:
                        
                        if (! m_mdata.add (m_instrResult.m_descriptor, false))
                           cacheClassDef = false; 
                    }
                    
                    if (cacheClassDef && (m_cache != null))
                    {
                        final byte [] bytes = new byte [m_readpos];
                        System.arraycopy (m_readbuf, 0, bytes, 0, m_readpos);
                        
                        m_cache.put (className, new ClassPathCacheEntry (bytes, srcURL));
                    }
                }
                catch (IOException ioe)
                {
                    // TODO: error code
                    throw new EMMARuntimeException (ioe);
                }
            }
        }
    }

    public void handleArchiveEnd (final File parentDir, final File archive)
    {
        m_archiveFile = null;
    }


    public void handleDirStart (final File pathDir, final File dir)
    {
        // do nothing
    }

    public void handleFile (final File pathDir, final File file)
    {
        if (m_log.atTRACE2 ()) m_log.trace2 ("handleFile", "[" + pathDir + "] [" + file + "]");
        
        final String name = file.getPath ();
        final String lcName = name.toLowerCase ();
        
        if (lcName.endsWith (".class"))
        {
            final String className = name.substring (0, name.length () - 6).replace (File.separatorChar, '.');
            
            if ((m_coverageFilter == null) || m_coverageFilter.included (className))
            {
                String srcURL = null;
                InputStream clsin = null;
                try
                {
                    final File inFile = Files.newFile (pathDir, file.getPath ());
                    readFile (inFile);
                    
                    srcURL = inFile.toURL ().toExternalForm ();
                }
                catch (FileNotFoundException fnfe)
                {
                    // ignore: this should never happen
                    if ($assert.ENABLED)
                    {
                        fnfe.printStackTrace (System.out);
                    }
                }
                catch (IOException ioe)
                {
                    // TODO: error code
                    throw new EMMARuntimeException (ioe);
                }
                finally
                {
                    if (clsin != null)
                        try
                        {
                            clsin.close ();
                            clsin = null;
                        }
                        catch (Exception e)
                        {
                            // TODO: error code
                            throw new EMMARuntimeException (e);
                        }
                }
                
                // [original class def read into m_readbuf]
                
                try
                {
                    ClassDef clsDef = ClassDefParser.parseClass (m_readbuf, m_readpos);
                    if (! clsDef.isInterface ()) ++ m_classCount;
                    
                    m_visitor.process (clsDef, false, false, true, m_instrResult); // get metadata only
                    clsDef = null;
                    
                    
                    boolean cacheClassDef = true;
                  
                    if (m_instrResult.m_descriptor != null)
                    {
                        // do not overwrite existing descriptors to support "first
                        // in the classpath wins" semantics:
                        
                        if (! m_mdata.add (m_instrResult.m_descriptor, false))
                           cacheClassDef = false; 
                    }
                    
                    if (cacheClassDef && (m_cache != null))
                    {
                        final byte [] bytes = new byte [m_readpos];
                        System.arraycopy (m_readbuf, 0, bytes, 0, m_readpos);
                        
                        m_cache.put (className, new ClassPathCacheEntry (bytes, srcURL));
                    }
                }
                catch (IOException ioe)
                {
                    // TODO: error code
                    throw new EMMARuntimeException (ioe);
                }
            }
        }
    }

    public void handleDirEnd (final File pathDir, final File dir)
    {
        // do nothing
    }

    // protected: .............................................................

    // package: ...............................................................


    /*
     * null 'cache' indicates to only populate the metadata and not bother with
     * caching instrumented class defs
     */
    ClassPathProcessorST (final File [] path, final boolean canonical,
                          final IMetaData mdata, final IInclExclFilter filter,
                          final Map cache)
    {
        if (path == null) throw new IllegalArgumentException ("null input: path");
        if (mdata == null) throw new IllegalArgumentException ("null input: mdata");
        
        m_path = path;
        m_canonical = canonical;
        m_mdata = mdata;
        m_coverageFilter = filter;
        m_cache = cache; // can be null
        m_visitor = new InstrVisitor (mdata.getOptions ());
        m_instrResult = new InstrVisitor.InstrResult ();
        
        m_log = Logger.getLogger ();
    }

    // private: ...............................................................


    /*
     * Reads into m_readbuf (m_readpos is updated correspondingly)
     */
    private void readFile (final File file)
        throws IOException
    {
        final int length = (int) file.length ();
        
        ensureReadCapacity (length);
        
        InputStream in = null;
        try
        {
            in = new FileInputStream (file);
            
            int totalread = 0;
            for (int read;
                 (totalread < length) && (read = in.read (m_readbuf, totalread, length - totalread)) >= 0;
                 totalread += read);
            m_readpos = totalread;
        } 
        finally
        {
            if (in != null) try { in.close (); } catch (Exception ignore) {} 
        }
    }
    
    /*
     * Reads into m_readbuf (m_readpos is updated correspondingly)
     */
    private void readZipEntry (final ZipInputStream in, final ZipEntry entry)
        throws IOException
    {
        final int length = (int) entry.getSize (); // can be -1 if unknown
        
        if (length >= 0)
        {
            ensureReadCapacity (length);
            
            int totalread = 0;
            for (int read;
                 (totalread < length) && (read = in.read (m_readbuf, totalread, length - totalread)) >= 0;
                 totalread += read);
            m_readpos = totalread;
        }
        else
        {
            ensureReadCapacity (BUF_SIZE);
            
            m_baos.reset ();
            for (int read; (read = in.read (m_readbuf)) >= 0; m_baos.write (m_readbuf, 0, read));
            
            m_readbuf = m_baos.copyByteArray ();
            m_readpos = m_readbuf.length;
        }
    }   
 
    private void ensureReadCapacity (final int capacity)
    {
        if (m_readbuf.length < capacity)
        {
            final int readbuflen = m_readbuf.length;
            m_readbuf = null;
            m_readbuf = new byte [Math.max (readbuflen << 1, capacity)];
        }
    }


    private final File [] m_path; // never null
    private final boolean m_canonical;
    private final IMetaData m_mdata; // never null
    private final IInclExclFilter m_coverageFilter; // can be null
    private final InstrVisitor m_visitor;
    private final InstrVisitor.InstrResult m_instrResult;
    private final Map /* classJavaName:String -> ClassPathCacheEntry */ m_cache; // can be null
    
    private final Logger m_log; // this class is instantiated and used on a single thread
    
    private int m_classCount;
    
    private byte [] m_readbuf;
    private int m_readpos;
    private ByteArrayOStream m_baos; // TODO: code to guard this from becoming too large
    
    private File m_archiveFile;
    
    private static final int BUF_SIZE = 32 * 1024;

} // end of class
// ----------------------------------------------------------------------------
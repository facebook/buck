/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: InstrProcessorST.java,v 1.1.1.1.2.3 2004/07/16 23:32:28 vlad_r Exp $
 */
package com.vladium.emma.instr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Date;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.vladium.jcd.cls.ClassDef;
import com.vladium.jcd.compiler.ClassWriter;
import com.vladium.jcd.parser.ClassDefParser;
import com.vladium.logging.Logger;
import com.vladium.util.ByteArrayOStream;
import com.vladium.util.Descriptors;
import com.vladium.util.Files;
import com.vladium.util.IPathEnumerator;
import com.vladium.util.IProperties;
//import com.vladium.util.Profiler;
import com.vladium.util.Property;
import com.vladium.util.asserts.$assert;
import com.vladium.util.exception.Exceptions;
//import com.vladium.utils.ObjectSizeProfiler;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.data.CoverageOptions;
import com.vladium.emma.data.CoverageOptionsFactory;
import com.vladium.emma.data.DataFactory;
import com.vladium.emma.data.IMetaData;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
final class InstrProcessorST extends InstrProcessor
                             implements IAppErrorCodes
{
    // public: ................................................................

    // TODO: performance of 'copy' mode could be improved by pushing dir filtering
    // all the way to the path enumerator [although dir listing is reasonably fast] 
        
    
    // IPathEnumerator.IPathHandler:
    
    
    public final void handleArchiveStart (final File parentDir, final File archive, final Manifest manifest)
    {
        final Logger log = m_log;
        if (log.atTRACE2 ()) log.trace2 ("handleArchiveStart", "[" + parentDir + "] [" + archive + "]");
        
        // TODO: pass manifest into this callback, if any
        // TODO: detect if manifest corresonds to a previously intrumented archive already ?
        
        if (DO_DEPENDS_CHECKING)
        {
            final File fullArchiveFile = Files.newFile (parentDir, archive);
            m_currentArchiveTS = fullArchiveFile.lastModified ();
            
            if ($assert.ENABLED) $assert.ASSERT (m_currentArchiveTS > 0, "invalid ts: " + m_currentArchiveTS);
        }
        
        if ((m_outMode == OutMode.OUT_MODE_FULLCOPY) || (m_outMode == OutMode.OUT_MODE_OVERWRITE))
        {
            final Manifest outManifest = manifest != null
                ? new Manifest (manifest) // shallow copy
                : new Manifest (); 
            
            // set some basic main attributes:
            
            final Attributes mainAttrs = outManifest.getMainAttributes ();
            if (manifest == null) mainAttrs.put (Attributes.Name.MANIFEST_VERSION,  "1.0");
            mainAttrs.put (new Attributes.Name ("Created-By"), IAppConstants.APP_NAME + " v" + IAppConstants.APP_VERSION_WITH_BUILD_ID_AND_TAG);
                            
            // note: Manifest makes these 72-char-safe
            
            mainAttrs.put (Attributes.Name.IMPLEMENTATION_TITLE,  "instrumented version of [" + archive.getAbsolutePath () + "]");
            mainAttrs.put (Attributes.Name.SPECIFICATION_TITLE,  "instrumented on " + new Date (m_timeStamp) + " [" + Property.getSystemFingerprint () + "]");
            
            // TODO: remove entries related to signing            
        
            if (m_outMode == OutMode.OUT_MODE_FULLCOPY)
            {
                // create an identically named artive in outdir/lib [the stream is
                // closed in the archive end event handler]:
                
                try
                {
                    final OutputStream out = new FileOutputStream (getFullOutFile (parentDir, archive, IN_LIB));
                    
                    m_archiveOut = outManifest != null ? new JarOutputStream (out, outManifest) : new JarOutputStream (out);
                }
                catch (IOException ioe)
                {
                    // TODO: error code
                    throw new EMMARuntimeException (ioe);
                }
            }
            else if (m_outMode == OutMode.OUT_MODE_OVERWRITE)
            {
                // create a temp file in the same dir [moved into the original one
                // in the archive end event handler]:
                
                m_origArchiveFile = Files.newFile (parentDir, archive);
                
                // length > 3:
                final String archiveName = Files.getFileName (archive) + IAppConstants.APP_NAME_LC;
                final String archiveExt = EMMAProperties.PROPERTY_TEMP_FILE_EXT;
                
                try
                {
                    m_tempArchiveFile = Files.createTempFile (parentDir, archiveName, archiveExt);
                    if (log.atTRACE2 ()) log.trace2 ("handleArchiveStart", "created temp archive [" + m_tempArchiveFile.getAbsolutePath () + "]");
                    
                    final OutputStream out = new FileOutputStream (m_tempArchiveFile);
                    
                    m_archiveOut = outManifest != null ? new JarOutputStream (out, outManifest) : new JarOutputStream (out);
                }
                catch (IOException ioe)
                {
                    // TODO: error code
                    throw new EMMARuntimeException (ioe);
                }
            }
        }
    }

    public final void handleArchiveEntry (final JarInputStream in, final ZipEntry entry)
    {
        final Logger log = m_log;
        if (log.atTRACE2 ()) log.trace2 ("handleArchiveEntry", "[" + entry.getName () + "]");
        
        final String name = entry.getName ();
        final String lcName = name.toLowerCase ();

        final boolean notcopymode = (m_outMode == OutMode.OUT_MODE_FULLCOPY) || (m_outMode == OutMode.OUT_MODE_OVERWRITE);
        
        boolean copyEntry = false;

        if (lcName.endsWith (".class"))
        {
            final String className = name.substring (0, name.length () - 6).replace ('/', '.');
            
            // it is possible that a class with this name has already been processed;
            // however, we can't skip it here because there is no guarantee that
            // the runtime classpath will be identical to the instrumentation path
            
            // [the metadata will still contain only a single entry for a class with
            // this name: it is the responsibility of the user to ensure that both
            // files represent the same class; in the future I might use a more
            // robust internal strategy that uses something other than a class name
            // as a metadata key]
            
            if ((m_coverageFilter == null) || m_coverageFilter.included (className))
            {
                InputStream clsin = null;
                try
                {
                    File outFile = null;
                    File fullOutFile = null;
                    
                    if (DO_DEPENDS_CHECKING)
                    {
                        // in 'copy' mode 
                        
                        if (m_outMode == OutMode.OUT_MODE_COPY)
                        {
                            outFile = new File (className.replace ('.', File.separatorChar).concat (".class"));
                            fullOutFile = getFullOutFile (null, outFile, IN_CLASSES);
                            
                            // if we already processed this class name within this instrumentor
                            // run, skip duplicates in copy mode: 
                            
                            if (m_mdata.hasDescriptor (Descriptors.javaNameToVMName (className)))
                                return;
                            
                            // BUG_SF989071: using outFile here instead resulted in
                            // a zero result regardless of whether the target existed or not
                            final long outTimeStamp = fullOutFile.lastModified (); // 0 if 'fullOutFile' does not exist or if an I/O error occurs
                             
                            if (outTimeStamp > 0)
                            {
                                long inTimeStamp = entry.getTime (); // can return -1
                                if (inTimeStamp < 0) inTimeStamp = m_currentArchiveTS; // default to the archive file timestamp
                                
                                if ($assert.ENABLED) $assert.ASSERT (inTimeStamp > 0);
                                
                                if (inTimeStamp <= outTimeStamp)
                                {
                                    if (log.atVERBOSE ()) log.verbose ("destination file [" + outFile + "] skipped: more recent than the source");
                                    return;
                                }
                            }
                        }
                    }
                    
                    readZipEntry (in, entry);
                    
                    final ClassDef clsDef = ClassDefParser.parseClass (m_readbuf, m_readpos);
                    
                    m_visitor.process (clsDef, m_outMode == OutMode.OUT_MODE_OVERWRITE, true, true, m_instrResult);
                    if (m_instrResult.m_instrumented)
                    {
                        if ($assert.ENABLED) $assert.ASSERT (m_instrResult.m_descriptor != null, "no descriptor created for an instrumented class");
                        
                        ++ m_classInstrs;
                        
                        // update metadata [if this class has not been seen before]:
                        
                        m_mdata.add (m_instrResult.m_descriptor, false);
                        
                        // class def modified: write it to an array and submit a write job
                        
                        m_baos.reset ();
                        ClassWriter.writeClassTable (clsDef, m_baos);
                        
                        if (notcopymode)
                        {
                            // [destination is a zip entry]
                            
                            entry.setTime (m_timeStamp);
                            addJob (new EntryWriteJob (m_archiveOut, m_baos.copyByteArray (), entry, false));
                        }
                        else // copy mode
                        {
                            // [destination is a file]
                            
                            if (! DO_DEPENDS_CHECKING) // this block is just a complement to the one above (where fullOutFile is inited)
                            {
                                outFile = new File (className.replace ('.', File.separatorChar).concat (".class"));
                                fullOutFile = getFullOutFile (null, outFile, IN_CLASSES);
                            }
                        
                            addJob (new FileWriteJob (fullOutFile, m_baos.copyByteArray (), true));
                        }
                    }   
                    else if (notcopymode)
                    {
                        // original class def already read into m_readbuf:
                        // clone the array and submit an entry write job
                         
                        final byte [] data = new byte [m_readpos];
                        System.arraycopy (m_readbuf, 0, data, 0, data.length);
                        ++ m_classCopies;
                        
                        entry.setTime (m_timeStamp);
                        addJob (new EntryWriteJob (m_archiveOut, data, entry, true));
                    }
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
                        }
                        catch (Exception e)
                        {
                            // TODO: error code
                            throw new EMMARuntimeException (e);
                        }
                }
            }
            else
            {
                // copy excluded .class entries in full copy and overwrite modes:
                copyEntry = notcopymode;
            }
        }
        else
        {
            // copy non-.class entries in full copy and overwrite modes:
            copyEntry = notcopymode;
            
            // skipping these entries here is important: this is done as a complement
            // to Sun jar API workarounds as detailed in PathEnumerator.enumeratePathArchive():
            
            if (copyEntry && name.equalsIgnoreCase ("META-INF/"))
                copyEntry = false;
            if (copyEntry && name.equalsIgnoreCase (JarFile.MANIFEST_NAME))
                copyEntry = false;
                
            // TODO: skip signature-related entries (.SF and .RSA/.DSA/.PGP)
        }
        
        if (copyEntry)
        {
            try
            {
                readZipEntry (in, entry);
                
                final byte [] data = new byte [m_readpos];
                System.arraycopy (m_readbuf, 0, data, 0, data.length);
                ++ m_classCopies;
                
                entry.setTime (m_timeStamp);
                addJob (new EntryWriteJob (m_archiveOut, data, entry, true));
            }
            catch (IOException ioe)
            {
                // TODO: error code
                throw new EMMARuntimeException (ioe);
            }
        }
    }
        
    public final void handleArchiveEnd (final File parentDir, final File archive)
    {
        final Logger log = m_log;
        if (log.atTRACE2 ()) log.trace2 ("handleArchiveEnd", "[" + parentDir + "] [" + archive + "]");
        
        m_currentArchiveTS = Long.MAX_VALUE;
        
        if ((m_outMode == OutMode.OUT_MODE_FULLCOPY) || (m_outMode == OutMode.OUT_MODE_OVERWRITE))
        {
            try
            {
                drainJobQueue (); // drain the queue before closing the archive
                
                m_archiveOut.flush ();
                m_archiveOut.close ();
                m_archiveOut = null;
            }
            catch (IOException ioe)
            {
                // TODO: error code
                throw new EMMARuntimeException (ioe);
            }
            
            // in overwrite mode replace the original archive with the temp archive:
             
            if (m_outMode == OutMode.OUT_MODE_OVERWRITE)
            {
                if (! Files.renameFile (m_tempArchiveFile, m_origArchiveFile, true)) // overwrite the original archive
                {
                    // TODO: disable temp file cleanup in this case so that the user
                    // could do it manually later?
                    
                    // error code
                    throw new EMMARuntimeException ("could not rename temporary file [" + m_tempArchiveFile + "] to [" + m_origArchiveFile + "]: make sure the original file is not locked and can be deleted");
                }
                else
                {
                    if (log.atTRACE2 ()) log.trace2 ("handleArchiveEnd", "renamed temp archive [" + m_tempArchiveFile.getAbsolutePath () + "] to [" + m_origArchiveFile + "]");
                    m_origArchiveFile = m_tempArchiveFile = null;
                }
            }
        }
    }


    public final void handleDirStart (final File pathDir, final File dir)
    {
        final Logger log = m_log;
        if (log.atTRACE2 ()) log.trace2 ("handleDirStart", "[" + pathDir + "] [" + dir + "]");
        
        // in full copy mode, create all dirs here; in copy mode, do it as part
        // of writing each individual file:
        
        if (m_outMode == OutMode.OUT_MODE_FULLCOPY)
        {
            final File saveDir = new File (getFullOutDir (pathDir, IN_CLASSES), dir.getPath ());
            createDir (saveDir, true);
        }
    }

    public final void handleFile (final File pathDir, final File file)
    {
        final Logger log = m_log;
        if (log.atTRACE2 ()) log.trace2 ("handleFile", "[" + pathDir + "] [" + file + "]");
        
        final String name = file.getPath ();
        final String lcName = name.toLowerCase ();

        final boolean fullcopymode = (m_outMode == OutMode.OUT_MODE_FULLCOPY);
        final boolean mkdir = (m_outMode == OutMode.OUT_MODE_COPY);
        

        boolean copyFile = false;

        if (lcName.endsWith (".class"))
        {
            final String className = name.substring (0, name.length () - 6).replace (File.separatorChar, '.');
            
            // it is possible that a class with this name has already been processed;
            // however, we can't skip it here because there is no guarantee that
            // the runtime classpath will be identical to the instrumentation path
            
            // [the metadata will still contain only a single entry for a class with
            // this name: it is the responsibility of the user to ensure that both
            // files represent the same class; in the future I might use a more
            // robust internal strategy that uses something other than a class name
            // as a metadata key]
            
            if ((m_coverageFilter == null) || m_coverageFilter.included (className))
            {
                InputStream clsin = null;
                try
                {
                    final File inFile = Files.newFile (pathDir, file.getPath ());
                    final File fullOutFile = getFullOutFile (pathDir, file, IN_CLASSES);
                    
                    if (DO_DEPENDS_CHECKING)
                    {
                        if (m_outMode == OutMode.OUT_MODE_COPY)
                        {
                            // if we already processed this class name within this instrumentor
                            // run, skip duplicates in copy mode: 
                            
                            if (m_mdata.hasDescriptor (Descriptors.javaNameToVMName (className)))
                                return;
                            
                            // otherwise, instrument only if the dest file is out of date
                            // wrt to the source file:
                            
                            final long outTimeStamp = fullOutFile.lastModified (); // 0 if 'fullOutFile' does not exist or if an I/O error occurs
                             
                            if (outTimeStamp > 0)
                            {
                                final long inTimeStamp = inFile.lastModified ();
                                
                                if (inTimeStamp <= outTimeStamp)
                                {
                                    if (log.atVERBOSE ()) log.verbose ("destination file [" + fullOutFile + "] skipped: more recent that the source file");
                                    return;
                                }
                            }
                        }
                    }
                    
                    readFile (inFile);
                    
                    ClassDef clsDef = ClassDefParser.parseClass (m_readbuf, m_readpos);

                    // in non-overwrite modes, bail if src file already instrumented:
                    m_visitor.process (clsDef, m_outMode == OutMode.OUT_MODE_OVERWRITE, true, true, m_instrResult);
                    if (m_instrResult.m_instrumented)
                    {
                        if ($assert.ENABLED) $assert.ASSERT (m_instrResult.m_descriptor != null, "no descriptor created for an instrumented class");
                        
                        ++ m_classInstrs;
                        
                        // update metadata [if this class has not been seen before]:
       
//       ObjectSizeProfiler.SizeProfile profile = ObjectSizeProfiler.profile (m_instrResult.m_descriptor, true);
//       System.out.println (clsDef.getName () + " metadata:");
//       System.out.println (profile.root ().dump (0.2));
                        
                        m_mdata.add (m_instrResult.m_descriptor, false);

                        // class def modified: write it to an array and submit a write job
                        
                        m_baos.reset ();
                        ClassWriter.writeClassTable (clsDef, m_baos);
                        clsDef = null;
                                                
                        final byte [] outdata = m_baos.copyByteArray ();
                        
                        addJob (new FileWriteJob (fullOutFile, outdata, mkdir));
                    }   
                    else if (fullcopymode)
                    {
                        // original class def already read into m_readbuf:
                        // clone the array and submit a file write job
                        
                        clsDef = null;
                        
                        final byte [] outdata = new byte [m_readpos];
                        System.arraycopy (m_readbuf, 0, outdata, 0, m_readpos);
                        ++ m_classCopies;
                        
                        addJob (new FileWriteJob (fullOutFile, outdata, mkdir));
                    }
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
                        }
                        catch (Exception e)
                        {
                            // TODO: error code
                            throw new EMMARuntimeException (e);
                        }
                }
            }
            else
            {
                // copy excluded .class files in full copy mode:
                copyFile = fullcopymode;
            }
        }
        else
        {
            // copy non-.class files in full copy mode:
            copyFile = fullcopymode;
        }
        
        if (copyFile)
        {
            try
            {
                final File inFile = Files.newFile (pathDir, file.getPath ());
                readFile (inFile);
                
                final byte [] data = new byte [m_readpos];
                System.arraycopy (m_readbuf, 0, data, 0, data.length);
                ++ m_classCopies;
                
                final File outFile = getFullOutFile (pathDir, file, IN_CLASSES);
    
                addJob (new FileWriteJob (outFile, data, mkdir));
            }
            catch (IOException ioe)
            {
                // TODO: error code
                throw new EMMARuntimeException (ioe);
            }
        }
    }

    public final void handleDirEnd (final File pathDir, final File dir)
    {
        final Logger log = m_log;
        if (log.atTRACE2 ()) log.trace2 ("handleDirEnd", "[" + pathDir + "] [" + dir + "]");
        
        // in overwrite mode, flush the job queue before going to the next directory:
        
        if (m_outMode == OutMode.OUT_MODE_OVERWRITE)
        {
            try
            {
                drainJobQueue ();
            }
            catch (IOException ioe)
            {
                // TODO: error code
                throw new EMMARuntimeException (ioe);
            }
        }
    }
    
    // protected: .............................................................
    
    
    protected void reset ()
    {
        m_visitor = null;
        m_mdata = null;
        m_readbuf = null;
        m_baos = null;
 
        for (int j = 0; j < m_jobs.length; ++ j) m_jobs [j] = null;
               
        if (CLEANUP_TEMP_ARCHIVE_ON_ERRORS)
        {
            if (m_archiveOut != null) 
                try { m_archiveOut.close (); } catch (Exception ignore) {} // unlock the file descriptor for deletion
            
            if (m_tempArchiveFile != null)
                m_tempArchiveFile.delete ();
        }
        
        m_archiveOut = null;
        m_origArchiveFile = null;
        m_tempArchiveFile = null;
        
        super.reset ();
    }
    
    protected void _run (final IProperties toolProperties)
    {
        final Logger log = m_log;

        final boolean verbose = log.atVERBOSE ();
        if (verbose)
        {
            log.verbose (IAppConstants.APP_VERBOSE_BUILD_ID);
            
            // [assertion: m_instrPath != null]
            log.verbose ("instrumentation path:");
            log.verbose ("{");
            for (int p = 0; p < m_instrPath.length; ++ p)
            {
                final File f = m_instrPath [p];
                final String nonexistent = f.exists () ? "" : "{nonexistent} ";
                
                log.verbose ("  " + nonexistent + f.getAbsolutePath ());
            }
            log.verbose ("}");
            
            // [assertion: m_outMode != null]
            log.verbose ("instrumentation output mode: " + m_outMode);
        }
        else
        {
            log.info ("processing instrumentation path ...");
        }
        
        RuntimeException failure = null;
        try
        {
            long start = System.currentTimeMillis ();
            m_timeStamp = start;
            
            // construct instr path enumerator [throws on illegal input only]:
            final IPathEnumerator enumerator = IPathEnumerator.Factory.create (m_instrPath, m_canonical, this);
            
            // create out dir(s):
            {
                if (m_outMode != OutMode.OUT_MODE_OVERWRITE) createDir (m_outDir, true);
                
                if ((m_outMode == OutMode.OUT_MODE_FULLCOPY))
                {
                    final File classesDir = Files.newFile (m_outDir, CLASSES);
                    createDir (classesDir, false); // note: not using mkdirs() here
                    
                    final File libDir = Files.newFile (m_outDir, LIB);
                    createDir (libDir, false); // note: not using mkdirs() here
                }
            }
            
            // get the data out settings [note: this is not conditioned on m_dumpRawData]:
            File mdataOutFile = m_mdataOutFile;
            Boolean mdataOutMerge = m_mdataOutMerge;
            {
                if (mdataOutFile == null)
                    mdataOutFile = new File (toolProperties.getProperty (EMMAProperties.PROPERTY_META_DATA_OUT_FILE,
                                                                         EMMAProperties.DEFAULT_META_DATA_OUT_FILE));
                
                if (mdataOutMerge == null)
                {
                    final String _dataOutMerge = toolProperties.getProperty (EMMAProperties.PROPERTY_META_DATA_OUT_MERGE,
                                                                             EMMAProperties.DEFAULT_META_DATA_OUT_MERGE.toString ());
                    mdataOutMerge = Property.toBoolean (_dataOutMerge) ? Boolean.TRUE : Boolean.FALSE;
                } 
            }
            
            if (verbose)
            {
                log.verbose ("metadata output file: " + mdataOutFile.getAbsolutePath ());
                log.verbose ("metadata output merge mode: " + mdataOutMerge);
            }
                        
            // TODO: can also register an exit hook to clean up temp files, but this is low value
            
            // allocate I/O buffers:
            m_readbuf = new byte [BUF_SIZE]; // don't reuse this across run() calls to reset it to the original size
            m_readpos = 0;
            m_baos = new ByteArrayOStream (BUF_SIZE); // don't reuse this across run() calls to reset it to the original size
            
            // reset job queue position: 
            m_jobPos = 0;
            
            m_currentArchiveTS = Long.MAX_VALUE;

            final CoverageOptions options = CoverageOptionsFactory.create (toolProperties);            
            m_visitor = new InstrVisitor (options); // TODO: reuse this?
            
            m_mdata = DataFactory.newMetaData (options);
            
            // actual work is driven by the path enumerator:
            try
            {
                enumerator.enumerate ();
                drainJobQueue ();
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (INSTR_IO_FAILURE, ioe);
            }
                       
            if (log.atINFO ())
            {
                final long end = System.currentTimeMillis ();
                
                log.info ("instrumentation path processed in " + (end - start) + " ms");
                log.info ("[" + m_classInstrs + " class(es) instrumented, " + m_classCopies + " resource(s) copied]");
            }
            
            // persist metadata:
            try
            {
                // TODO: create an empty file earlier to catch any errors sooner? [to avoid scenarios where a user waits throught the entire instr run to find out the file could not be written to]
                
                if ($assert.ENABLED) $assert.ASSERT (mdataOutFile != null, "m_metadataOutFile is null");
                
                if (verbose)
                {
                    if (m_mdata != null)
                    {
                        log.verbose ("metadata contains " + m_mdata.size () + " entries");
                    }
                }
                
                if (m_mdata.isEmpty ())
                {
                    log.info ("no output created: metadata is empty");
                }
                else
                {
                    start = System.currentTimeMillis ();
                    DataFactory.persist (m_mdata, mdataOutFile, mdataOutMerge.booleanValue ());
                    final long end = System.currentTimeMillis ();
                    
                    if (log.atINFO ())
                    {
                        log.info ("metadata " + (mdataOutMerge.booleanValue () ? "merged into" : "written to") + " [" + mdataOutFile.getAbsolutePath () + "] {in " + (end - start) + " ms}");
                    }
                }
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (OUT_IO_FAILURE, new Object [] {mdataOutFile.getAbsolutePath ()}, ioe);
            }
        }
        catch (SecurityException se)
        {
            failure = new EMMARuntimeException (SECURITY_RESTRICTION, new String [] {IAppConstants.APP_NAME}, se);
        }
        catch (RuntimeException re)
        {
            failure = re;
        }
        finally
        {
            reset ();
        }
        
        if (failure != null)
        {
            if (Exceptions.unexpectedFailure (failure, EXPECTED_FAILURES))
            {
                throw new EMMARuntimeException (UNEXPECTED_FAILURE,
                                                new Object [] {failure.toString (), IAppConstants.APP_BUG_REPORT_LINK},
                                                failure);
            }
            else
                throw failure;
        }
    }

    // package: ...............................................................
    
    
    InstrProcessorST ()
    {
        m_jobs = new Job [JOB_QUEUE_SIZE];
        m_instrResult = new InstrVisitor.InstrResult ();
    }
    
    
    static void writeFile (final byte [] data, final File outFile, final boolean mkdirs)
        throws IOException
    {
        RandomAccessFile raf = null;
        try
        {
            if (mkdirs)
            {
                final File parent = outFile.getParentFile ();
                if (parent != null) parent.mkdirs (); // no error checking here [errors will be throw below]
            }
            
            raf = new RandomAccessFile (outFile, "rw");
            if (DO_RAF_EXTENSION) raf.setLength (data.length);
            
            raf.write (data);
        } 
        finally
        {
            if (raf != null) raf.close (); // note: intentionally letting the exception percolate up
        }
    }
    
    static void writeZipEntry (final byte [] data, final ZipOutputStream out, final ZipEntry entry, final boolean isCopy)
        throws IOException
    {
        if (isCopy)
        {
            out.putNextEntry (entry); // reusing ' entry' is ok here because we are not changing the data 
            try
            {
                out.write (data);
            }
            finally
            {
                out.closeEntry ();
            }
        }
        else
        {
            // need to compute the checksum which slows things down quite a bit:
            
            final ZipEntry entryCopy = new ZipEntry (entry.getName ());
            entryCopy.setTime (entry.getTime ()); // avoid repeated calls to System.currentTimeMillis() inside the zip stream
            entryCopy.setMethod (ZipOutputStream.STORED);
            // [directory status is implicit in the name]
            entryCopy.setSize (data.length);
            entryCopy.setCompressedSize (data.length);
            
            final CRC32 crc = new CRC32 ();
            crc.update (data);
            entryCopy.setCrc (crc.getValue ());
            
            out.putNextEntry (entryCopy);
            try
            {
                out.write (data);
            }
            finally
            {
                out.closeEntry ();
            }
        }
    }
            
    // private: ...............................................................
    
    
    private static abstract class Job
    {
        protected abstract void run () throws IOException;
        
    } // end of nested class
    
    
    private static final class FileWriteJob extends Job
    {
        protected void run () throws IOException
        {
            writeFile (m_data, m_outFile, m_mkdirs);
            m_data = null;
        }
        
        FileWriteJob (final File outFile, final byte [] data, final boolean mkdirs)
        {
            m_outFile = outFile;
            m_data = data;
            m_mkdirs = mkdirs;
        }
        

        final File m_outFile;
        final boolean m_mkdirs;
        byte [] m_data;
        
    } // end of nested class
    

    private static final class EntryWriteJob extends Job
    {
        protected void run () throws IOException
        {
            writeZipEntry (m_data, m_out, m_entry, m_isCopy);
            m_data = null;
        }
        
        EntryWriteJob (final ZipOutputStream out, final byte [] data, final ZipEntry entry, final boolean isCopy)
        {
            m_out = out;
            m_data = data;
            m_entry = entry;
            m_isCopy = isCopy;
        }
        

        final ZipOutputStream m_out;
        byte [] m_data;
        final ZipEntry m_entry;
        final boolean m_isCopy;
        
    } // end of nested class

    
    private void addJob (final Job job)
        throws FileNotFoundException, IOException
    {
        if (m_jobPos == JOB_QUEUE_SIZE) drainJobQueue ();
        
        m_jobs [m_jobPos ++] = job;
    }
    
    private void drainJobQueue ()
        throws IOException
    {
        for (int j = 0; j < m_jobPos; ++ j)
        {
            final Job job = m_jobs [j];
            if (job != null) // a guard just in case
            {
                m_jobs [j] = null;
                job.run ();
            }
        }
        
        m_jobPos = 0;
    }
        
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
    
    
    // internal run()-scoped state:
    
    private final Job [] m_jobs;
    private final InstrVisitor.InstrResult m_instrResult;
    
    private InstrVisitor m_visitor;
    private IMetaData m_mdata;
    private byte [] m_readbuf;
    private int m_readpos;
    private ByteArrayOStream m_baos; // TODO: code to guard this from becoming too large
    private int m_jobPos;
    private long m_currentArchiveTS;
    private File m_origArchiveFile, m_tempArchiveFile;
    private JarOutputStream m_archiveOut;
    private long m_timeStamp;
    
    
    private static final int BUF_SIZE = 32 * 1024;
    private static final int JOB_QUEUE_SIZE = 128; // a reasonable size chosen empirically after testing a few SCSI/IDE machines
    private static final boolean CLEANUP_TEMP_ARCHIVE_ON_ERRORS = true;
    private static final boolean DO_RAF_EXTENSION = true;
    
    private static final boolean DO_DEPENDS_CHECKING = true;
    private static final Class [] EXPECTED_FAILURES; // set in <clinit>
    
    static
    {
        EXPECTED_FAILURES = new Class []
        {
            EMMARuntimeException.class,
            IllegalArgumentException.class,
            IllegalStateException.class,
        };
    }
    
} // end of class
// ----------------------------------------------------------------------------
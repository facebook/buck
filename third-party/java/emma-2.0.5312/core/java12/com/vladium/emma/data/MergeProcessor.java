/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: MergeProcessor.java,v 1.1.1.1.2.2 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.File;
import java.io.IOException;

import com.vladium.logging.Logger;
import com.vladium.util.Files;
import com.vladium.util.IConstants;
import com.vladium.util.IProperties;
import com.vladium.util.asserts.$assert;
import com.vladium.util.exception.Exceptions;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.Processor;

// ----------------------------------------------------------------------------
/*
 * This class was not meant to be public by design. It is made to to work around
 * access bugs in reflective invocations. 
 */
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class MergeProcessor extends Processor
                           implements IAppErrorCodes
{
    // public: ................................................................
    
    public static MergeProcessor create ()
    {
        return new MergeProcessor ();
    }

    /**
     * 
     * @param path [null is equivalent to an empty array]
     */
    public synchronized final void setDataPath (final String [] path)
    {
        if ((path == null) || (path.length == 0))
            m_dataPath = IConstants.EMPTY_FILE_ARRAY;
        else
            m_dataPath = Files.pathToFiles (path, true);
    }
    
    /**
     * NOTE: there is no setter for merge attribute because this processor
     * always overwrites the out file [to ensure compaction]
     * 
     * @param fileName [null unsets the previous override setting]
     */
    public synchronized final void setSessionOutFile (final String fileName)
    {
        if (fileName == null)
            m_sdataOutFile = null;
        else
        {
            final File _file = new File (fileName);
                
            if (_file.exists () && ! _file.isFile ())
                throw new IllegalArgumentException ("not a file: [" + _file.getAbsolutePath () + "]");
                
            m_sdataOutFile = _file;
        }
    }
    
    // protected: .............................................................

    
    protected void validateState ()
    {
        super.validateState ();
        
        if (m_dataPath == null)
            throw new IllegalStateException ("data path not set");
        
        // [m_sdataOutFile can be null]
        
        // [m_propertyOverrides can be null]
    }
    
    
    protected void _run (final IProperties toolProperties)
    {
        final Logger log = m_log;

        final boolean verbose = m_log.atVERBOSE ();
        if (verbose)
        {
            log.verbose (IAppConstants.APP_VERBOSE_BUILD_ID);
            
            // [assertion: m_dataPath != null]
            log.verbose ("input data path:");
            log.verbose ("{");
            for (int p = 0; p < m_dataPath.length; ++ p)
            {
                final File f = m_dataPath [p];
                final String nonexistent = f.exists () ? "" : "{nonexistent} ";
                
                log.verbose ("  " + nonexistent + f.getAbsolutePath ());
            }
            log.verbose ("}");
        }
        else
        {
            log.info ("processing input files ...");
        }
        
        // get the data out settings:
        File sdataOutFile = m_sdataOutFile;
        {
            if (sdataOutFile == null)
                sdataOutFile = new File (toolProperties.getProperty (EMMAProperties.PROPERTY_SESSION_DATA_OUT_FILE,
                                                                     EMMAProperties.DEFAULT_SESSION_DATA_OUT_FILE));
        }
                
        RuntimeException failure = null;
        try
        {
            IMetaData mdata = null;
            ICoverageData cdata = null;
            
            // merge all data files:
            try
            {
                final long start = log.atINFO () ? System.currentTimeMillis () : 0;
                
                for (int f = 0; f < m_dataPath.length; ++ f)
                {
                    final File dataFile = m_dataPath [f];
                    if (verbose) log.verbose ("processing input file [" + dataFile.getAbsolutePath () + "] ...");
                    
                    final IMergeable [] fileData = DataFactory.load (dataFile);
                    
                    final IMetaData _mdata = (IMetaData) fileData [DataFactory.TYPE_METADATA];
                    if (_mdata != null)
                    {
                        if (verbose) log.verbose ("  loaded " + _mdata.size () + " metadata entries");
                        
                        if (mdata == null)
                            mdata = _mdata;
                        else
                            mdata = (IMetaData) mdata.merge (_mdata); // note: later datapath entries override earlier ones
                    }
                    
                    final ICoverageData _cdata = (ICoverageData) fileData [DataFactory.TYPE_COVERAGEDATA];
                    if (_cdata != null)
                    {
                        if (verbose) log.verbose ("  loaded " + _cdata.size () + " coverage data entries");
                        
                        if (cdata == null)
                            cdata = _cdata;
                        else
                            cdata = (ICoverageData) cdata.merge (_cdata); // note: later datapath entries override earlier ones
                    }
                    
                    ++ m_dataFileCount;
                }
                
                if (log.atINFO ())
                {
                    final long end = System.currentTimeMillis ();
                    
                    log.info (m_dataFileCount + " file(s) read and merged in " + (end - start) + " ms");
                }
                
                if (((mdata == null) || mdata.isEmpty ()) && ((cdata == null) || cdata.isEmpty ()))
                {
                    log.warning ("nothing to do: no metadata or coverage data found in any of the input files");
                    
                    // TODO: throw exception or exit quietly?
                    return;
                }
            }
            catch (IOException ioe)
            {
                // TODO: handle
                ioe.printStackTrace (System.out);
            }
            

            if (verbose)
            {
                if (mdata != null)
                {
                    log.verbose ("  merged metadata contains " + mdata.size () + " entries");
                }
                
                if (cdata != null)
                {
                    log.verbose ("  merged coverage data contains " + cdata.size () + " entries");
                }
            }
            
            // write merged data into output file:
            {
                $assert.ASSERT (sdataOutFile != null, "sdataOutFile not null");
                    
                // the case of the output file being one of the input files is
                // supported; however, for safety reasons we create output in
                // a temp file and rename it only when the data is safely persisted:
                
                boolean rename = false;
                File tempDataOutFile = null;
                
                final File canonicalDataOutFile = Files.canonicalizeFile (sdataOutFile);
                
                for (int f = 0; f < m_dataPath.length; ++ f)
                {
                    final File canonicalDataFile = Files.canonicalizeFile (m_dataPath [f]);
                    if (canonicalDataOutFile.equals (canonicalDataFile))
                    {
                        rename = true;
                        break;
                    }
                }
                
                if (rename) // create a temp out file
                {
                    File tempFileDir = canonicalDataOutFile.getParentFile ();
                    if (tempFileDir == null) tempFileDir = new File ("");
                    
                    // length > 3:
                    final String tempFileName = Files.getFileName (canonicalDataOutFile) + IAppConstants.APP_NAME_LC;
                    final String tempFileExt = EMMAProperties.PROPERTY_TEMP_FILE_EXT;
                
                    try
                    {
                        tempDataOutFile = Files.createTempFile (tempFileDir, tempFileName, tempFileExt);
                    }
                    catch (IOException ioe)
                    {
                        // TODO: error code
                        throw new EMMARuntimeException (ioe);
                    }
                    
                    log.warning ("the specified output file is one of the input files [" + canonicalDataOutFile + "]");
                    log.warning ("all merged data will be written to a temp file first [" + tempDataOutFile.getAbsolutePath ()  + "]");
                }
                
                // persist merged session data:
                {
                    final long start = log.atINFO () ? System.currentTimeMillis () : 0;
                    
                    File persistFile = null;
                    try
                    {
                        persistFile = tempDataOutFile != null ? tempDataOutFile : canonicalDataOutFile;
                        
                        // TODO: the persister API is ugly, redesign
                        
                        if ((mdata == null) || mdata.isEmpty ())
                            DataFactory.persist (cdata, persistFile, false); // never merge to enforce compaction behavior
                        else if ((cdata == null) || cdata.isEmpty ())
                            DataFactory.persist (mdata, persistFile, false); // never merge to enforce compaction behavior
                        else
                            DataFactory.persist (new SessionData (mdata, cdata), persistFile, false); // never merge to enforce compaction behavior
                    }
                    catch (IOException ioe)
                    {
                        if (persistFile != null) persistFile.delete ();
                        
                        // TODO: error code
                        throw new EMMARuntimeException (ioe);
                    }
                    catch (Error e)
                    {
                        if (persistFile != null) persistFile.delete ();
                        
                        throw e; // re-throw
                    }
                    
                    if (rename) // rename-with-delete temp out file into the desired out file
                    {
                        if (! Files.renameFile (tempDataOutFile, canonicalDataOutFile, true)) // overwrite the original archive
                        {
                            // error code
                            throw new EMMARuntimeException ("could not rename temporary file [" + tempDataOutFile.getAbsolutePath () + "] to [" + canonicalDataOutFile + "]: make sure the original file is not locked and can be deleted");
                        }
                    }
                    
                    if (log.atINFO ())
                    {
                        final long end = System.currentTimeMillis ();
                        
                        log.info ("merged/compacted data written to [" + canonicalDataOutFile + "] {in " + (end - start) + " ms}");
                    }
                }
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
    
    // private: ...............................................................
    
    
    private MergeProcessor ()
    {
        m_dataPath = IConstants.EMPTY_FILE_ARRAY;
    }
    
    
    private void reset ()
    {
        m_dataFileCount = 0;
    }
    
    
    // caller-settable state [scoped to this runner instance]:
    
    private File [] m_dataPath; // required to be non-null for run() [is set to canonicalized form]
    private File m_sdataOutFile; // user override; can be null for run()

    // internal run()-scoped state:
    
    private int m_dataFileCount;
    
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
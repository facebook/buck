/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ReportProcessor.java,v 1.1.1.1.2.2 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.io.File;
import java.io.IOException;

import com.vladium.logging.Logger;
import com.vladium.util.Files;
import com.vladium.util.IConstants;
import com.vladium.util.IProperties;
import com.vladium.util.Strings;
import com.vladium.util.asserts.$assert;
import com.vladium.util.exception.Exceptions;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.Processor;
import com.vladium.emma.data.DataFactory;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.IMergeable;
import com.vladium.emma.data.IMetaData;

// ----------------------------------------------------------------------------
/*
 * This class was not meant to be public by design. It is made to to work around
 * access bugs in reflective invocations. 
 */
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ReportProcessor extends Processor
                            implements IAppErrorCodes
{
    // public: ................................................................
    
    public static ReportProcessor create ()
    {
        return new ReportProcessor ();
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
     * @param path [null is equivalent to no source path]
     */
    public synchronized void setSourcePath (final String [] path)
    {
        if (path == null)
            m_sourcePath = null;
        else
            m_sourcePath = Files.pathToFiles (path, true); // always canonicalize source path
    }

    /**
     * 
     * @param types [may not be null]
     */
    public synchronized void setReportTypes (final String [] types)
    {
        if (types == null) throw new IllegalArgumentException ("null input: types");
        
        final String [] reportTypes = Strings.removeDuplicates (types, true);
        if (reportTypes.length == 0) throw new IllegalArgumentException ("empty input: types");
        
        if ($assert.ENABLED) $assert.ASSERT (reportTypes != null && reportTypes.length  > 0);
        
        
        final IReportGenerator [] reportGenerators = new IReportGenerator [reportTypes.length];
        for (int t = 0; t < reportTypes.length; ++ t)
        {
            reportGenerators [t] = AbstractReportGenerator.create (reportTypes [t]);
        }
        
        m_reportGenerators = reportGenerators;
    }
    
    // protected: .............................................................

    
    protected void validateState ()
    {
        super.validateState ();
        
        if (m_dataPath == null)
            throw new IllegalStateException ("data path not set");
            
        // [m_sourcePath can be null]
        
        if ((m_reportGenerators == null) || (m_reportGenerators.length == 0))
            throw new IllegalStateException ("report types not set");
        
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
            
            
            if ((m_sourcePath == null) || (m_sourcePath.length == 0))
            {
                log.verbose ("source path not set");
            }
            else
            {
                log.verbose ("source path:");
                log.verbose ("{");
                for (int p = 0; p < m_sourcePath.length; ++ p)
                {
                    final File f = m_sourcePath [p];
                    final String nonexistent = f.exists () ? "" : "{nonexistent} ";
                    
                    log.verbose ("  " + nonexistent + f.getAbsolutePath ());
                }
                log.verbose ("}");
            }
        }
        else
        {
            log.info ("processing input files ...");
        }
        
        RuntimeException failure = null;
        try
        {
            final long start = log.atINFO () ? System.currentTimeMillis () : 0;
            
            IMetaData mdata = null;
            ICoverageData cdata = null;
            
            // merge all data files:
            try
            {
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
                
                if ((mdata == null) || mdata.isEmpty ())
                {
                    log.warning ("nothing to do: no metadata found in any of the data files");
                    
                    return;
                }
                
                if (cdata == null)
                {
                    log.warning ("nothing to do: no runtime coverage data found in any of the data files");
                    
                    return;
                }
                
                if (cdata.isEmpty ())
                {
                    log.warning ("no collected coverage data found in any of the data files [all reports will be empty]");
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
                                
                SourcePathCache srcpathCache = null;
                if (m_sourcePath != null) srcpathCache = new SourcePathCache (m_sourcePath, true); // ignore non-existent source dirs 
                
                for (int g = 0; g < m_reportGenerators.length; ++ g)
                {
                    final IReportGenerator generator = m_reportGenerators [g];
                    
                    try
                    {
                        // no shallow copies of 'mdata' or 'cdata' are needed here
                        // because this command never runs in a concurrent situation
                        
                        generator.process (mdata, cdata, srcpathCache, toolProperties);
                    }
                    catch (Throwable t)
                    {
                        // TODO: handle and continue
                        t.printStackTrace (System.out);
                        
                        // TODO: continue here
                        break;
                    }
                    finally
                    {
                        try { generator.cleanup (); } catch (Throwable ignore) {}
                    }
                }
            }
            catch (IOException ioe)
            {
                // TODO: handle
                ioe.printStackTrace (System.out);
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
    
    
    private ReportProcessor ()
    {
        m_dataPath = IConstants.EMPTY_FILE_ARRAY;
    }
    
    private void reset ()
    {
        m_dataFileCount = 0;
    }
    
    
    // caller-settable state [scoped to this runner instance]:
    
    private File [] m_dataPath;     // required to be non-null for run()
    private File [] m_sourcePath;   // can be null/empty for run()
    private IReportGenerator [] m_reportGenerators; // required to be non-null for run()

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
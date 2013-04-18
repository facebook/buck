/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RTCoverageDataPersister.java,v 1.1.2.2 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;

import com.vladium.emma.IAppConstants;
import com.vladium.emma.data.DataFactory;
import com.vladium.emma.data.ICoverageData;
import com.vladium.logging.Logger;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2004
 */
abstract
class RTCoverageDataPersister
{
    // public: ................................................................
    
    // protected: .............................................................

    // package: ...............................................................
    
    /*
     * Stateless package-private method shared by RT and RTExitHook for coverage
     * data persistence. This method was moved out of RT class after build 4120
     * in order to decrease classloading dependency set for RTExitHook
     * (FR SF978671).
     */
    static void dumpCoverageData (final ICoverageData cdata, final boolean useSnapshot,
                                  final File outFile, final boolean merge)
    {
        try
        {
            if (cdata != null)
            {
                // use method-scoped loggers everywhere in RT:
                final Logger log = Logger.getLogger ();
                final boolean info = log.atINFO ();
                
                final long start = info ? System.currentTimeMillis () : 0;
                {
                    final ICoverageData cdataView = useSnapshot ? cdata.shallowCopy () : cdata;
                    
                    synchronized (Object.class) // fake a JVM-global critical section when multilply loaded RT's write to the same file
                    {
                        DataFactory.persist (cdataView, outFile, merge);
                    }
                }
                if (info)
                {
                    final long end = System.currentTimeMillis ();
                    
                    log.info ("runtime coverage data " + (merge ? "merged into" : "written to") + " [" + outFile.getAbsolutePath () + "] {in " + (end - start) + " ms}");
                }
            }
        }
        catch (Throwable t)
        {
            // log
            t.printStackTrace ();
            
            // TODO: do better chaining in JRE 1.4+
            throw new RuntimeException (IAppConstants.APP_NAME + " failed to dump coverage data: " + t.toString ());
        }
    }
    
    // private: ...............................................................

} // end of class
// ----------------------------------------------------------------------------
/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RTExitHook.java,v 1.1.1.1.2.2 2004/07/10 03:34:53 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.StringTokenizer;

import com.vladium.emma.data.ICoverageData;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
final class RTExitHook implements Runnable
{
    // public: ................................................................
    
    
    public synchronized void run ()
    {
        if (m_cdata != null)
        {
            RTCoverageDataPersister.dumpCoverageData (m_cdata, true, m_outFile, m_merge);
            
            m_RT = null;
            m_cdata = null;
        }
    }
    
    public static void createClassLoaderClosure ()
    {
        Properties closureMap = null;
        
        InputStream in = null;
        try
        {
            // note that this does not use ClassLoaderResolver by design
            // (closure loading must not load any app classes that are outside
            /// the closure list)
            
            in = RTExitHook.class.getResourceAsStream (CLOSURE_RESOURCE);
            if (in != null)
            {
                closureMap = new Properties ();
                closureMap.load (in);
            }
            else
            {
                throw new Error ("packaging failure: closure resource not found");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace (System.out);
            
            throw new Error ("packaging failure: " + e.toString ());
        }
        finally
        {
            if (in != null) try { in.close (); } catch (IOException ignore) { ignore.printStackTrace (); }
        }
        in = null;
        
        final String closureList = closureMap.getProperty ("closure");
        if (closureList == null)
        {
            throw new Error ("packaging failure: no closure mapping");
        }
        
        // note that this uses the current classloader (only), consistently
        // with the getResourceAsStream() above:
        
        final ClassLoader loader = RTExitHook.class.getClassLoader ();
        
        final StringTokenizer tokenizer = new StringTokenizer (closureList, ",");
        while (tokenizer.hasMoreTokens ())
        {
            final String className = tokenizer.nextToken ();
            
            try
            {
                Class.forName (className, true, loader);
            }
            catch (Exception e)
            {
                throw new Error ("packaging failure: class [" + className + "] not found {" + e.toString () + "}");
            }
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................


    RTExitHook (final Class RT, final ICoverageData cdata, final File outFile, final boolean merge)
    {
        m_RT = RT;
        m_cdata = cdata;
        
        m_outFile = outFile;
        m_merge = merge;
    }
        
    // private: ...............................................................


    private final File m_outFile;
    private final boolean m_merge;
    
    private Class m_RT; // keep our RT class pinned in memory
    private ICoverageData m_cdata;
    
    private static final String CLOSURE_RESOURCE = "RTExitHook.closure"; // relative to this package
    
} // end of class
// ----------------------------------------------------------------------------
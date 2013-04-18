/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: RT.java,v 1.2.2.3 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;

import com.vladium.logging.Logger;
import com.vladium.util.IProperties;
import com.vladium.util.Property;
import com.vladium.util.exit.ExitHookManager;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.DataFactory;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class RT implements IAppConstants
{
    // public: ................................................................
    
    
    public static synchronized ICoverageData reset (final boolean createCoverageData, final boolean createExitHook)
    {
        // reload the app properties [needs to be done to accomodate classloader rearrangements]:
        
        // avoid the call context tricks at runtime in case security causes problems,
        // use an explicit caller parameter for getAppProperties():
        
        ClassLoader loader = RT.class.getClassLoader ();
        if (loader == null) loader = ClassLoader.getSystemClassLoader (); 
        
        IProperties appProperties = null;
        try
        {
            appProperties = EMMAProperties.getAppProperties (loader);
        }
        catch (Throwable t)
        {
            // TODO: handle better
            t.printStackTrace (System.out);
        }
        s_appProperties = appProperties;


        if (EXIT_HOOK_MANAGER != null)
        {
            // disable/remove the current hook, if any:
            
            if (s_exitHook != null)
            {
                 // note: no attempt is made to execute the existing hook, so its coverage
                 // data may be simply discarded
                
                EXIT_HOOK_MANAGER.removeExitHook (s_exitHook);
                s_exitHook = null;
            }
        }
        
        ICoverageData cdata = s_cdata; // no sync accessor needed
        if (createCoverageData)
        {
            cdata = DataFactory.newCoverageData ();
            s_cdata = cdata;
        }
        else
        {
            s_cdata = null;
        }
        
        if (EXIT_HOOK_MANAGER != null)
        {
            if (createExitHook && (cdata != null))
            {
                final Runnable exitHook = new RTExitHook (RT.class, cdata, getCoverageOutFile (), getCoverageOutMerge ());

                // FR SF978671: fault all classes that we might need to do coverage
                // data dumping (this forces classdefs to be loaded into classloader
                // class cache and allows output file writing to succeed even if
                // the RT classloader is some component loader (e.g, in a J2EE container)
                // that gets invalidated by the time the exit hook thread is run:
                
                RTExitHook.createClassLoaderClosure ();
                
                if (EXIT_HOOK_MANAGER.addExitHook (exitHook))
                {
                    s_exitHook = exitHook;
                }
                // else TODO: log/warn
            }
        }
        
        return cdata;
    }
    
    public static void r (final boolean [][] coverage, final String classVMName, final long stamp)
    {
        // note that we use class names, not the actual Class objects, as the keys here. This
        // is not the best possible solution because it is not capable of supporting
        // multiply (re)loaded classes within the same app, but the rest of the toolkit
        // isn't designed to support this anyway. Furthermore, this does not interfere
        // with class unloading.

        final ICoverageData cdata = getCoverageData (); // need to use accessor for JMM reasons

        // ['cdata' can be null if a previous call to dumpCoverageData() disabled data collection]
        
        if (cdata != null)
        {
            synchronized (cdata.lock ())
            {
                // TODO: could something useful be communicated back to the class
                // by returning something here [e.g., unique class ID (solves the
                // issues of class name collisions and class reloading) or RT.class
                // (to prevent RT reloading)]
                
                cdata.addClass (coverage, classVMName, stamp);
            }
        }
    }

    public static synchronized ICoverageData getCoverageData ()
    {
        return s_cdata;
    }
    
    public static synchronized IProperties getAppProperties ()
    {
        return s_appProperties;
    }
    
    /**
     * Public API for forcing coverage data dump.
     * 
     * @param outFile
     * @param merge
     * @param stopDataCollection
     */
    public static synchronized void dumpCoverageData (File outFile, final boolean merge, final boolean stopDataCollection)
    {
        if (DEBUG) System.out.println ("RT::dumpCoverageData() DUMPING " + RT.class.getClassLoader ());
        outFile = outFile != null ? outFile : getCoverageOutFile ();
        
        ICoverageData cdata = s_cdata; // no need to use accessor
        if (stopDataCollection) s_cdata = null; // TODO: log this NOTE: this does not really stop data collection, merely prevents new class registration
        
        RTCoverageDataPersister.dumpCoverageData (cdata, ! stopDataCollection, outFile, merge);
    }
    
    public static synchronized void dumpCoverageData (File outFile, final boolean stopDataCollection)
    {
        outFile = outFile != null ? outFile : getCoverageOutFile ();
        
        ICoverageData cdata = s_cdata; // no need to use accessor
        if (stopDataCollection) s_cdata = null; // TODO: log this NOTE: this does not really stop data collection, merely prevents new class registration
        
        RTCoverageDataPersister.dumpCoverageData (cdata, ! stopDataCollection, outFile, getCoverageOutMerge ());
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................
    
    
    private RT () {} // prevent subclassing
    
    
    private static File getCoverageOutFile ()
    {
        final IProperties appProperties = getAppProperties (); // sync accessor
        if (appProperties != null)
        {
            final String property = appProperties.getProperty (EMMAProperties.PROPERTY_COVERAGE_DATA_OUT_FILE,
                                                               EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_FILE);
            return new File (property);
        }
        
        return new File (EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_FILE); 
    }
    
    private static boolean getCoverageOutMerge ()
    {
        final IProperties appProperties = getAppProperties (); // sync accessor
        if (appProperties != null)
        {
            // [Boolean.toString (boolean) is J2SDK 1.4+]
            
            final String property = appProperties.getProperty (EMMAProperties.PROPERTY_COVERAGE_DATA_OUT_MERGE,
                                                               EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_MERGE.toString ());
            return Property.toBoolean (property);
        }
        
        return EMMAProperties.DEFAULT_COVERAGE_DATA_OUT_MERGE.booleanValue ();
    }
    
        
    private static ICoverageData s_cdata;
    private static Runnable s_exitHook;
    private static IProperties s_appProperties; // TODO: this is better of as java.util.Properties

    private static final ExitHookManager EXIT_HOOK_MANAGER; // set in <clinit>
    
    private static final boolean DEBUG = false;
    
    static
    {
        if (DEBUG) System.out.println ("RT[" + System.identityHashCode (RT.class) + "]::<clinit>: loaded by " + RT.class.getClassLoader ());
        
        ExitHookManager temp = null;
        try
        {
            temp = ExitHookManager.getSingleton ();
        }
        catch (Throwable t)
        {
            // TODO: handle better
            t.printStackTrace (System.out);
        }
        EXIT_HOOK_MANAGER = temp;

         
        if (RTSettings.isStandaloneMode ())
        {
            if (DEBUG) System.out.println ("RT::<clinit>: STANDALONE MODE");
            
            // load app props, create coverage data, and register an exit hook for it:
            reset (true, true);
            
            // use method-scoped loggers in RT:
            final Logger log = Logger.getLogger ();
            if (log.atINFO ())
            {
                log.info ("collecting runtime coverage data ...");
            }
        }
        else
        {
            // load app props only:
            reset (false, false);
        }
    }

} // end of class
// ----------------------------------------------------------------------------
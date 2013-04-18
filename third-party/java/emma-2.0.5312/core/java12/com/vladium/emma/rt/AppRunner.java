/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: AppRunner.java,v 1.1.1.1.2.2 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.vladium.logging.Logger;
import com.vladium.util.Files;
import com.vladium.util.IConstants;
import com.vladium.util.IProperties;
import com.vladium.util.Property;
import com.vladium.util.SoftValueMap;
import com.vladium.util.Strings;
import com.vladium.util.asserts.$assert;
import com.vladium.util.exception.Exceptions;
import com.vladium.util.exit.ExitHookManager;
import com.vladium.emma.AppLoggers;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.Processor;
import com.vladium.emma.filter.IInclExclFilter;
import com.vladium.emma.data.CoverageOptionsFactory;
import com.vladium.emma.data.IMetaData;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.DataFactory;
import com.vladium.emma.data.ISessionData;
import com.vladium.emma.data.SessionData;
import com.vladium.emma.report.AbstractReportGenerator;
import com.vladium.emma.report.IReportGenerator;
import com.vladium.emma.report.SourcePathCache;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class AppRunner extends Processor
                      implements IAppErrorCodes
{
    // public: ................................................................
    
    
    public static AppRunner create (final ClassLoader delegate)
    {
        return new AppRunner (delegate);
    }
    
    
    public synchronized void run ()
    {
        validateState ();
        
        // disable Runtime's own exit hook:
        RTSettings.setStandaloneMode (false); // an optimization to disable RT's static init code [this line must precede any reference to RT]
        RT.reset (true, false); // reset RT [RT creates 'cdata' and loads app properties]

        // load tool properties:
        final IProperties toolProperties;
        {
            IProperties appProperties = RT.getAppProperties (); // try to use app props consistent with RT's view of them
            if (appProperties == null) appProperties = EMMAProperties.getAppProperties (); // don't use combine()
            
            toolProperties = IProperties.Factory.combine (m_propertyOverrides, appProperties);
        }
        if ($assert.ENABLED) $assert.ASSERT (toolProperties != null, "toolProperties is null"); // can be empty, though

        final Logger current = Logger.getLogger ();
        final Logger log = AppLoggers.create (m_appName, toolProperties, current);
        
        if (log.atTRACE1 ())
        {
            log.trace1 ("run", "complete tool properties:");
            toolProperties.list (log.getWriter ());
        }
        
        try
        {
            Logger.push (log);
            m_log = log;
        
            _run (toolProperties);
        }
        finally
        {
            if (m_log != null)
            {
                Logger.pop (m_log);
                m_log = null;
            }
        }
    }
    

    /**
     * @param path [null is equivalent to empty array]
     * @param canonical
     */
    public synchronized void setCoveragePath (String [] path, final boolean canonical)
    {
        if ((path == null) || (path.length == 0))
            m_coveragePath = IConstants.EMPTY_FILE_ARRAY;
        else
            m_coveragePath = Files.pathToFiles (path, canonical);
        
        m_canonical = canonical;
    }
    
    public synchronized void setScanCoveragePath (final boolean scan)
    {
        m_scanCoveragePath = scan;
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
     * @param specs [null is equivalent to no filtering (everything is included)]
     */    
    public synchronized final void setInclExclFilter (final String [] specs)
    {
        if (specs == null)
            m_coverageFilter = null;
        else
            m_coverageFilter = IInclExclFilter.Factory.create (specs);
    }
    
    /**
     * 
     * @param className [may not be null or empty]
     * @param args [null is equivalent to an empty array]
     */
    public synchronized void setAppClass (final String className, final String [] args)
    {
        if ((className == null) || (className.length () == 0))
            throw new IllegalArgumentException ("null/empty input: className");
        
        if (args != null)
        {
            final String [] _args = (String []) args.clone ();
             
            for (int a = 0; a < _args.length; ++ a)
                if (_args [a] == null) throw new IllegalArgumentException ("null input: args[" + a + "]");
                
            m_appArgs = _args;
        }
        else
        {
            m_appArgs = IConstants.EMPTY_STRING_ARRAY;
        }
        
        m_appClassName = className;
    }
    
    public synchronized void setDumpSessionData (final boolean dump)
    {
        m_dumpSessionData = dump;    
    }
    
    /**
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
    
    /**
     * 
     * @param merge [null unsets the previous override setting]
     */
    public synchronized final void setSessionOutMerge (final Boolean merge)
    {
        m_sdataOutMerge = merge;
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
        
        if ((m_appClassName == null) || (m_appClassName.length () == 0))
            throw new IllegalStateException ("application class name not set");
        
        if (m_appArgs == null)
            throw new IllegalStateException ("application arguments not set");

        if (m_coveragePath == null)
            throw new IllegalStateException ("coverage path not set");
        
        // [m_coverageFilter can be null]
        
        // [m_sdataOutFile can be null]
        // [m_sdataOutMerge can be null]
        
        if ((m_reportGenerators == null) || (m_reportGenerators.length == 0))
            throw new IllegalStateException ("report types not set");

        // [m_sourcePath can be null/empty]
        
        // [m_propertyOverrides can be null]
    }
    
    
    protected void _run (final IProperties toolProperties)
    {
        final Logger log = m_log;
        
        final boolean verbose = log.atVERBOSE ();
        if (verbose)
        {
            log.verbose (IAppConstants.APP_VERBOSE_BUILD_ID);
            
            // [assertion: m_coveragePath != null]
            log.verbose ("coverage path:");
            log.verbose ("{");
            for (int p = 0; p < m_coveragePath.length; ++ p)
            {
                final File f = m_coveragePath [p];
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
        
        // get the data out settings [note: this is not conditioned on m_dumpRawData]:
        File sdataOutFile = m_sdataOutFile;
        Boolean sdataOutMerge = m_sdataOutMerge;
        {
            if (sdataOutFile == null)
                sdataOutFile = new File (toolProperties.getProperty (EMMAProperties.PROPERTY_SESSION_DATA_OUT_FILE,
                                                                     EMMAProperties.DEFAULT_SESSION_DATA_OUT_FILE));
            
            if (sdataOutMerge == null)
            {
                final String _dataOutMerge = toolProperties.getProperty (EMMAProperties.PROPERTY_SESSION_DATA_OUT_MERGE,
                                                                         EMMAProperties.DEFAULT_SESSION_DATA_OUT_MERGE.toString ());
                sdataOutMerge = Property.toBoolean (_dataOutMerge) ? Boolean.TRUE : Boolean.FALSE;
            } 
        }
        
        if (verbose && m_dumpSessionData)
        {
            log.verbose ("session data output file: " + sdataOutFile.getAbsolutePath ());
            log.verbose ("session data output merge mode: " + sdataOutMerge);
        }
        
        // get instr class loader delegation filter settings:
        final IInclExclFilter forcedDelegationFilter
            = IInclExclFilter.Factory.create (toolProperties.getProperty (InstrClassLoader.PROPERTY_FORCED_DELEGATION_FILTER),
                                              COMMA_DELIMITERS, FORCED_DELEGATION_FILTER_SPECS);
        final IInclExclFilter throughDelegationFilter
            = IInclExclFilter.Factory.create (toolProperties.getProperty (InstrClassLoader.PROPERTY_THROUGH_DELEGATION_FILTER),
                                              COMMA_DELIMITERS, null);
        

        // TODO: consider injecting Runtime straight into appLoader namespace...
        // TODO: create a thread group for all exit hooks?


        // get a handle to exit hook manager singleton:
        ExitHookManager runnerExitHookManager = null;
        try
        {
            runnerExitHookManager = ExitHookManager.getSingleton (); // can throw
        }
        catch (Exception e)
        {
            // TODO: log/handle/warn
            e.printStackTrace (System.out);
        }

        AppRunnerExitHook runnerExitHook = null;
        RuntimeException failure = null;
        
        try
        { 
            SourcePathCache srcpathCache = null;
            if (m_sourcePath != null) srcpathCache = new SourcePathCache (m_sourcePath, true); // ignore non-existent source dirs
            
            // create session data containers:
            ICoverageData cdata = RT.getCoverageData ();
            if ($assert.ENABLED) $assert.ASSERT (cdata != null, "cdata is null");
            
            IMetaData mdata = DataFactory.newMetaData (CoverageOptionsFactory.create (toolProperties));
            
            runnerExitHook = new AppRunnerExitHook (log, m_dumpSessionData, sdataOutFile, sdataOutMerge.booleanValue (), mdata, cdata, m_reportGenerators, srcpathCache, toolProperties);
            
            if (runnerExitHookManager != null)
                runnerExitHookManager.addExitHook (runnerExitHook);
            
            // --------------[ start of exit hook-protected section ]--------------
            
            Map classIOCache = null;
            
            // scan the classpath to populate the initial metadata:
            if (m_scanCoveragePath)
            {
                if (USE_SOFT_CACHE)
                    classIOCache = new SoftValueMap (INIT_CACHE_CAPACITY, 0.75F, SOFT_CACHE_READ_CHK_FREQUENCY, SOFT_CACHE_WRITE_CHK_FREQUENCY);
                else
                    classIOCache = new HashMap (INIT_CACHE_CAPACITY, 0.75F);
                    
                final ClassPathProcessorST processor = new ClassPathProcessorST (m_coveragePath, m_canonical, mdata, m_coverageFilter, classIOCache);
                
                // with a bit of work [ClassPathProcessorST needs to lock on the
                // metadata, etc] this could be run concurrently with the app
                // itself to improve perceived performance, however, I am not
                // going to invest time in this; 
                
                // populate 'cache' [optional] and 'mdata':
                processor.run ();
                
                if (log.atTRACE1 ())
                {
                    log.trace1 ("run", "class cache size after cp scan: " + classIOCache.size ());
                    log.trace1 ("run", "metadata size after cp scan: " + mdata.size ());
                }
            }
            
            
            // app runner does not need these handles anymore [only the exit hook runner maintains them]:
            srcpathCache = null;
            cdata = null;
            
            final ClassLoader appLoader;
            {
                final IClassLoadHook loadHook = new InstrClassLoadHook (m_coverageFilter, mdata);
                 
                try
                {
                    appLoader = new InstrClassLoader (m_delegate, m_coveragePath, forcedDelegationFilter, throughDelegationFilter, loadHook, classIOCache);
                }
                catch (SecurityException se)
                {
                    throw new EMMARuntimeException (SECURITY_RESTRICTION, new String [] {IAppConstants.APP_NAME}, se);
                }
                catch (MalformedURLException mue)
                {
                    throw new EMMARuntimeException (mue); 
                }
            }
            
            // app runner does not need these handles anymore:
            mdata = null;
            classIOCache = null;

            
            final ClassLoader contextLoader;
            boolean contextLoaderSet = false;
            if (SET_CURRENT_CONTEXT_LOADER)
            {
                try
                {
                    final Thread currentThread = Thread.currentThread (); 
                    
                    // TODO: rethink if this is the right place to do this
                    contextLoader = currentThread.getContextClassLoader ();
                    currentThread.setContextClassLoader (appLoader);
                    
                    contextLoaderSet = true;
                }
                catch (SecurityException se)
                {
                    throw new EMMARuntimeException (SECURITY_RESTRICTION, new String [] {IAppConstants.APP_NAME}, se);
                }
            }
            
            
            ThreadGroup appThreadGroup = null;
            try
            {
                // load [and possibly initialize] the app class:
                
                final Class appClass;
                try
                {
                    // load [and force early initialization if INIT_AT_LOAD_TIME is 'true']:
                    appClass = Class.forName (m_appClassName, INIT_AT_LOAD_TIME, appLoader);
                }
                catch (ClassNotFoundException cnfe)
                {
                    // TODO: dump the classloader tree into the error message as well
                    throw new EMMARuntimeException (MAIN_CLASS_NOT_FOUND, new String [] {m_appClassName}, cnfe);
                }
                catch (ExceptionInInitializerError eiie) // this should not happen for INIT_AT_LOAD_TIME=false
                {
                    final Throwable cause = eiie.getException ();
                    
                    throw new EMMARuntimeException (MAIN_CLASS_LOAD_FAILURE, new String [] {m_appClassName, cause.toString ()}, cause);
                }
                catch (Throwable t)
                {
                    throw new EMMARuntimeException (MAIN_CLASS_NOT_FOUND, new String [] {m_appClassName}, t);
                }
                
                // ensure that the app is bootstrapped using appLoader:
                {
                    final ClassLoader actualLoader = appClass.getClassLoader ();
                    if (actualLoader != appLoader)
                    {
                        final String loaderName = actualLoader != null ?  actualLoader.getClass ().getName () : "<PRIMORDIAL>";
                        
                        throw new EMMARuntimeException (MAIN_CLASS_BAD_DELEGATION, new String [] {IAppConstants.APP_NAME, m_appClassName, loaderName});
                    }
                }
    
                // run the app's main():
                
                final Method appMain;
                try
                {
                    // this causes initialization on some non-Sun-compatible JVMs [ignore]:
                    appMain = appClass.getMethod ("main", MAIN_TYPE); // Sun JVMs do not seem to require the method to be declared
                }
                catch (Throwable t)
                {
                    throw new EMMARuntimeException (MAIN_METHOD_NOT_FOUND, new String [] {m_appClassName}, t);
                }            
                
                Invoker invoker = new Invoker (appMain, null, new Object [] {m_appArgs});
                
                appThreadGroup = new ThreadGroup (IAppConstants.APP_NAME + " thread group [" + m_appClassName + "]");
                appThreadGroup.setDaemon (true);
                
                Thread appThread = new Thread (appThreadGroup, invoker, IAppConstants.APP_NAME + " main() thread");
                appThread.setContextClassLoader (appLoader);
                
                // --- [app start] ----
                
                appThread.start ();
                
                try {appThread.join (); } catch (InterruptedException ignore) {}
                appThread = null;
                
                joinNonDeamonThreads (appThreadGroup);
                
                // --- [app end] ----
                
                if (log.atTRACE1 ())
                {
                    if (appLoader instanceof InstrClassLoader) ((InstrClassLoader) appLoader).debugDump (log.getWriter ());
                }
                
                final Throwable mainFailure = invoker.getFailure ();
                invoker = null;
                
                if (mainFailure != null)
                {
                    if (mainFailure instanceof InvocationTargetException)
                    {
                        final Throwable cause = ((InvocationTargetException) mainFailure).getTargetException ();
                        
                        throw new EMMARuntimeException (MAIN_METHOD_FAILURE, new String [] {m_appClassName, cause.toString ()}, cause);
                    }
                    else if (mainFailure instanceof ExceptionInInitializerError)
                    {
                        // this catch block is never entered if INIT_AT_LOAD_TIME is 'true'
                        final Throwable cause = ((ExceptionInInitializerError) mainFailure).getException ();
                        
                        throw new EMMARuntimeException (MAIN_METHOD_FAILURE, new String [] {m_appClassName, cause.toString ()}, cause);
                    }
                    else if ((mainFailure instanceof IllegalAccessException)   ||
                             (mainFailure instanceof IllegalArgumentException) ||
                             (mainFailure instanceof NullPointerException))
                    {
                        throw new EMMARuntimeException (MAIN_METHOD_NOT_FOUND, new String [] {m_appClassName}, mainFailure);
                    }
                    else
                    {
                        throw new EMMARuntimeException (MAIN_METHOD_FAILURE, new String [] {m_appClassName, mainFailure.toString ()}, mainFailure);
                    }
                }                
            }
            catch (SecurityException se)
            {
                throw new EMMARuntimeException (SECURITY_RESTRICTION, new String [] {IAppConstants.APP_NAME}, se);
            }
            finally
            {
                if (SET_CURRENT_CONTEXT_LOADER && contextLoaderSet)
                {
                    try
                    {
                        Thread.currentThread ().setContextClassLoader (contextLoader);
                    }
                    catch (Throwable ignore) {} 
                }

                if ((appThreadGroup != null) && ! appThreadGroup.isDestroyed ())
                try
                {
                    appThreadGroup.destroy ();
                    appThreadGroup = null;
                }
                catch (Throwable ignore) {}                
            }
        }
        catch (RuntimeException re)
        {
            failure = re; // should be EMMARuntimeException only if there are no errors above
        }
        finally
        {
            RT.reset (false, false);
        }
        
        if ($assert.ENABLED) $assert.ASSERT (runnerExitHook != null, "reportExitHook = null");
        runnerExitHook.run (); // that this may be a noop (if the shutdown sequence got there first)
        
        // [assertion: the report exit hook is done]
                
        if (runnerExitHookManager != null)
        {
            runnerExitHookManager.removeExitHook (runnerExitHook); // Ok if this fails
            runnerExitHookManager = null;
        }
        
        // ---------------[ end of exit hook-protected section ]---------------
        
        
        final Throwable exitHookDataDumpFailure = runnerExitHook.getDataDumpFailure ();
        final List /* Throwable */ exitHookReportFailures = runnerExitHook.getReportFailures ();
        runnerExitHook = null;        
        
        if (failure != null) // 'failure' takes precedence over any possible exit hook's problems
        {
            throw wrapFailure (failure);
        }
        else if ((exitHookDataDumpFailure != null) || (exitHookReportFailures != null))
        {
            if (exitHookDataDumpFailure != null)
                log.log (Logger.SEVERE, "exception while persisting raw session data:", exitHookDataDumpFailure);
            
            Throwable firstReportFailure = null;
            if (exitHookReportFailures != null)
            {
                for (Iterator i = exitHookReportFailures.iterator (); i.hasNext (); )
                {
                    final Throwable reportFailure = (Throwable) i.next ();                
                    if (firstReportFailure == null) firstReportFailure = reportFailure;
                    
                    log.log (Logger.SEVERE, "exception while creating a report:", reportFailure);
                }
            }
            
            if (exitHookDataDumpFailure != null)
                throw wrapFailure (exitHookDataDumpFailure);
            else if (firstReportFailure != null) // redundant check
                throw wrapFailure (firstReportFailure);
        }

    }

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class Invoker implements Runnable
    {
        Invoker (final Method method, final Object target, final Object [] args)
        {
            if (method == null) throw new IllegalArgumentException ("null input: method");
            if (args == null) throw new IllegalArgumentException ("null input: args");
            
            m_method = method;
            m_target = target;
            m_args = args;
        }
        
        public void run ()
        {
            try
            {
                m_method.invoke (m_target, m_args);
            }
            catch (Throwable t)
            {
                m_failure = t;
            }
        }
        
        Throwable getFailure ()
        {
            return m_failure;
        }
        
        
        private final Method m_method;
        private final Object m_target;
        private final Object [] m_args;
        private Throwable m_failure;
        
    } // end of nested class
    
    
    private static final class AppRunnerExitHook implements Runnable
    {
        public synchronized void run ()
        {
            try
            {
                if (! m_done)
                {
                    // grab data snapshots:
                    
                    final IMetaData mdataSnashot = m_mdata.shallowCopy ();
                    m_mdata = null;
                    final ICoverageData cdataSnapshot = m_cdata.shallowCopy ();
                    m_cdata = null;
                    
                    if (mdataSnashot.isEmpty ())
                    {
                        m_log.warning ("no metadata collected at runtime [no reports generated]");
                        
                        return;
                    }
                    
                    if (cdataSnapshot.isEmpty ())
                    {
                        m_log.warning ("no coverage data collected at runtime [all reports will be empty]");
                    }
                    
                    final ISessionData sdata = new SessionData (mdataSnashot, cdataSnapshot);

                    // if requested, dump raw data before running report generators:                    
                    // [note that the raw dumps and reports will be consistent wrt
                    // the session data they represent]
                    
                    if (m_dumpRawData && (m_sdataOutFile != null))
                    {
                       try
                        {
                            final boolean info = m_log.atINFO ();
                            
                            final long start = info ? System.currentTimeMillis () : 0;
                            {
                                DataFactory.persist (sdata, m_sdataOutFile, m_sdataOutMerge);
                            }
                            if (info)
                            {
                                final long end = System.currentTimeMillis ();
                                
                                m_log.info ("raw session data " + (m_sdataOutMerge ? "merged into" : "written to") + " [" + m_sdataOutFile.getAbsolutePath () + "] {in " + (end - start) + " ms}");
                            }
                        }
                        catch (Throwable t)
                        {
                            m_dataDumpFailure = t;
                        }
                    }
                    
                    for (int g = 0; g < m_generators.length; ++ g)
                    {
                        final IReportGenerator generator = m_generators [g];
                        
                        if (generator != null)
                        {
                            try
                            {
                                generator.process (mdataSnashot, cdataSnapshot, m_cache, m_properties);
                            }
                            catch (Throwable t)
                            {
                                if (m_reportFailures == null) m_reportFailures = new ArrayList ();
                                m_reportFailures.add (t);
                                
                                continue;
                            }
                            finally
                            {
                                try { generator.cleanup (); } catch (Throwable ignore) {}
                                m_generators [g] = null;
                            }
                        }
                    }                    
                }
            }
            finally
            {
                m_generators = null;
                m_mdata = null;
                m_cdata = null;
                m_properties = null;
                m_cache = null;
                
                m_done = true;
            }
        }
        
        // note: because ExitHookManager is a lazily created static singleton the
        // correct thing to do is to pass an explicit Logger into each exit hook runner
        // instead of relying on thread inheritance:
        
        AppRunnerExitHook (final Logger log,
                           final boolean dumpRawData, final File sdataOutFile, final boolean sdataOutMerge,
                           final IMetaData mdata, final ICoverageData cdata,
                           final IReportGenerator [] generators,
                           final SourcePathCache cache, final IProperties properties)
        {
            if (log == null) throw new IllegalArgumentException ("null input: log");
            if ((generators == null) || (generators.length == 0)) throw new IllegalArgumentException ("null/empty input: generators");
            if (mdata == null) throw new IllegalArgumentException ("null input: mdata");
            if (cdata == null) throw new IllegalArgumentException ("null input: cdata");
            if (properties == null) throw new IllegalArgumentException ("null input: properties");
            
            m_log = log;
            
            m_dumpRawData = dumpRawData;
            m_sdataOutFile = sdataOutFile;
            m_sdataOutMerge = sdataOutMerge;
            
            m_generators = (IReportGenerator []) generators.clone ();
            m_mdata = mdata;
            m_cdata = cdata;
            m_cache = cache;
            m_properties = properties;
        }

        
        synchronized Throwable getDataDumpFailure ()
        {
            return m_dataDumpFailure;
        }
        
        synchronized List /* Throwable */ getReportFailures ()
        {
            return m_reportFailures;
        }


        private final Logger m_log;
        private final boolean m_dumpRawData;
        private final File m_sdataOutFile;
        private final boolean m_sdataOutMerge;
        
        private IReportGenerator [] m_generators;
        private IMetaData m_mdata;
        private ICoverageData m_cdata;
        private SourcePathCache m_cache;
        private IProperties m_properties;
        private boolean m_done;
        private Throwable m_dataDumpFailure;
        private List /* Throwable */ m_reportFailures;
        
    } // end of nested class 
    
    
    private AppRunner (final ClassLoader delegate)
    {
        m_delegate = delegate;
        m_coveragePath = IConstants.EMPTY_FILE_ARRAY;
    }
    
    
    private static void joinNonDeamonThreads (final ThreadGroup group)
    {
        if (group == null) throw new IllegalArgumentException ("null input: group");
        
        final List threads = new ArrayList ();
        while (true)
        {
            threads.clear ();
            
            // note: group.activeCount() is only an estimate as more threads
            // could get created while we are doing this [if 'aliveThreads'
            // array is too short, the extra threads are silently ignored]:
            
            Thread [] aliveThreads;
            final int aliveCount;
            
            // enumerate [recursively] all threads in 'group':
            synchronized (group)
            {
                aliveThreads = new Thread [group.activeCount () << 1];
                aliveCount = group.enumerate (aliveThreads, true);
            }
            
            for (int t = 0; t < aliveCount; t++)
            {
                if (! aliveThreads [t].isDaemon ())
                    threads.add (aliveThreads [t]);
            }            
            aliveThreads = null;
            
            if (threads.isEmpty ())
                break; // note: this logic does not work if daemon threads are spawning non-daemon ones
            else
            {
                for (Iterator i = threads.iterator (); i.hasNext (); )
                {
                    try
                    {
                        ((Thread) i.next ()).join (); 
                    }
                    catch (InterruptedException ignore) {}
                }
            }
        }
    }
    
    private static RuntimeException wrapFailure (final Throwable t)
    {
        if (Exceptions.unexpectedFailure (t, EXPECTED_FAILURES))
            return new EMMARuntimeException (UNEXPECTED_FAILURE,
                                            new Object [] {t.toString (), IAppConstants.APP_BUG_REPORT_LINK},
                                            t);
        else if (t instanceof RuntimeException)
            return (RuntimeException) t;
        else
            return new EMMARuntimeException (t);
    }
    

    // caller-settable state [scoped to this runner instance]:
    
    private final ClassLoader m_delegate;
    
    private String m_appClassName;      // required to be non-null for run()
    private String [] m_appArgs;        // required to be non-null for run()
    
    private File [] m_coveragePath;     // required to be non-null/non-empty for run()
    private boolean m_canonical;
    private boolean m_scanCoveragePath;
    private IInclExclFilter m_coverageFilter; // can be null for run()   
    
    private boolean m_dumpSessionData;
    private File m_sdataOutFile; // user override; can be null for run()
    private Boolean m_sdataOutMerge; // user override; can be null for run()
   
    private IReportGenerator [] m_reportGenerators; // required to be non-null for run()
    private File [] m_sourcePath;                   // can be null/empty for run()
    
    // it is attractive to detect errors at load time, but this may allow
    // threads created by <clinit> code to escape; on the other hand, classes
    // that do not override main() will not get initialized this way and will
    // not register with our runtime [which seems a minor problem at this point]: 
    private static final boolean INIT_AT_LOAD_TIME = false;
    
    // setting the context loader on AppRunner's thread should not
    // be necessary since the app is run in a dedicated thread group;
    // however, if INIT_AT_LOAD_TIME=true the app's <clinit> code
    // should run with an adjusted context loader:
    private static final boolean SET_CURRENT_CONTEXT_LOADER = INIT_AT_LOAD_TIME;
    
    // a soft cache is ideal for managing class definitions that are read during
    // the initial classpath scan; however, the default LRU policy parameters for
    // clearing SoftReferences in Sun's J2SDK 1.3+ render them next to useless
    // in the client HotSpot JVM (in which this tool will probably run most often);
    // using a hard cache guarantees 100% cache hit rate but can also raise the
    // memory requirements significantly beyond the needs of the original app.
    // [see bug refs 4471453, 4806720, 4888056, 4239645]
    //
    // resolution for now: use a soft cache anyway and doc that to make it useful
    // for non-trivial apps the user should use -Xms or -XX:SoftRefLRUPolicyMSPerMB
    // JVM options or use a server HotSpot JVM
    private static final boolean USE_SOFT_CACHE = true;
    
    private static final int INIT_CACHE_CAPACITY = 2003; // prime
    private static final int SOFT_CACHE_READ_CHK_FREQUENCY = 100;
    private static final int SOFT_CACHE_WRITE_CHK_FREQUENCY = 100;

    private static final String [] FORCED_DELEGATION_FILTER_SPECS; // set in <clinit>
    private static final Class [] MAIN_TYPE = new Class [] {String [].class};
    
    private static final Class [] EXPECTED_FAILURES; // set in <clinit>
    
    protected static final String COMMA_DELIMITERS    = "," + Strings.WHITE_SPACE;
    protected static final String PATH_DELIMITERS     = ",".concat (File.pathSeparator);
    
    static
    {
        EXPECTED_FAILURES = new Class []
        {
            EMMARuntimeException.class,
            IllegalArgumentException.class,
            IllegalStateException.class,
        };
                
        FORCED_DELEGATION_FILTER_SPECS = new String [] {"+" + IAppConstants.APP_PACKAGE + ".*"};
    }
    
} // end of class
// ----------------------------------------------------------------------------
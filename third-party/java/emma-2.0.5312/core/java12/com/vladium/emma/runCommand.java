/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: runCommand.java,v 1.1.1.1.2.1 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.vladium.util.ClassLoaderResolver;
import com.vladium.util.Strings;
import com.vladium.util.args.IOptsParser;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.rt.AppRunner;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class runCommand extends Command
{
    // public: ................................................................


    public synchronized void run ()
    {
        ClassLoader loader;
        try
        {
            loader = ClassLoaderResolver.getClassLoader ();
        }
        catch (Throwable t)
        {
            loader = getClass ().getClassLoader ();
        }
        
        try
        {
            // process 'args':
            {
                final IOptsParser parser = getOptParser (loader);
                final IOptsParser.IOpts parsedopts = parser.parse (m_args);
                
                // check if usage is requested before checking args parse errors etc:
                {
                    final int usageRequestLevel = parsedopts.usageRequestLevel ();

                    if (usageRequestLevel > 0)
                    {
                        usageexit (parser, usageRequestLevel, null);
                        return;
                    }
                }
                
                final IOptsParser.IOpt [] opts = parsedopts.getOpts ();
                
                if (opts == null) // this means there were args parsing errors
                {
                    parsedopts.error (m_out, STDOUT_WIDTH);
                    usageexit (parser, IOptsParser.SHORT_USAGE, null);
                    return;
                }
                
                // process parsed args:
                try
                {
                    for (int o = 0; o < opts.length; ++ o)
                    {
                        final IOptsParser.IOpt opt = opts [o];
                        final String on = opt.getCanonicalName ();
                        
                        if (! processOpt (opt))
                        {
                            if ("cp".equals (on))
                            {
                                m_classpath = getListOptValue (opt, PATH_DELIMITERS, true);
                            }
                            else if ("jar".equals (on))
                            {
                                m_jarMode = true;
                            }
                            else if ("f".equals (on))
                            {
                                m_scanCoveragePath = getOptionalBooleanOptValue (opt);
                            }
                            else if ("sp".equals (on))
                            {
                                m_srcpath = getListOptValue (opt, PATH_DELIMITERS, true);
                            }
                            else if ("raw".equals (on))
                            {
                                m_dumpRawData = getOptionalBooleanOptValue (opt); 
                            }
                            else if ("out".equals (on))
                            {
                                m_outFileName = opt.getFirstValue ();
                            }
                            else if ("merge".equals (on))
                            {
                                m_outDataMerge = getOptionalBooleanOptValue (opt) ? Boolean.TRUE : Boolean.FALSE; 
                            }
                            else if ("r".equals (on))
                            {
                                m_reportTypes = Strings.merge (opt.getValues (), COMMA_DELIMITERS, true);
                            }
                            else if ("ix".equals (on))
                            {
                                m_ixpath = getListOptValue (opt, COMMA_DELIMITERS, true);
                            }
                        }
                    }
                    
                    // user '-props' file property overrides:
                    
                    if (! processFilePropertyOverrides ()) return;
                    
                    // process prefixed opts:
                    
                    processCmdPropertyOverrides (parsedopts);
                }
                catch (IOException ioe)
                {
                    throw new EMMARuntimeException (IAppErrorCodes.ARGS_IO_FAILURE, ioe);
                }
                
                
                // process free args:
                {
                    final String [] freeArgs = parsedopts.getFreeArgs ();
                    
                    if (m_jarMode)
                    {
                        if ((freeArgs == null) || (freeArgs.length == 0))
                        {
                            usageexit (parser, IOptsParser.SHORT_USAGE, "missing jar file name");
                            return;
                        }
                        
                        if ($assert.ENABLED) $assert.ASSERT (freeArgs != null && freeArgs.length > 0, "invalid freeArgs");
    
                        final File jarfile = new File (freeArgs [0]);
                        final String jarMainClass;
                        try
                        {
                            jarMainClass = openJarFile (jarfile); // the rest of free args are *not* ignored
                        }
                        catch (IOException ioe)
                        {
                            // TODO: is the right error code?
                            throw new EMMARuntimeException (IAppErrorCodes.ARGS_IO_FAILURE, ioe);
                        }                    
                        
                        if (jarMainClass == null)
                        {
                            exit (true, "failed to load Main-Class manifest attribute from [" + jarfile.getAbsolutePath () + "]", null, RC_UNEXPECTED);
                            return; 
                        }
                        
                        if ($assert.ENABLED) $assert.ASSERT (jarMainClass != null, "invalid jarMainClass");
                        
                        m_appArgs = new String [freeArgs.length];
                        System.arraycopy (freeArgs, 1, m_appArgs, 1, freeArgs.length - 1);
                        m_appArgs [0] = jarMainClass;
                        
                        m_classpath = new String [] { jarfile.getPath () };
                    }
                    else
                    {
                        if ((freeArgs == null) || (freeArgs.length == 0))
                        {
                            usageexit (parser, IOptsParser.SHORT_USAGE, "missing application class name");
                            return;
                        }
                        
                        m_appArgs = freeArgs;
                    }
                }
                // [m_appArgs: { mainclass, arg1, arg2, ... }]

                
                // handle cmd line-level defaults and option interaction
                {
                    if (DEFAULT_TO_SYSTEM_CLASSPATH)
                    {
                        if (m_classpath == null)
                        {
                            // TODO" this is not guaranteed to work (in WebStart etc), so double check if I should remove this
                            
                            final String systemClasspath = System.getProperty ("java.class.path", "");
                            if (systemClasspath.length () == 0)
                            {
                                // TODO: assume "." just like Sun JVMs in this case?
                                usageexit (parser, IOptsParser.SHORT_USAGE, "could not infer coverage classpath from 'java.class.path'; use an explicit -cp option");
                                return;
                            }
                            
                            m_classpath = new String [] {systemClasspath};
                        }
                    }
                    else
                    {
                        if (m_classpath == null)
                        {
                            usageexit (parser, IOptsParser.SHORT_USAGE, "either '-cp' or '-jar' option is required");
                            return;
                        }
                    }               

                    // TODO: who owns setting this 'txt' default? most likely AppRunner
                    if (m_reportTypes == null)
                    {
                        m_reportTypes = new String [] {"txt"};
                    }
                }
            }
            
            // run the app:
            {
                if ($assert.ENABLED) $assert.ASSERT (m_appArgs != null && m_appArgs.length > 0, "invalid m_appArgs");
                
                final String [] appargs = new String [m_appArgs.length - 1];
                System.arraycopy (m_appArgs, 1, appargs, 0, appargs.length);
                
                final AppRunner processor = AppRunner.create (loader);
                processor.setAppName (IAppConstants.APP_NAME); // for log prefixing
                
                processor.setAppClass (m_appArgs [0], appargs);
                processor.setCoveragePath (m_classpath, true); // TODO: an option to set 'canonical'?
                processor.setScanCoveragePath (m_scanCoveragePath);
                processor.setSourcePath (m_srcpath);
                processor.setInclExclFilter (m_ixpath);
                processor.setDumpSessionData (m_dumpRawData);
                processor.setSessionOutFile (m_outFileName);
                processor.setSessionOutMerge (m_outDataMerge);
                if ($assert.ENABLED) $assert.ASSERT (m_reportTypes != null, "m_reportTypes no set");
                processor.setReportTypes (m_reportTypes);
                processor.setPropertyOverrides (m_propertyOverrides);
                
                processor.run ();
            }
        }
        catch (EMMARuntimeException yre)
        {
            // TODO: see below
            
            exit (true, yre.getMessage (), yre, RC_UNEXPECTED); // does not return
            return;
        }
        catch (Throwable t)
        {
            // TODO: embed: OS/JVM fingerprint, build #, etc
            // TODO: save stack trace in a file and prompt user to send it to ...
            
            exit (true, "unexpected failure: ", t, RC_UNEXPECTED); // does not return
            return;
        }

        exit (false, null, null, RC_OK);
    }
    
    // protected: .............................................................


    protected runCommand (final String usageToolName, final String [] args)
    {
        super (usageToolName, args);
    }

    protected void initialize ()
    {
        // TODO: clean up instance state
        
        super.initialize ();
    }
    
    protected String usageArgsMsg ()
    {
        return "[options] class [args...] | -jar [options] jarfile [args...]";
    }
    
    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static String openJarFile (final File file)
        throws IOException
    {
        JarFile jarfile = null;
        try
        {
            jarfile = new JarFile (file, false);
            
            final Manifest manifest = jarfile.getManifest ();
            if (manifest == null) return null;
            
            final Attributes attributes = manifest.getMainAttributes ();
            if (attributes == null) return null;

            final String jarMainClass = attributes.getValue (Attributes.Name.MAIN_CLASS);
            
            return jarMainClass;
        }
        finally
        {
            if (jarfile != null) try { jarfile.close (); } catch (IOException ignore) {}
        }
    }
        
        
    private String [] m_classpath, m_srcpath;
    private boolean m_jarMode;
    private boolean m_scanCoveragePath; // defaults to false
    private String [] m_ixpath;
    private String [] m_appArgs;
    private boolean m_dumpRawData; // defaults to false
    private String m_outFileName;
    private Boolean m_outDataMerge; 
    private String [] m_reportTypes;
    
    private static final boolean DEFAULT_TO_SYSTEM_CLASSPATH = false;
        
} // end of class
// ----------------------------------------------------------------------------
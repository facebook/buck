/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: reportCommand.java,v 1.1.1.1.2.1 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.io.IOException;

import com.vladium.util.ClassLoaderResolver;
import com.vladium.util.Strings;
import com.vladium.util.args.IOptsParser;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.Command;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class reportCommand extends Command
{
    // public: ................................................................

    public reportCommand (final String usageToolName, final String [] args)
    {
        super (usageToolName, args);
    }

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
                            if ("in".equals (on))
                            {
                                m_datapath = getListOptValue (opt, PATH_DELIMITERS, true);
                            }
                            else if ("sp".equals (on))
                            {
                                m_srcpath = getListOptValue (opt, PATH_DELIMITERS, true);
                            }
                            else if ("r".equals (on))
                            {
                                m_reportTypes = Strings.merge (opt.getValues (), COMMA_DELIMITERS, true);
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
                
                // handle cmd line-level defaults:
                {
                }
            }
            
            // run the reporter:
            {
                final ReportProcessor processor = ReportProcessor.create ();
                processor.setAppName (IAppConstants.APP_NAME); // for log prefixing
                
                processor.setDataPath (m_datapath);
                processor.setSourcePath (m_srcpath);
                if ($assert.ENABLED) $assert.ASSERT (m_reportTypes != null, "m_reportTypes no set");
                processor.setReportTypes (m_reportTypes); // no "txt" default for report processor
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


    protected void initialize ()
    {
        super.initialize ();                
    }
    
    protected String usageArgsMsg ()
    {
        return "[options]";
    }

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private String [] m_datapath; // list of data files, not a real path
    private String [] m_srcpath;
    private String [] m_reportTypes;
    
} // end of class
// ----------------------------------------------------------------------------
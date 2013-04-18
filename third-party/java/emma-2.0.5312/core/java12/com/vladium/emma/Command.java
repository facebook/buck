/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Command.java,v 1.1.1.1.2.1 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Properties;

import com.vladium.logging.ILogLevels;
import com.vladium.util.IConstants;
import com.vladium.util.Property;
import com.vladium.util.Strings;
import com.vladium.util.XProperties;
import com.vladium.util.args.IOptsParser;
import com.vladium.emma.data.mergeCommand;
import com.vladium.emma.instr.instrCommand;
import com.vladium.emma.report.reportCommand;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class Command
{
    // public: ................................................................
    
    
    public static Command create (final String name, final String usageName, final String [] args)
    {
        final Command tool;
        
        // TODO: dynamic load here?
        
        if ("run".equals (name))
            tool = new runCommand (usageName, args);
        else if ("instr".equals (name))
            tool = new instrCommand (usageName, args);
        else if ("report".equals (name))
            tool = new reportCommand (usageName, args);
        else if ("merge".equals (name))
            tool = new mergeCommand (usageName, args);
        else
            throw new IllegalArgumentException ("unknown command: [" + name + "]");    
            
        tool.initialize ();
        
        return tool;
    }
    
    public abstract void run ();
    
    // protected: .............................................................

    
    protected Command (final String usageToolName, final String [] args)
    {
        m_usageToolName = usageToolName;
        m_args = args != null ? (String []) args.clone () : IConstants.EMPTY_STRING_ARRAY;  
    }
    
    protected abstract String usageArgsMsg ();

    // TODO: is this useful (separate from <init>)?
    protected void initialize ()
    {
        m_exit = false;
        
        if (m_out != null) try { m_out.flush (); } catch (Throwable ignore) {}
        m_out = new PrintWriter (System.out, true);
    }   
    
    protected final String getToolName ()
    {
        // TODO: embed build number etc
        final String clsName = getClass ().getName ();
        
        return clsName.substring (0, clsName.length () - 7);
    }
    
    protected final IOptsParser getOptParser (final ClassLoader loader)
    {
        return IOptsParser.Factory.create (usageResName (getToolName ()), loader,
            usageMsgPrefix (m_usageToolName), USAGE_OPT_NAMES);
    } 
    
    protected final boolean processOpt (final IOptsParser.IOpt opt)
    {
        final String on = opt.getCanonicalName ();
        
        if ("exit".equals (on)) // 'exit' should always be first in this else-if chain
        {
            m_exit = getOptionalBooleanOptValue (opt);
            return true;
        }
        else if ("p".equals (on))
        {
            m_propertyFile = new File (opt.getFirstValue ());
            return true;
        }
        else if ("verbose".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.VERBOSE_STRING);
            return true;
        }
        else if ("quiet".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.WARNING_STRING);
            return true;
        }
        else if ("silent".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.SEVERE_STRING);
            return true;
        }
        else if ("debug".equals (on))
        {
            if (opt.getValueCount () == 0)
                setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, ILogLevels.TRACE1_STRING);
            else
                setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_LEVEL, opt.getFirstValue ());
            
            return true;
        }
        else if ("debugcls".equals (on))
        {
            setPropertyOverride (AppLoggers.PROPERTY_VERBOSITY_FILTER, Strings.toListForm (Strings.merge (opt.getValues (), COMMA_DELIMITERS, true), ',')); 
            return true;
        }
        
        return false;
    }
    
    protected final void processCmdPropertyOverrides (final IOptsParser.IOpts parsedopts)
    {
        final IOptsParser.IOpt [] popts = parsedopts.getOpts (EMMAProperties.GENERIC_PROPERTY_OVERRIDE_PREFIX);
        if ((popts != null) && (popts.length != 0))
        {
            final Properties cmdOverrides = new XProperties ();
            
            for (int o = 0; o < popts.length; ++ o)
            {
                final IOptsParser.IOpt opt = popts [o];
                final String on = opt.getName ().substring (opt.getPatternPrefix ().length ());
                
                // TODO: support mergeable prefixed opts?
                
                cmdOverrides.setProperty (on, opt.getFirstValue ());
            }
            
            // command line user overrides are have highest precedence:
            m_propertyOverrides = Property.combine (cmdOverrides, m_propertyOverrides);
        }
    }
    
    protected final boolean processFilePropertyOverrides ()
    {
        if (m_propertyFile != null)
        {
            final Properties fileOverrides;
            
            try
            {
                fileOverrides = Property.getPropertiesFromFile (m_propertyFile);
            }
            catch (IOException ioe)
            {
                exit (true, "property override file [" + m_propertyFile.getAbsolutePath () + "] could not be read", ioe, RC_USAGE);
                return false;
            }
            
            // props file overrides have second highest precendence:
            m_propertyOverrides = Property.combine (m_propertyOverrides, fileOverrides); 
        }
        
        return true;
    }
    
    protected final void usageexit (final IOptsParser parser, final int level, final String msg)
    {
        if (msg != null)
        {
            m_out.print (usageMsgPrefix (m_usageToolName));
            m_out.println (msg);
        }
        
        if (parser != null)
        {
            m_out.println ();
            m_out.print (usageMsgPrefix (m_usageToolName));
            m_out.println (toolNameToCommandName (m_usageToolName) + " " + usageArgsMsg () + ",");
            m_out.println ("  where options include:");
            m_out.println ();
            parser.usage (m_out, level, STDOUT_WIDTH);            
        }
        
        m_out.println ();
        exit (true, null, null, RC_USAGE);
    }
    
    protected final void exit (final boolean showBuildID, final String msg, final Throwable t, final int rc)
        throws EMMARuntimeException
    {
        if (showBuildID)
        {
            m_out.println (IAppConstants.APP_USAGE_BUILD_ID);
        }
        
        if (msg != null)
        {
            m_out.print (toolNameToCommandName (m_usageToolName) + ": "); m_out.println (msg);
        }

        if (rc != RC_OK)
        {
            // error exit:
            
            //if ((showBuildID) || (msg != null)) m_out.println ();
            
            if (m_exit)
            {
                if (t != null) t.printStackTrace (m_out);
                System.exit (rc);
            }
            else
            {
                if (t instanceof EMMARuntimeException)
                    throw (EMMARuntimeException) t;
                else if (t != null)
                    throw msg != null ? new EMMARuntimeException (msg, t) : new EMMARuntimeException ("unexpected failure: ", t);
            }
        }
        else
        {
            // normal exit: 't' is ignored
            
            if (m_exit)
            {
                System.exit (0);
            }
        }
    }

    protected static boolean getOptionalBooleanOptValue (final IOptsParser.IOpt opt)
    {
        if (opt.getValueCount () == 0)
            return true;
        else
        {
            final String v = opt.getFirstValue ().toLowerCase ();
         
            return Property.toBoolean (v);
        }
    }
    
    protected static String [] getListOptValue (final IOptsParser.IOpt opt, final String delimiters, final boolean processAtFiles)
        throws IOException
    {
        return Strings.mergeAT (opt.getValues (), delimiters, processAtFiles);
    }
    
    protected static String usageMsgPrefix (final String toolName)
    {
        return toolNameToCommandName (toolName).concat (" usage: ");
    }
    
    protected static String usageResName (final String toolName)
    {
        return toolName.replace ('.', '/').concat ("_usage.res");
    }
    
    protected static String toolNameToCommandName (final String toolName)
    {
        final int lastDot = toolName.lastIndexOf ('.');
        
        return lastDot > 0 ? toolName.substring (lastDot + 1) : toolName;
    }
    

    protected final String m_usageToolName;
    protected final String [] m_args;
    
    protected File m_propertyFile;
    protected Properties m_propertyOverrides;
    protected boolean m_exit;
    protected PrintWriter m_out; // this is set independently from Logger by design
    
    protected static final String COMMA_DELIMITERS    = "," + Strings.WHITE_SPACE;
    protected static final String PATH_DELIMITERS     = ",".concat (File.pathSeparator);

    protected static final String [] USAGE_OPT_NAMES = new String [] {"h", "help"};
    protected static final int STDOUT_WIDTH = 80;    
    
    // return codes used with System.exit():
    protected static final int RC_OK          = 0;
    protected static final int RC_USAGE       = 1;
    protected static final int RC_UNEXPECTED  = 2;

    // package: ...............................................................
    
    // private: ...............................................................
    

    /*
     * Lazily instantiates m_propertyOverrides if necessary.
     */
    private void setPropertyOverride (final String key, final String value)
    {
        Properties propertyOverrides = m_propertyOverrides;
        if (propertyOverrides == null)
        {
            m_propertyOverrides = propertyOverrides = new XProperties ();
        }
        
        propertyOverrides.setProperty (key, value);
    }

} // end of class
// ----------------------------------------------------------------------------
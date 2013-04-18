/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Logger.java,v 1.1.1.1.2.2 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import com.vladium.emma.AppLoggers;
import com.vladium.emma.IAppConstants;
import com.vladium.util.ClassLoaderResolver;
import com.vladium.util.Property;
import com.vladium.util.Strings;

// ----------------------------------------------------------------------------
/**
 * A simple Java version-independent logging framework. Each Logger is also
 * an immutable context that encapsulates configuration elements like the
 * logging verbosity level etc. In general, a Logger is looked up as an
 * inheritable thread-local piece of data. This decouples classes and
 * logging configurations in a way that seems difficult with log4j.<P>
 * 
 * Note that a given class is free to cache its context in an instance field
 * if the class is instantiated and used only on a single thread (or a set of
 * threads that are guaranteed to share the same logging context). [This is
 * different from the usual log4j pattern of caching a logger in a class static
 * field]. In other cases (e.g., the instrumentation runtime), it makes more
 * sense to scope a context to a single method invocation.<P>
 * 
 * Every log message is structured as follows:
 * <OL>
 *  <LI> message is prefixed with the prefix string set in the Logger if that is
 * not null;
 *  <LI> if the calling class could be identified and it supplied the calling
 * method name, the calling method is identified with all name components that
 * are not null;
 *  <LI> caller-supplied message is logged, if not null;
 *  <LI> caller-supplied Throwable is dumped starting with a new line, if not null.
 * </OL>
 * 
 * MT-safety: a given Logger instance will not get corrupted by concurrent
 * usage from multiple threads and guarantees that data written to the underlying
 * PrintWriter in a single log call will be done in one atomic print() step.
 * 
 * @see ILogLevels
 * 
 * @author (C) 2001, Vlad Roubtsov
 */
public
final class Logger implements ILogLevels
{
    // public: ................................................................
    
    // TODO: update javadoc for 'logCaller'
    // TODO: need isLoggable (Class)
    
    public static Logger create (final int level, final PrintWriter out, final String prefix, final Set classMask)
    {
        if ((level < NONE) || (level > ALL))
            throw new IllegalArgumentException ("invalid log level: " + level);
        
        if ((out == null) || out.checkError ())
            throw new IllegalArgumentException ("null or corrupt input: out");
        
        return new Logger (level, out, prefix, classMask);
    }
    
    /**
     * This works as a cloning creator of sorts.
     * 
     * @param level
     * @param out
     * @param prefix
     * @param classMask
     * @param base
     * @return
     */
    public static Logger create (final int level, final PrintWriter out, final String prefix, final Set classMask,
                                 final Logger base)
    {
        if (base == null)
        {
            return create (level, out, prefix, classMask);
        }
        else
        {
            final int _level = level >= NONE
                ? level
                : base.m_level;
                
            final PrintWriter _out = (out != null) && ! out.checkError ()
                ? out
                : base.m_out;
            
            // TODO: do a better job of logger cloning
            final String _prefix = prefix;
//            final String _prefix = prefix != null
//                ? prefix
//                : base.m_prefix;
            
            final Set _classMask = classMask != null
                ? classMask
                : base.m_classMask;
        
        
            return new Logger (_level, _out, _prefix, _classMask);
        }
    }
  

    /**
     * A quick method to determine if logging is enabled at a given level.
     * This method acquires no monitors and should be used when calling one of
     * log() or convenience logging methods directly incurs significant
     * parameter construction overhead.
     * 
     * @see ILogLevels
     */
    public final boolean isLoggable (final int level)
    {
        return (level <= m_level);
    }

    /**
     * A convenience method equivalent to isLoggable(INFO).
     */
    public final boolean atINFO ()
    {
        return (INFO <= m_level);
    }
    
    /**
     * A convenience method equivalent to isLoggable(VERBOSE).
     */
    public final boolean atVERBOSE ()
    {
        return (VERBOSE <= m_level);
    }
    
    /**
     * A convenience method equivalent to isLoggable(TRACE1).
     */
    public final boolean atTRACE1 ()
    {
        return (TRACE1 <= m_level);
    }

    /**
     * A convenience method equivalent to isLoggable(TRACE2).
     */
    public final boolean atTRACE2 ()
    {
        return (TRACE2 <= m_level);
    }

    /**
     * A convenience method equivalent to isLoggable(TRACE3).
     */
    public final boolean atTRACE3 ()
    {
        return (TRACE3 <= m_level);
    }


    /**
     * A convenience method to log 'msg' from an anonymous calling method
     * at WARNING level.
     * 
     * @param msg log message [ignored if null]
     */
    public final void warning (final String msg)
    {
        _log (WARNING, null, msg, false);
    }
    
    /**
     * A convenience method to log 'msg' from an anonymous calling method
     * at INFO level.
     * 
     * @param msg log message [ignored if null]
     */
    public final void info (final String msg)
    {
        _log (INFO, null, msg, false);
    }
    
    /**
     * A convenience method to log 'msg' from an anonymous calling method
     * at VERBOSE level.
     * 
     * @param msg log message [ignored if null]
     */
    public final void verbose (final String msg)
    {
        _log (VERBOSE, null, msg, false);
    }

    
    /**
     * A convenience method equivalent to log(TRACE1, method, msg).
     * 
     * @param method calling method name [ignored if null]
     * @param msg log message [ignored if null]
     */
    public final void trace1 (final String method, final String msg)
    {
        _log (TRACE1, method, msg, true);
    }
    
    /**
     * A convenience method equivalent to log(TRACE2, method, msg).
     * 
     * @param method calling method name [ignored if null]
     * @param msg log message [ignored if null]
     */
    public final void trace2 (final String method, final String msg)
    {
        _log (TRACE2, method, msg, true);
    }
    
    /**
     * A convenience method equivalent to log(TRACE3, method, msg).
     * 
     * @param method calling method name [ignored if null]
     * @param msg log message [ignored if null]
     */
    public final void trace3 (final String method, final String msg)
    {
        _log (TRACE3, method, msg, true);
    }

    /**
     * Logs 'msg' from an unnamed calling method.
     * 
     * @param level level to log at [the method does nothing if this is less
     * than the set level].
     * @param msg log message [ignored if null]
     */        
    public final void log (final int level, final String msg, final boolean logCaller)
    {
        _log (level, null, msg, logCaller);
    }
    
    /**
     * Logs 'msg' from a given calling method.
     * 
     * @param level level to log at [the method does nothing if this is less
     * than the set level].
     * @param method calling method name [ignored if null]
     * @param msg log message [ignored if null]
     */        
    public final void log (final int level, final String method, final String msg, final boolean logCaller)
    {
        _log (level, method, msg, logCaller);
    }

    /**
     * Logs 'msg' from an unnamed calling method followed by the 'throwable' stack
     * trace dump.
     *  
     * @param level level to log at [the method does nothing if this is less
     * than the set level].
     * @param msg log message [ignored if null]
     * @param throwable to dump after message [ignored if null]
     */
    public final void log (final int level, final String msg, final Throwable throwable)
    {
        _log (level, null, msg, throwable);
    }
    
    /**
     * Logs 'msg' from a given calling method followed by the 'throwable' stack
     * trace dump.
     *  
     * @param level level to log at [the method does nothing if this is less
     * than the set level].
     * @param method calling method name [ignored if null]
     * @param msg log message [ignored if null]
     * @param throwable to dump after message [ignored if null]
     */
    public final void log (final int level, final String method, final String msg, final Throwable throwable)
    {
        _log (level, method, msg, throwable);
    }

    
    /**
     * Provides direct access to the PrintWriter used by this Logger. 
     * 
     * @return print writer used by this logger [never null]
     */
    public PrintWriter getWriter ()
    {
        return m_out;
    }

    
    /**
     * Returns the current top of the thread-local logger stack or the static
     * Logger instance scoped to Logger.class if the stack is empty.
     * 
     * @return current logger [never null]
     */
    public static Logger getLogger ()
    {
        final LinkedList stack = (LinkedList) THREAD_LOCAL_STACK.get ();
        
        // [assertion: stack != null]

        if (stack.isEmpty ())
        {
            return STATIC_LOGGER;
        }
        else
        {
            return (Logger) stack.getLast ();
        }
    }
    
    /**
     * 
     * @param ctx [may not be null]
     */
    public static void push (final Logger ctx)
    {
        if (ctx == null)
            throw new IllegalArgumentException ("null input: ctx");
        
        final LinkedList stack = (LinkedList) THREAD_LOCAL_STACK.get ();
        stack.addLast (ctx);
    }
    
    /**
     * Requiring a context parameter here helps enforce correct push/pop
     * nesting in the caller code.
     * 
     * @param ctx [may not be null]
     */
    public static void pop (final Logger ctx)
    {
        // TODO: add guards for making sure only the pushing thread is allowed to
        // execute this
        
        final LinkedList stack = (LinkedList) THREAD_LOCAL_STACK.get ();

        try
        {
            final Logger current = (Logger) stack.getLast ();
            if (current != ctx)
                throw new IllegalStateException ("invalid context being popped: " + ctx);
            
            stack.removeLast ();
            current.cleanup ();
        }
        catch (NoSuchElementException nsee)
        {
            throw new IllegalStateException ("empty logger context stack on thread [" + Thread.currentThread () + "]: " + nsee);
        }
    }

    
    public static int stringToLevel (final String level)
    {
        if (ILogLevels.SEVERE_STRING.equalsIgnoreCase (level) || ILogLevels.SILENT_STRING.equalsIgnoreCase (level))
            return ILogLevels.SEVERE;
        else if (ILogLevels.WARNING_STRING.equalsIgnoreCase (level) || ILogLevels.QUIET_STRING.equalsIgnoreCase (level))
            return ILogLevels.WARNING;
        else if (ILogLevels.INFO_STRING.equalsIgnoreCase (level))
            return ILogLevels.INFO;
        else if (ILogLevels.VERBOSE_STRING.equalsIgnoreCase (level))
            return ILogLevels.VERBOSE;
        else if (ILogLevels.TRACE1_STRING.equalsIgnoreCase (level))
            return ILogLevels.TRACE1;
        else if (ILogLevels.TRACE2_STRING.equalsIgnoreCase (level))
            return ILogLevels.TRACE2;
        else if (ILogLevels.TRACE3_STRING.equalsIgnoreCase (level))
            return ILogLevels.TRACE3;
        else if (ILogLevels.NONE_STRING.equalsIgnoreCase (level))
            return ILogLevels.NONE;
        else if (ILogLevels.ALL_STRING.equalsIgnoreCase (level))
            return ILogLevels.ALL;
        else
        {
            int _level = Integer.MIN_VALUE;
            try
            {
                _level = Integer.parseInt (level);
            }
            catch (Exception ignore) {}
            
            if ((_level >= ILogLevels.NONE) && (_level <= ILogLevels.ALL))
                return _level;
            else
                return ILogLevels.INFO; // default to something middle of the ground
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class ThreadLocalStack extends InheritableThreadLocal
    {
        protected Object initialValue ()
        {
            return new LinkedList ();
        }
        
    } // end of nested class

    
    private Logger (final int level, final PrintWriter out, final String prefix, final Set classMask)
    {
        m_level = level;
        m_out = out;
        m_prefix = prefix;
        m_classMask = classMask; // no defensive clone
    }

    private void cleanup ()
    {
        m_out.flush ();
    }
    
    private void _log (final int level, final String method,
                       final String msg, final boolean logCaller)
    {
        if ((level <= m_level) && (level >= SEVERE))
        {
            final Class caller = logCaller ? ClassLoaderResolver.getCallerClass (2) : null;
            final StringBuffer buf = new StringBuffer (m_prefix != null ? m_prefix + ": " : "");

            if ((caller != null) || (method != null))
            {
                buf.append ("[");
                
                if (caller != null) // if the caller could not be determined, s_classMask is ignored
                {
                    String callerName = caller.getName ();
                    
                    if (callerName.startsWith (PREFIX_TO_STRIP))
                        callerName = callerName.substring (PREFIX_TO_STRIP_LENGTH);
                        
                    String parentName = callerName;
                    
                    final int firstDollar = callerName.indexOf ('$');
                    if (firstDollar > 0) parentName = callerName.substring (0, firstDollar);
                    
                    if ((m_classMask == null) || m_classMask.contains (parentName))
                        buf.append (callerName);
                    else
                        return;
                }
                
                if (method != null)
                {
                    buf.append ("::");
                    buf.append (method);
                }
                
                buf.append ("] ");
            }

            final PrintWriter out = m_out;

            if (msg != null) buf.append (msg);
            
            out.println (buf);
            if (FLUSH_LOG) out.flush ();
        }
    }
    
    private void _log (final int level, final String method,
                       final String msg, final Throwable throwable)
    {        
        if ((level <= m_level) && (level >= SEVERE))
        {
            final Class caller = ClassLoaderResolver.getCallerClass (2);
            final StringBuffer buf = new StringBuffer (m_prefix != null ? m_prefix + ": " : "");
            
            if ((caller != null) || (method != null))
            {
                buf.append ("[");
                
                if (caller != null) // if the caller could not be determined, s_classMask is ignored
                {
                    String callerName = caller.getName ();
                    
                    if (callerName.startsWith (PREFIX_TO_STRIP))
                        callerName = callerName.substring (PREFIX_TO_STRIP_LENGTH);
                        
                    String parentName = callerName;
                    
                    final int firstDollar = callerName.indexOf ('$');
                    if (firstDollar > 0) parentName = callerName.substring (0, firstDollar);
                    
                    if ((m_classMask == null) || m_classMask.contains (parentName))
                        buf.append (callerName);
                    else
                        return;
                }
                
                if (method != null)
                {
                    buf.append ("::");
                    buf.append (method);
                }
                
                buf.append ("] ");
            }
            
            final PrintWriter out = m_out;
            
            if (msg != null) buf.append (msg);
            
            if (throwable != null)
            {
                final StringWriter sw = new StringWriter ();
                final PrintWriter pw = new PrintWriter (sw);
                
                throwable.printStackTrace (pw);
                pw.flush ();
                
                buf.append (sw.toString ());
            }

            out.println (buf);
            if (FLUSH_LOG) out.flush ();
        }
    }

    

    private final int m_level; // always in [NONE, ALL] range
    private final PrintWriter m_out; // never null
    private final String m_prefix; // null is equivalent to no prefix
    private final Set /* String */ m_classMask; // null is equivalent to no class filtering

    private static final String PREFIX_TO_STRIP = "com.vladium."; // TODO: can this be set programmatically ?
    private static final int PREFIX_TO_STRIP_LENGTH = PREFIX_TO_STRIP.length ();
    private static final boolean FLUSH_LOG = true;
    private static final String COMMA_DELIMITERS    = "," + Strings.WHITE_SPACE;

    private static final Logger STATIC_LOGGER; // set in <clinit>
    private static final ThreadLocalStack THREAD_LOCAL_STACK; // set in <clinit>
    
    static
    {
        THREAD_LOCAL_STACK = new ThreadLocalStack ();
        
        // TODO: unfortunately, this init code makes Logger coupled to the app classes
        // (via the app namespace string constants)
        // I don't quite see an elegant solution to this design problem yet
        
        final Properties properties = Property.getAppProperties (IAppConstants.APP_NAME_LC, Logger.class.getClassLoader ());
        
        // verbosity level:
        
        final int level;
        {
            final String _level = properties.getProperty (AppLoggers.PROPERTY_VERBOSITY_LEVEL,
                                                          AppLoggers.DEFAULT_VERBOSITY_LEVEL);
            level = stringToLevel (_level);
        }
        
        // verbosity filter:
        
        final Set filter;
        {
            final String _filter = properties.getProperty (AppLoggers.PROPERTY_VERBOSITY_FILTER);
            Set temp = null;
            
            if (_filter != null)
            {
                final StringTokenizer tokenizer = new StringTokenizer (_filter, COMMA_DELIMITERS);
                if (tokenizer.countTokens () > 0)
                {
                    temp = new HashSet (tokenizer.countTokens ());
                    while (tokenizer.hasMoreTokens ())
                    {
                        temp.add (tokenizer.nextToken ());
                    }
                }
            }
            
            filter = temp;
        }
        
        
        STATIC_LOGGER = create (level,
                                new PrintWriter (System.out, false),
                                IAppConstants.APP_NAME,
                                filter);
    }
    
} // end of class
// ----------------------------------------------------------------------------
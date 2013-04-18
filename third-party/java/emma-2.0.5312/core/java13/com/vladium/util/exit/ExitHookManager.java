/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ExitHookManager.java,v 1.1.1.1 2004/05/09 16:57:58 vlad_r Exp $
 */
package com.vladium.util.exit;

import java.util.HashMap;
import java.util.Map;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.vladium.util.IJREVersion;
import com.vladium.util.Property;
import com.vladium.emma.IAppConstants;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class ExitHookManager implements IJREVersion
{
    // public: ................................................................
    
    // TOTO: handle thread groups as well?
    
    public abstract boolean addExitHook (Runnable runnable);
    public abstract boolean removeExitHook (Runnable runnable);
    
    public static synchronized ExitHookManager getSingleton ()
    {
        if (s_singleton == null)
        {
            if (JRE_1_3_PLUS)
            {
                s_singleton = new JRE13ExitHookManager ();
            }
            else if (JRE_SUN_SIGNAL_COMPATIBLE)
            {
                s_singleton = new SunJREExitHookManager ();
            }
            else
            {
                throw new UnsupportedOperationException ("no shutdown hook manager available [JVM: " + Property.getSystemFingerprint () + "]");
            }
        }
        
        return s_singleton;
    }
    
    // protected: .............................................................
    
    
    protected ExitHookManager () {}

    // package: ...............................................................

    // private: ...............................................................
    
    
    private static final class JRE13ExitHookManager extends ExitHookManager
    {
        public synchronized boolean addExitHook (final Runnable runnable)
        {
            if ((runnable != null) && ! m_exitThreadMap.containsKey (runnable))
            {
                final Thread exitThread = new Thread (runnable, IAppConstants.APP_NAME + " shutdown handler thread");
                
                try
                {
                    Runtime.getRuntime ().addShutdownHook (exitThread);
                    m_exitThreadMap.put (runnable, exitThread); // TODO: use identity here
                    
                    return true;
                }
                catch (Exception e)
                {
                    System.out.println ("exception caught while adding a shutdown hook:");
                    e.printStackTrace (System.out);
                }
            }
            
            return false;
        }
        
        public synchronized boolean removeExitHook (final Runnable runnable)
        {
            if (runnable != null)
            {
                final Thread exitThread = (Thread) m_exitThreadMap.get (runnable);  // TODO: use identity here
                
                if (exitThread != null)
                {
                    try
                    {
                        Runtime.getRuntime ().removeShutdownHook (exitThread);
                        m_exitThreadMap.remove (runnable);
                        
                        return true;
                    }
                    catch (Exception e)
                    {
                        System.out.println ("exception caught while removing a shutdown hook:");
                        e.printStackTrace (System.out);
                    }
                }
            }
            
            return false;
        }
        
        JRE13ExitHookManager ()
        {
            m_exitThreadMap = new HashMap ();
        }
        
        
        private final Map /* Runnable->Thread */ m_exitThreadMap;
        
    } // end of nested class
    
    
    private static final class SunJREExitHookManager extends ExitHookManager
    {
        public synchronized boolean addExitHook (final Runnable runnable)
        {
            if ((runnable != null) && ! m_signalHandlerMap.containsKey (runnable))
            {
                final INTSignalHandler handler = new INTSignalHandler (runnable);
                
                try
                {
                    handler.register ();
                    m_signalHandlerMap.put (runnable, handler); // TODO: use identity here
                    
                    return true;
                }
                catch (Throwable t)
                {
                    System.out.println ("exception caught while adding a shutdown hook:");
                    t.printStackTrace (System.out);
                }
            }
            
            return false;
        }
        
        public synchronized boolean removeExitHook (final Runnable runnable)
        {
            if (runnable != null)
            {
                final INTSignalHandler handler = (INTSignalHandler) m_signalHandlerMap.get (runnable);  // TODO: use identity here
                if (handler != null)
                {
                    try
                    {
                        handler.unregister ();
                        m_signalHandlerMap.remove (runnable);
                        
                        return true;
                    }
                    catch (Exception e)
                    {
                        System.out.println ("exception caught while removing a shutdown hook:");
                        e.printStackTrace (System.out);
                    }
                }
            }
            
            return false;
        }
        
        SunJREExitHookManager ()
        {
            m_signalHandlerMap = new HashMap ();
        }
        
        
        private final Map /* Runnable->INTSignalHandler */ m_signalHandlerMap;
        
    } // end of nested class
    
    
    private static final class INTSignalHandler implements SignalHandler
    {
        public synchronized void handle (final Signal signal)
        {
            if (m_runnable != null)
            {
                try
                {
                    m_runnable.run ();
                }
                catch (Throwable ignore) {}
            }
            m_runnable = null;
            
            if ((m_previous != null) && (m_previous != SIG_DFL) && (m_previous != SIG_IGN))
            {
                try
                {
                    // this does not work:
                    //Signal.handle (signal, m_previous);
                    //Signal.raise (signal);
                    
                    m_previous.handle (signal);
                }
                catch (Throwable ignore) {}
            }
            else
            {
                System.exit (0);
            }
        }
        
        INTSignalHandler (final Runnable runnable)
        {
            m_runnable = runnable;
        }
       
        synchronized void register ()
        {
            m_previous = Signal.handle (new Signal ("INT"), this);
        }
        
        synchronized void unregister ()
        {
//            if (m_previous != null)
//            {
//                Signal.handle (new Signal ("INT"), m_previous);
//                m_previous = null;
//            }

            m_runnable = null;
        }


        private Runnable m_runnable;
        private SignalHandler m_previous;
        
    } // end of nested class
    
    
    private static ExitHookManager s_singleton;
    
} // end of class
// ----------------------------------------------------------------------------
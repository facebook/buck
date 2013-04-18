/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: InstrClassLoader.java,v 1.1.1.1.2.2 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma.rt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.cert.Certificate;
import java.security.CodeSource;
import java.util.Map;

import com.vladium.logging.Logger;
import com.vladium.util.ByteArrayOStream;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.filter.IInclExclFilter;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class InstrClassLoader extends URLClassLoader
{
    // public: ................................................................
        
    // TODO: proper security [use PrivilegedAction as needed]
    // TODO: [see above as well] need to keep track of res URLs to support [path] exclusion patterns
    // TODO: improve error handling so it is clear when errors come from buggy instrumentation
    
    
    public static final String PROPERTY_FORCED_DELEGATION_FILTER  = "clsload.forced_delegation_filter";
    public static final String PROPERTY_THROUGH_DELEGATION_FILTER  = "clsload.through_delegation_filter";
        

    public InstrClassLoader (final ClassLoader parent, final File [] classpath,
                             final IInclExclFilter forcedDelegationFilter,
                             final IInclExclFilter throughDelegationFilter,
                             final IClassLoadHook hook, final Map cache)
        throws MalformedURLException
    {
        // setting ClassLoader.parent to null disables the standard delegation
        // behavior in a few places, including URLClassLoader.getResource():
        
        super (filesToURLs (classpath), null);
        
        // TODO: arg validation
        
        m_hook = hook;
        m_cache = cache; // can be null
        
        m_forcedDelegationFilter = forcedDelegationFilter;
        m_throughDelegationFilter = throughDelegationFilter;
         
        m_parent = parent;        
        m_bufPool = new PoolEntry [BAOS_POOL_SIZE];
        
        m_log = Logger.getLogger ();
    }
    
    /**
     * Overrides java.lang.ClassLoader.loadClass() to change the usual parent-child
     * delegation rules just enough to be able to 'replace' the parent loader. This
     * also has the effect of detecting 'system' classes without doing any class
     * name-based matching.
     */
    public synchronized final Class loadClass (final String name, final boolean resolve)
        throws ClassNotFoundException
    {
        final boolean trace1 = m_log.atTRACE1 ();
        
        if (trace1) m_log.trace1 ("loadClass",  "(" + name + ", " + resolve + "): nest level " + m_nestLevel);
        
        Class c = null;
        
        // first, check if this class has already been defined by this classloader
        // instance:
        c = findLoadedClass (name);
        
        if (c == null)
        {
            Class parentsVersion = null;
            if (m_parent != null)
            {
                try
                {
                    parentsVersion = m_parent.loadClass (name); // note: it is important that this does not init the class
                    
                    if ((parentsVersion.getClassLoader () != m_parent) ||
                        ((m_forcedDelegationFilter == null) || m_forcedDelegationFilter.included (name)))
                    {
                        // (a) m_parent itself decided to delegate: use parent's version
                        // (b) the class was on the forced delegation list: use parent's version
                        c = parentsVersion;
                        if (trace1) m_log.trace1 ("loadClass", "using parent's version for [" + name + "]");
                    }
                }
                catch (ClassNotFoundException cnfe)
                {
                    // if the class was on the forced delegation list, error out: 
                    if ((m_forcedDelegationFilter == null) || m_forcedDelegationFilter.included (name))
                        throw cnfe;
                }
            }
            
            if (c == null)
            {
                try
                {
                    // either (a) m_parent was null or (b) it could not load 'c'
                    // or (c) it will define 'c' itself if allowed to. In any
                    // of these cases I attempt to define my own version:
                    c = findClass (name);
                }
                catch (ClassNotFoundException cnfe)
                {
                    // this is a difficult design point unless I resurrect the -lx option
                    // and document how to use it [which will confuse most users anyway]
                    
                    // another alternative would be to see if parent's version is included by
                    // the filter and print a warning; still, it does not help with JAXP etc 
                    
                    if (parentsVersion != null)
                    {
                        final boolean delegate = (m_throughDelegationFilter == null) || m_throughDelegationFilter.included (name); 
                        
                        if (delegate)
                        {
                            c = parentsVersion;
                            if (trace1) m_log.trace1 ("loadClass", "[delegation filter] using parent's version for [" + name + "]");
                        }
                        else
                            throw cnfe;
                    }
                    else
                      throw cnfe;
                }
            }
        }
        
        if (c == null) throw new ClassNotFoundException (name);
        
        if (resolve) resolveClass (c); // this never happens in J2SE JVMs
        return c;
    }
    
    // TODO: remove this in the release build
    
    public final URL getResource (final String name)
    {
        final boolean trace1 = m_log.atTRACE1 ();
        
        if (trace1) m_log.trace1 ("getResource",  "(" + name + "): nest level " + m_nestLevel);
        
        final URL result = super.getResource (name);
        if (trace1 && (result != null)) m_log.trace1 ("loadClass",  "[" + name + "] found in " + result);
        
        return result;
    }
    
    // protected: .............................................................
    
    
    protected final Class findClass (final String name)
        throws ClassNotFoundException
    {
        final boolean trace1 = m_log.atTRACE1 ();
        
        if (trace1) m_log.trace1 ("findClass",  "(" + name + "): nest level " + m_nestLevel);
        
        final boolean useClassCache = (m_cache != null);
        final ClassPathCacheEntry entry = useClassCache ? (ClassPathCacheEntry) m_cache.remove (name) : null;
        
        byte [] bytes;
        int length;
        URL classURL = null;
            
        if (entry != null) // cache hit
        {
            ++ m_cacheHits;
            
            // used cached class def bytes, no need to repeat disk I/O:
            
            try
            {
                classURL = new URL (entry.m_srcURL);
            }
            catch (MalformedURLException murle) // this should never happen
            {
                if ($assert.ENABLED)
                {
                    murle.printStackTrace (System.out);
                }
            }
            
            PoolEntry buf = null;
            try
            {
                buf = acquirePoolEntry ();
                final ByteArrayOStream baos = buf.m_baos; // reset() has been called on this
                
                // the original class definition:
                bytes = entry.m_bytes;
                length = bytes.length;
                
                if ((m_hook != null) && m_hook.processClassDef (name, bytes, length, baos)) // note: this can overwrite 'bytes'
                {
                    // the instrumented class definition:
                    bytes = baos.getByteArray ();
                    length = baos.size ();
                    
                    if (trace1) m_log.trace1 ("findClass",  "defining [cached] instrumented [" + name + "] {" + length + " bytes }");
                }
                else
                {
                    if (trace1) m_log.trace1 ("findClass",  "defining [cached] [" + name + "] {" + length + " bytes }");
                }
                
                return defineClass (name, bytes, length, classURL);
            }
            catch (IOException ioe)
            {
                throw new ClassNotFoundException (name);
            }
            finally
            {
                if (buf != null) releasePoolEntry (buf);
            }
        }
        else // cache miss
        {
            if (useClassCache) ++ m_cacheMisses;
            
            // .class files are not guaranteed to be loadable as resources;
            // but if Sun's code does it...
            final String classResource = name.replace ('.', '/') + ".class";
            
            // even thought normal delegation is disabled, this will find bootstrap classes:
            classURL = getResource (classResource); // important to hook into URLClassLoader's overload of this so that Class-Path manifest attributes are processed etc
            
            if (trace1 && (classURL != null)) m_log.trace1 ("findClass",  "[" + name + "] found in " + classURL);
            
            if (classURL == null)
                throw new ClassNotFoundException (name);
            else
            {
                InputStream in = null;
                PoolEntry buf = null;
                try
                {
                    in = classURL.openStream ();
                    
                    buf = acquirePoolEntry ();
                    final ByteArrayOStream baos = buf.m_baos; // reset() has been called on this
                    
                    readFully (in, baos, buf.m_buf);
                    in.close (); // don't keep the file handle across reentrant calls
                    in = null;
                    
                    // the original class definition:
                    bytes = baos.getByteArray ();
                    length = baos.size ();
                    
                    baos.reset (); // reuse this for processClassDef below
                    
                    if ((m_hook != null) && m_hook.processClassDef (name, bytes, length, baos)) // note: this can overwrite 'bytes'
                    {
                        // the instrumented class definition:
                        bytes = baos.getByteArray ();
                        length = baos.size ();
                        
                        if (trace1) m_log.trace1 ("findClass",  "defining instrumented [" + name + "] {" + length + " bytes }");
                    }
                    else
                    {
                        if (trace1) m_log.trace1 ("findClass",  "defining [" + name + "] {" + length + " bytes }");
                    }
                    
                    return defineClass (name, bytes, length, classURL);
                }
                catch (IOException ioe)
                {
                    throw new ClassNotFoundException (name);
                }
                finally
                {
                    if (buf != null) releasePoolEntry (buf);
                    if (in != null) try { in.close (); } catch (Exception ignore) {}
                }
            }
        }
    }
    
    public void debugDump (final PrintWriter out)
    {
        if (out != null)
        {
            out.println (this + ": " + m_cacheHits + " class cache hits, " + m_cacheMisses + " misses");
        }
    }

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class PoolEntry
    {
        PoolEntry (final int baosCapacity, final int bufSize)
        {
            m_baos = new ByteArrayOStream (baosCapacity);
            m_buf = new byte [bufSize];
        }
        
        void trim (final int baosCapacity, final int baosMaxCapacity)
        {
            if (m_baos.capacity () > baosMaxCapacity)
            {
                m_baos = new ByteArrayOStream (baosCapacity);
            }
        }
        
        ByteArrayOStream m_baos;
        final byte [] m_buf;
        
    } // end of nested class
    
    
    /*
     * 'srcURL' may be null
     */
    private Class defineClass (final String className, final byte [] bytes, final int length, final URL srcURL)
    {
        // support ProtectionDomains with non-null class source URLs:
        // [however, disable anything related to sealing or signing]
        
        final CodeSource csrc = new CodeSource (srcURL, (Certificate[])null);
        
        // allow getPackage() to return non-null on the class we are about to
        // define (however, don't bother emulating the original manifest info since
        // we may be altering manifest content anyway):
        
        final int lastDot = className.lastIndexOf ('.');
        if (lastDot >= 0)
        {
            final String packageName = className.substring (0, lastDot);
            
            final Package pkg = getPackage (packageName);
            if (pkg == null)
            {
                definePackage (packageName,
                               IAppConstants.APP_NAME, IAppConstants.APP_VERSION, IAppConstants.APP_COPYRIGHT,
                               IAppConstants.APP_NAME, IAppConstants.APP_VERSION, IAppConstants.APP_COPYRIGHT,
                               srcURL);
            }
        }
        
        return defineClass (className, bytes, 0, length, csrc);
    }
    
    
    private static URL [] filesToURLs (final File [] classpath)
        throws MalformedURLException
    {
        if ((classpath == null) || (classpath.length == 0))
            return EMPTY_URL_ARRAY;
            
        final URL [] result = new URL [classpath.length];
        
        for (int f = 0; f < result.length ; ++ f)
        {
            result [f] = classpath [f].toURL (); // note: this does proper dir encoding
        }
        
        return result;
    }
    
    /**
     * Reads the entire contents of a given stream into a flat byte array.
     */
    private static void readFully (final InputStream in, final ByteArrayOStream out, final byte [] buf)
        throws IOException
    {
        for (int read; (read = in.read (buf)) >= 0; )
        {
            out.write (buf, 0, read);
        }
    }
    
    /*
     * not MT-safe; must be called from loadClass() only
     */
    private PoolEntry acquirePoolEntry ()
    {
        PoolEntry result;
        
        if (m_nestLevel >= BAOS_POOL_SIZE)
        {
            result = new PoolEntry (BAOS_INIT_SIZE, BAOS_INIT_SIZE);
        }
        else
        {
            result = m_bufPool [m_nestLevel];
            if (result == null)
            {
                result = new PoolEntry (BAOS_INIT_SIZE, BAOS_INIT_SIZE);
                m_bufPool [m_nestLevel] = result;
            }
            else
            {
                result.m_baos.reset ();
            }
        }
        
        ++ m_nestLevel;
            
        return result;
    }
    
    /*
     * not MT-safe; must be called from loadClass() only
     */
    private void releasePoolEntry (final PoolEntry buf)
    {
        if (-- m_nestLevel < BAOS_POOL_SIZE)
        {
            buf.trim (BAOS_INIT_SIZE, BAOS_MAX_SIZE);
        }
    }
    
    
    private final ClassLoader m_parent;    
    
    private final IInclExclFilter m_forcedDelegationFilter;
    private final IInclExclFilter m_throughDelegationFilter;
    
    private final Map /* classJavaName:String -> ClassPathCacheEntry */ m_cache; // can be null
    private final IClassLoadHook m_hook;
    private final PoolEntry [] m_bufPool;
    
    private final Logger m_log; // a loader instance is used concurrently but cached its log config at construction time
    
    private int m_nestLevel;
    
    private int m_cacheHits, m_cacheMisses;
    
    private static final int BAOS_INIT_SIZE = 32 * 1024;
    private static final int BAOS_MAX_SIZE = 1024 * 1024;
    private static final int BAOS_POOL_SIZE = 8;
    private static final URL [] EMPTY_URL_ARRAY = new URL [0];
    
} // end of class
// ----------------------------------------------------------------------------

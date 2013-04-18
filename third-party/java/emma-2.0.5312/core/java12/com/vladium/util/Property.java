/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Property.java,v 1.1.1.1.2.4 2004/07/16 23:32:04 vlad_r Exp $
 */
package com.vladium.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

/*
 * NOTE: to avoid certain build problems, this class should use only
 * core Java APIs and not any app infrastructure.
 */

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class Property
{
    // public: ................................................................


    public static boolean toBoolean (final String value)
    {
        if (value == null)
            return false;
        else
            return value.startsWith ("t") || value.startsWith ("y");
    }

    
    /**
     * NOTE: this does not guarantee that the result will be mutatable
     * independently from 'overrides' or 'base', so this method
     * should be used for read-only property only
     * 
     * @param overrides [null is equivalent to empty]
     * @param base [null is equivalent to empty]
     * 
     * @return [never null, could be empty]
     */
    public static Properties combine (final Properties overrides, final Properties base)
    {
        // note: no defensive copies here
         
        if (base == null)
        {
            if (overrides == null)
                return new XProperties ();
            else
                return overrides;
        }
        
        // [assertion: base != null]
        
        if (overrides == null) return base;
        
        // [assertion: both 'overrides' and 'base' are not null]
        
        final Properties result = new XProperties (base);
        
        // note: must use propertyNames() because that is the only method that recurses
        // into possible bases inside 'overrides'
        
        for (Enumeration overrideNames = overrides.propertyNames (); overrideNames.hasMoreElements (); )
        {
            final String n = (String) overrideNames.nextElement ();
            final String v = overrides.getProperty (n);
            
            result.setProperty (n, v);
        }
        
        return result;
    }
    
    /**
     * Creates a set of properties for an application with a given namespace.
     * This method is not property aliasing-aware.
     * 
     * @param namespace application namespace [may not be null]
     * @param loader classloader to use for any classloader resource lookups
     * [null is equivalent to the applicaton classloader]
     * @return application properties [never null, a new instance is created
     * on each invocation]
     */
    public static Properties getAppProperties (final String namespace, final ClassLoader loader)
    {
        if (namespace == null)
            throw new IllegalArgumentException ("null properties: appNameLC");
        
        final Properties appDefaults = Property.getProperties (namespace + "_default.properties", loader);
        final Properties systemFileOverrides;
        {
            final String fileName = Property.getSystemProperty (namespace + ".properties");
            final File file = fileName != null
                ? new File (fileName)
                : null;

            systemFileOverrides = Property.getLazyPropertiesFromFile (file);
        }
        final Properties systemOverrides = Property.getSystemProperties (namespace);
        final Properties resOverrides = Property.getProperties (namespace + ".properties", loader);
        
        return combine (resOverrides,
               combine (systemOverrides,
               combine (systemFileOverrides,
                        appDefaults)));
    }
    
    public static Properties getSystemProperties (final String systemPrefix)
    {
        // note: this method is not synchronized on purpose
        
        Properties result = s_systemProperties;
        if (result == null)
        {
            result = new SystemPropertyLookup (systemPrefix);
            
            s_systemProperties = result;
            return result;
        }
        
        return result;
    }
    
    public static Properties getSystemPropertyRedirects (final Map systemRedirects)
    {
        // note: this method is not synchronized on purpose
        
        Properties result = s_systemRedirects;
        if (result == null)
        {
            result = new SystemRedirectsLookup (systemRedirects);
            
            s_systemRedirects = result;
            return result;
        }
        
        return result;
    }


    public static String getSystemFingerprint ()
    {
        // [not synchronized intentionally]
        
        if (s_systemFingerprint != null)
            return s_systemFingerprint;
        else
        {
            final StringBuffer s = new StringBuffer ();
            final char delimiter = ':';
            
            s.append (getSystemProperty ("java.vm.name", ""));
            s.append (delimiter);
            s.append (getSystemProperty ("java.vm.version", ""));
            s.append (delimiter);
            s.append (getSystemProperty ("java.vm.vendor", ""));
            s.append (delimiter);
            s.append (getSystemProperty ("os.name", ""));
            s.append (delimiter);
            s.append (getSystemProperty ("os.version", ""));
            s.append (delimiter);
            s.append (getSystemProperty ("os.arch", ""));
            
            s_systemFingerprint = s.toString ();
            return s_systemFingerprint;
        }
    }    
    
    public static String getSystemProperty (final String key)
    {
        try
        {
            return System.getProperty (key);
        }
        catch (SecurityException se)
        {
            return null;
        }
    }
    
    public static String getSystemProperty (final String key, final String def)
    {
        try
        {
            return System.getProperty (key, def);
        }
        catch (SecurityException se)
        {
            return def;
        }
    }
    
    /**
     * does not throw
     * 
     * @param name
     * @return
     */
    public static Properties getProperties (final String name)
    {
        Properties result = null;
        
        InputStream in = null;
        try
        {
            in = ResourceLoader.getResourceAsStream (name);
            if (in != null)
            {
                result = new XProperties ();
                result.load (in);
            }
        }
        catch (Throwable t)
        {
            result = null;
        }
        finally
        {
            if (in != null) try { in.close (); } catch (Throwable ignore) {}
            in = null;
        }
        
        return result;
    }
    
    /**
     * does not throw
     * 
     * @param name
     * @param loader
     * @return
     */
    public static Properties getProperties (final String name, final ClassLoader loader)
    {
        Properties result = null;
        
        InputStream in = null;
        try
        {
            in = ResourceLoader.getResourceAsStream (name, loader);
            if (in != null)
            {
                result = new XProperties ();
                result.load (in);
            }
        }
        catch (Throwable t)
        {
            result = null;
        }
        finally
        {
            if (in != null) try { in.close (); } catch (Throwable ignore) {}
            in = null;
        }
        
        return result;
    }

    /**
     * Loads 'file' as a .properties file.
     * 
     * @param file [may not be null]
     * @return read properties [never null]
     * @throws IOException on any file I/O errors
     */
    public static Properties getPropertiesFromFile (final File file)
        throws IOException
    {
        if (file == null)
            throw new IllegalArgumentException ("null input: file");
        
        Properties result = null;
        
        InputStream in = null;
        try
        {
            in = new BufferedInputStream (new FileInputStream (file), 8 * 1024);

            result = new XProperties ();
            result.load (in);
        }
        finally
        {
            if (in != null) try { in.close (); } catch (Throwable ignore) {}
            in = null;
        }
        
        return result;
    }    

    /**
     * Returns a lazy property implementation that will read 'load' as a .properties
     * file on first use. If there are any file I/O errors when reading the file,
     * they will be thrown as runtime exceptions (also on first use).
     * 
     * @param file [can be null, which results in an empty property set returned]
     * @return [never null]
     */
    public static Properties getLazyPropertiesFromFile (final File file)
    {
        return new FilePropertyLookup (file);
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class FilePropertyLookup extends XProperties
    {
        // note: due to incredibly stupid coding in java.util.Properties
        // (getProperty() uses a non-virtual call to get(), while propertyNames()
        // uses a virtual call to the same instead of delegating to getProperty())
        // I must override both methods below
        
        public String getProperty (final String key)
        {
            faultContents ();
            
            return m_contents.getProperty (key);
        }
            
        public Object get (final Object key)
        {
            faultContents ();
            
            return m_contents.get (key);
        }
        
        /*
         * Overrides Properties.keys () [this is used for debug logging only]
         */
        public Enumeration keys ()
        {
            faultContents ();
            
            return m_contents.keys ();
        }


        /**
         * Creates a lazy property lookup based on 'src' contents.
         * 
         * @param src [null will result in empty property set created]
         */
        FilePropertyLookup (final File src)
        {
            m_src = src;
        }
        
        /*
         * @throws RuntimeException on file I/O failures. 
         */
        private synchronized void faultContents ()
        {
            Properties contents = m_contents;
            if ((contents == null) && (m_src != null))
            {
                try
                {
                    contents = getPropertiesFromFile (m_src);
                }
                catch (RuntimeException re)
                {
                    throw re; // re-throw;
                }
                catch (Exception e)
                {
                    throw new RuntimeException ("exception while processing properties file [" + m_src.getAbsolutePath () + "]: " + e);
                }
            }
            
            if (contents == null)
            {
                contents = new XProperties (); // non-null marker
            }
            
            m_contents = contents;
        }
        

        private final File m_src; // can be null
        private Properties m_contents; // non-null after faultContents()
        
    } // end of nested class
    

    private static final class SystemPropertyLookup extends XProperties
    {
        // note: due to incredibly stupid coding in java.util.Properties
        // (getProperty() uses a non-virtual call to get(), while propertyNames()
        // uses a virtual call to the same instead of delegating to getProperty())
        // I must override both methods below
        
        public String getProperty (final String key)
        {
            return (String) get (key);
        }
            
        public Object get (final Object key)
        {
            if (! (key instanceof String)) return null;
            
            String result = (String) super.get (key);
            if (result != null) return result;
            
            if (m_systemPrefix != null)
            {
                result = getSystemProperty (m_systemPrefix.concat ((String) key), null);
                
                if (result != null) return result;
            }
            
            return result;
        }
        
        /*
         * Overrides Properties.keys () [this is used for debug logging only]
         */
        public synchronized Enumeration keys ()
        {
            final Hashtable _propertyNames = new Hashtable ();
            
            if (m_systemPrefix != null)
            {
                try
                {
                    final int systemPrefixLength = m_systemPrefix.length ();
                    
                    for (Enumeration e = System.getProperties ().propertyNames ();
                         e.hasMoreElements (); )
                    {
                        final String n = (String) e.nextElement ();
                        
                        if (n.startsWith (m_systemPrefix))
                        {
                            final String yn = n.substring (systemPrefixLength);
                            
                            _propertyNames.put (yn, yn);
                        }
                    } 
                }
                catch (SecurityException ignore)
                {
                    ignore.printStackTrace (System.out);
                    
                    // continue
                }
            }
            
            return _propertyNames.keys ();
        }

                
        SystemPropertyLookup (String systemPrefix)
        {
            if ((systemPrefix != null) && ! systemPrefix.endsWith ("."))
                systemPrefix = systemPrefix.concat (".");
                
            m_systemPrefix = systemPrefix;
        }
        
        
        private final String m_systemPrefix; // can be null [if not null, normalized to end with "."]
        
    } // end of nested class
    
    
    private static final class SystemRedirectsLookup extends XProperties
    {
        // note: due to incredibly stupid coding in java.util.Properties
        // (getProperty() uses a non-virtual call to get(), while propertyNames()
        // uses a virtual call to the same instead of delegating to getProperty())
        // I must override both methods below
        
        public String getProperty (final String key)
        {
            return (String) get (key);
        }
            
        public Object get (final Object key)
        {
            if (! (key instanceof String)) return null;
            
            String result = (String) super.get (key);
            if (result != null) return result;
            
            if (m_systemRedirects != null)
            {
                final String redirect = (String) m_systemRedirects.get (key);
                
                if (redirect != null)
                {
                    result = getSystemProperty (redirect, null);
                    if (result != null) return result;
                }
            }
            
            return result;
        }
        
        /*
         * Overrides Properties.keys () [this is used for debug logging only]
         */
        public synchronized Enumeration keys ()
        {
            final Hashtable _propertyNames = new Hashtable ();
            
            if (m_systemRedirects != null)
            {
                for (Iterator i = m_systemRedirects.keySet ().iterator ();
                     i.hasNext (); )
                {
                    final Object key = i.next ();                    
                    if (key != null) _propertyNames.put (key , key);
                }
            }
            
            return _propertyNames.keys ();
        }

                
        SystemRedirectsLookup (final Map systemRedirects)
        {
            m_systemRedirects = systemRedirects; // note: no defensive copy
        }
        
        
        private final Map m_systemRedirects; // can be null
        
    } // end of nested class
    
    
    private static String s_systemFingerprint;
    private static Properties s_systemProperties, s_systemRedirects;
    
} // end of class
// ----------------------------------------------------------------------------
/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: EMMAProperties.java,v 1.1.1.1.2.3 2004/07/16 23:32:03 vlad_r Exp $
 */
package com.vladium.emma;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import com.vladium.util.ClassLoaderResolver;
import com.vladium.util.IProperties;
import com.vladium.util.Property;
import com.vladium.emma.report.IReportProperties;
import com.vladium.emma.report.ReportProperties;

// ----------------------------------------------------------------------------
/**
 * A reflection of "${IAppConstants.APP_PROPERTY_RES_NAME}.properties" resource
 * as viewed by a given classloader.
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class EMMAProperties
{
    // public: ................................................................
    
    public static final String GENERIC_PROPERTY_OVERRIDE_PREFIX = "D";

    // [the DEFAULT_xxx settings duplicate the defaults in APP_DEFAULT_PROPERTIES_RES_NAME
    // resource to provide a safe fallback option if that resource cannot be loaded]
    
    public static final String DEFAULT_META_DATA_OUT_FILE       = "coverage.em";
    public static final Boolean DEFAULT_META_DATA_OUT_MERGE     = Boolean.TRUE;
    public static final String PREFIX_META_DATA                 = "metadata.";
    public static final String PROPERTY_META_DATA_OUT_FILE      = PREFIX_META_DATA + "out.file";
    public static final String PROPERTY_META_DATA_OUT_MERGE     = PREFIX_META_DATA + "out.merge";
    
    public static final String DEFAULT_COVERAGE_DATA_OUT_FILE   = "coverage.ec";
    public static final Boolean DEFAULT_COVERAGE_DATA_OUT_MERGE = Boolean.TRUE;
    public static final String PREFIX_COVERAGE_DATA             = "coverage.";
    public static final String PROPERTY_COVERAGE_DATA_OUT_FILE  = PREFIX_COVERAGE_DATA + "out.file";
    public static final String PROPERTY_COVERAGE_DATA_OUT_MERGE = PREFIX_COVERAGE_DATA + "out.merge";

    public static final String DEFAULT_SESSION_DATA_OUT_FILE    = "coverage.es";
    public static final Boolean DEFAULT_SESSION_DATA_OUT_MERGE  = Boolean.TRUE;
    public static final String PREFIX_SESSION_DATA              = "session.";
    public static final String PROPERTY_SESSION_DATA_OUT_FILE   = PREFIX_SESSION_DATA + "out.file";
    public static final String PROPERTY_SESSION_DATA_OUT_MERGE  = PREFIX_SESSION_DATA + "out.merge";
    
    public static final String PROPERTY_TEMP_FILE_EXT           = ".et";
    
    public static final Map SYSTEM_PROPERTY_REDIRECTS; // set in <clinit>
    
    
    /**
     * Global method used to create an appearance that all app work has been
     * done at the same point in time (useful for setting archive and report
     * timestamps etc).
     * 
     * @return the result of System.currentTimeMillis (), evaluated on the
     * first call only
     */
    public static synchronized long getTimeStamp ()
    {
        long result = s_timestamp;
        if (result == 0)
        {
            s_timestamp = result = System.currentTimeMillis ();
        }

        return result; 
    }
    
    
    public static String makeAppVersion (final int major, final int minor, final int build)
    {
        final StringBuffer buf = new StringBuffer ();
        
        buf.append (major);
        buf.append ('.');
        buf.append (minor);
        buf.append ('.');
        buf.append (build);
        
        return buf.toString ();
    }
    
    
    /**
     * Wraps a Properties into a IProperties with the app's standard property
     * mapping in place.
     * 
     * @param properties [null results in null result]
     */
    public static IProperties wrap (final Properties properties)
    {
        if (properties == null) return null;
        
        return IProperties.Factory.wrap (properties, ReportProperties.REPORT_PROPERTY_MAPPER);
    }
   
    /**
     * Retrieves application properties as classloader resource with a given name.
     * [as seen from ClassLoaderResolver.getClassLoader ()]. The result is cached
     * using this loader as a weak key.
     * 
     * @return properties [can be null]
     */
    public static synchronized IProperties getAppProperties ()
    {
        final ClassLoader loader = ClassLoaderResolver.getClassLoader ();
        
        return getAppProperties (loader);
    }

    public static synchronized IProperties getAppProperties (final ClassLoader loader)
    {
        IProperties properties = (IProperties) s_properties.get (loader);

        if (properties != null)
            return properties;
        else
        {
            final String appName = IAppConstants.APP_NAME_LC;
            
            // note: this does not use Property.getAppProperties() by design,
            // because that mechanism is not property alias-capable
            
            final IProperties systemRedirects = wrap (Property.getSystemPropertyRedirects (EMMAProperties.SYSTEM_PROPERTY_REDIRECTS));
            final IProperties appDefaults = wrap (Property.getProperties (appName + "_default.properties", loader));
            final IProperties systemFile;
            {
                final String fileName = Property.getSystemProperty (appName + ".properties");
                final File file = fileName != null
                    ? new File (fileName)
                    : null;

                systemFile = wrap (Property.getLazyPropertiesFromFile (file));
            }
            final IProperties system = wrap (Property.getSystemProperties (appName));
            final IProperties userOverrides = wrap (Property.getProperties (appName + ".properties", loader));
            
            // "vertical" inheritance order:
            //      (1) user overrides ("emma.properties" classloader resource)
            //      (2) system properties (java.lang.System.getProperties(),
            //                             filtered by the app prefix)
            //      (3) system file properties ("emma.properties" system property,
            //                                  interpreted as a property file)
            //      (4) app defaults ("emma_default.properties" classloader resource)
            //      (5) system property redirects (report.out.encoding->file.encoding,
            //                                     report.out.dir->user.dir, etc)
        
            properties = IProperties.Factory.combine (userOverrides,
                         IProperties.Factory.combine (system,
                         IProperties.Factory.combine (systemFile,
                         IProperties.Factory.combine (appDefaults,
                                                      systemRedirects))));

            s_properties.put (loader, properties);
            
            return properties;
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private EMMAProperties () {} // prevent subclassing
        
    
    private static long s_timestamp;
    
    private static final Map /* ClassLoader->Properties */ s_properties; // set in <clinit>
    
    static
    {
        s_properties = new WeakHashMap ();
        
        final Map redirects = new HashMap ();
        redirects.put (IReportProperties.PREFIX.concat (IReportProperties.OUT_ENCODING),
                       "file.encoding");
        redirects.put (IReportProperties.PREFIX.concat (IReportProperties.OUT_DIR),
                       "user.dir");
                       
        SYSTEM_PROPERTY_REDIRECTS = Collections.unmodifiableMap (redirects);
    }

} // end of class
// ----------------------------------------------------------------------------

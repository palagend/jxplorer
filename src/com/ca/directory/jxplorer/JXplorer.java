package com.ca.directory.jxplorer;

import com.ca.commons.cbutil.*;
import com.ca.commons.jndi.JNDIOps;
import com.ca.commons.jndi.JndiSocketFactory;
import com.ca.commons.naming.*;
import com.ca.commons.security.cert.CertViewer;
import com.ca.directory.BuildNumber;
import com.ca.directory.jxplorer.broker.*;
import com.ca.directory.jxplorer.event.*;
import com.ca.directory.jxplorer.search.SearchBar;
import com.ca.directory.jxplorer.tree.*;
import com.ca.directory.jxplorer.viewer.AttributeDisplay;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.security.Provider;
import java.security.Security;
import java.util.*;
import java.util.logging.*;

/**
 * Does the main setup for JXplorer.
 */
public class
        JXplorer extends JFrame         			// Applet
        implements JXplorerEventGenerator
{
    static String version = BuildNumber.value;

    private static JFrame rootFrame;                 	// convenience variables to avoid
    Container mainPane;                      			// calling get methods all the time.

    transient JXplorerListener jxplorerListener;

    EventListenerList eventListeners = new EventListenerList();

    JScrollPane explorePanel;                   // contains mr tree
    JScrollPane resultsPanel;                   // contains search tree
    JScrollPane schemaPanel;                    // contains schema tree

    JTabbedPane treeTabPane;

    JPanel userViewPanel;

    CBPanel topPanel;                        	// the top panel, containing the tool bars.
    JToolBar searchBar;                      	// the quick search toolbar.
    ButtonBar buttonBar;                     	// the graphic button bar.

    public static Properties myProperties;   	// global variables for the browser, read from...
    public static String propertyFile;       	// ...a user configurable file storing default properties.
    public static String localDir;           	// local directory the browser is being run from...

    public static JFrame jx;

    JNDIBroker jndiBroker = null;      	// the JNDIBroker intermediary class through which requests pass
    JNDIBroker searchBroker = null;      	// another JNDIBroker used for searching, and the search tree.
    OfflineBroker offlineBroker = null;      	// provides a gateway to ldif files.
    SchemaBroker schemaBroker = null;      	// provides access to an artificaial 'schema tree'

    SmartTree mrTree = null;      	// the display tree
    SmartTree searchTree = null;      	// the search results tree
    SmartTree schemaTree = null;      	// the schema display tree

    AttributeDisplay mainViewer;				// the main display panel

    CBPanel statusDisplay;
    JLabel displayLabel;

    boolean workOffline = false;

    public static boolean debug = false;
    public static int debugLevel = 0;

    protected Stack statusStack = new Stack();

    protected MainMenu mainMenu;

    protected static ButtonRegister buttonRegister = null;      //TE: Object that is used by JXplorer to register all its buttons and menu items.

    protected CBHelpSystem helpSystem;

    protected StopMonitor stopMonitor;

    public Thread jndiThread, schemaThread, searchThread, offlineThread;

    public String url = "Disconnected";         //TE: an anchor for the LDAP/DSML url.

    CBResourceLoader resourceLoader;  			// loads files 'n stuff from zip/jar archives.
    CBClassLoader classLoader;        			// loads classes from zip/jar archives.

    /*
     *    Constants that define which security property elements to use.
     */

    public static final String CLIENT_TYPE_PROPERTY = "keystoreType.clientcerts";
    public static final String CA_TYPE_PROPERTY = "keystoreType.cacerts";
    public static final String CLIENT_PATH_PROPERTY = "option.ssl.clientcerts";
    public static final String CA_PATH_PROPERTY = "option.ssl.cacerts";
    public static final String ALLOW_CONNECTION_CERT_IMPORT = "option.ssl.import.cert.during.connection";

    private static Logger log = Logger.getLogger(JXplorer.class.getName());  // ...It's round it's heavy it's wood... It's better than bad, it's good...

    /**
     * Constructor for the JXplorer object, which is in fact the whole browser.
     */

    // PROG NOTE: Many of these methods are order dependant - don't change the
    //            order they are called without checking!

    boolean connected = false;  //TE: a vague flag that is set to true if the user hits the connect button, false if user hits disconnect button.  This is for changing the state of the buttons when flicking between tabs.

    public JXplorer()
    {
        super();
        JXplorerInit((String)null);
    }

    public JXplorer(String initLdifName)
    {
        super();
	JXplorerInit(initLdifName);
    }

    protected void JXplorerInit(String initLdifName)
    {
	DataSource datamodifier;

        JWindow splash = new JWindow();

        showSplashScreen(splash);

        rootFrame = this;
        mainPane = rootFrame.getContentPane();
        mrTree = null;

        loadProperties(myProperties);

        setupLogger();  // requires properties to be loaded first

        initUtilityFtns(this);

        setupResourceFiles();

        CBIntText.init("language.JX", classLoader);   // i18n support.


        if (checkFileEnvironment() == false) return;

        initJNDIBroker();

        initSearchBroker();

        initSchemaBroker();

        initOfflineBroker();

        initStopMonitor();

        buttonRegister = new ButtonRegister();

        setupGUI();

        setStatus(CBIntText.get("Not Connected"));

        setBackground(Color.white);

	datamodifier = (DataSource)this.offlineBroker;

	if (initLdifName != null) {
	    this.setStatus("Working Offline");
	    this.workOffline = true ;
	    this.offlineBroker.clear();
	    this.mrTree.registerDataSource(this.offlineBroker);
	    this.mrTree.setRoot(new DN(SmartTree.NODATA));

            LdifImport imp = new LdifImport(datamodifier, this.mrTree, this, null, initLdifName);
	}

        setVisible(true);

        splash.dispose();
    }

    public static void printTime(String msg)
    {
        long time = System.currentTimeMillis();
        log.info(msg + "\nTIME: " + new Date().toString() + "  (" + (time % 1000) + ")\n");
    }

    /**
     * The main class, from whence all else proceeds.
     * (Obviously, this is not used if the browser is
     * run as an applet instead...)
     *
     * @param args Not currently used.
     */

    public static void main(String[] args)
    {
        printTime("main start");

        log.fine("running JXplorer version " + version);

	boolean loadFile;
	String initLdifName;

        if (checkJavaEnvironment() == false)
            System.exit(-1);

	if (args.length > 0) {
	    log.info("trying to open " + args[0]);
	    initLdifName=args[0];
	} else {
	    initLdifName=null;
	}

        new JXplorer(initLdifName);

        printTime("main end");
    }

    /**
     * Failback routine for if we can't find proper log parameters... setup a console logger
     */

    protected static void setupBackupLogger()
    {
        Logger mainLogger = LogManager.getLogManager().getLogger("com.ca");

        // property default value is 'WARNING'
        mainLogger.setLevel(Level.parse(getProperty("java.util.logging.ConsoleHandler.level")));
        Handler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        mainLogger.addHandler(handler);
    }

    /**
     * Initialises the log manager using log configuration set in the properties file
     */
    protected static void setupLogger()
    {
        // REQUIRES loadProperties() to have been called.

        log.info("setting up logger");

        try
        {
            // Sun logging system weird.  Cascading log levels only work if you pre-create exact loggers. whatever.
            Logger.getLogger("com");
            Logger.getLogger("com.ca");
            Logger.getLogger("com.ca.directory");
            Logger.getLogger("com.ca.directory.jxplorer");

            // XXX Have to reinitialise 'log' here, because Sun logging system is too stupid to do the cascading trick
            // XXX unless the 'parent' loggers have been already created.
            log = Logger.getLogger(JXplorer.class.getName());

            LogManager logManager = LogManager.getLogManager();
            logManager.reset();

            /* DEBUG
            Enumeration names = logManager.getLoggerNames();
            while (names.hasMoreElements())
                System.out.println("LOGGER: " + names.nextElement());

            System.out.println("JX: " + JXplorer.class.getName());
            System.out.println("JX: " + JXplorer.class.getName());
            */

            logManager.readConfiguration(new FileInputStream(propertyFile));
            System.out.println("XXX logging initially level " + CBUtility.getTrueLogLevel(log) + " with " + log.getHandlers().length + " parents=" + log.getUseParentHandlers());

            log.info("Using configuration file: " + propertyFile);
            log.info("logging initialised to global level " + CBUtility.getTrueLogLevel(log));

            // DEBUG BLOCK
            /*
            log.severe("ENABLED");
            log.warning("ENABLED");
            log.info("ENABLED");
            log.fine("ENABLED");
            log.finer("ENABLED");
            log.finest("ENABLED");
             */
            if (false) throw new IOException();
        }
        catch (IOException e)
        {
            log.log(Level.SEVERE, "Unable to load log configuration from config file: " + propertyFile, e);
            System.err.println("Unable to load log configuration from config file: " + propertyFile);
            e.printStackTrace();
            setupBackupLogger();

        }

        int currentLogLevel = CBUtility.getTrueLogLevel(log).intValue();

        // XXX 'ALL' is a very, very large negatice number, because Sun are fucked in the head.

        if (currentLogLevel <= Level.FINE.intValue())
        {
            Vector sortedKeys = new Vector();
            Enumeration baseKeys = myProperties.keys();
            while (baseKeys.hasMoreElements())
            {
                String key = (String) baseKeys.nextElement();
                sortedKeys.addElement(key);
            }
            Collections.sort(sortedKeys);

            Enumeration propNames = sortedKeys.elements();

            StringBuffer propertyData = new StringBuffer();
            String propName;

            while (propNames.hasMoreElements())
            {
                propName = (String) propNames.nextElement();
                propertyData.append("property: ").append(propName).append(" = ").append(myProperties.getProperty(propName)).append("\n");
            }

            log.fine("property:\n" + propertyData.toString());
        }

        /*  Never did get this stuff working properly... log4j xml config file.
        try
        {
            final String DEFAULT_XML_LOG_FILE_TEXT =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "    <!DOCTYPE log4j:configuration SYSTEM \"log4j.dtd\">\n" +
                "    <log4j:configuration xmlns:log4j=\"http://jakarta.apache.org/log4j/\"> \n" +
                "  \n" +
                "        <appender name=\"JX\" class=\"org.apache.log4j.FileAppender\"> \n" +
                "                <param name=\"File\"   value=\"JX.log\" /> \n" +
                "                <param name=\"Append\" value=\"false\" />\n" +
                "                <layout class=\"org.apache.log4j.PatternLayout\"> \n" +
                "                    <param name=\"ConversionPattern\" value=\"%t %-5p %c{2} - %m%n\"/> \n" +
                "                </layout>\n" +
                "        </appender>  \n" +
                "  \n" +
                "        <category name=\"org.apache.log4j.xml\"> \n" +
                "          <priority value=\"info\" /> \n" +
                "          <appender-ref ref=\"JX\" /> \n" +
                "        </category> \n" +
                " \n" +
                "        <root> \n" +
                "           <priority value =\"info\" /> \n" +
                "           <appender-ref ref=\"JX\" /> \n" +
                "        </root>\n" +
                " \n" +
                "    </log4j:configuration> \n";

            String logFileName = getProperty("log4j.config", "log4j.xml");
            File logFile = new File(logFileName);
            if (logFile.exists() == false)
            {
                FileWriter writer = new FileWriter(logFile);
                writer.write(DEFAULT_XML_LOG_FILE_TEXT);
                writer.close();
            }
            DOMConfigurator.configure(logFile.getPath());
        }
        catch (IOException e)
        {
            System.err.println("Unable to write/read log config file: " + e.toString());
        }
        catch (Exception e2)
        {
            System.err.println("Unexpected error setting up log file.");
            e2.printStackTrace();
        }
        */
    }

    /**
     * Set up some common utility ftns for logging and error reporting.
     */
    public void initUtilityFtns(JFrame rootFrame)
    {

        CBUtility.initDefaultDisplay(rootFrame);
    }

    /**
     *    A common start point.  This  method is where the JXplorer object
     *    is created and initialised, and should be called by the main
     *    method, the applet initialiser, or by external programs wishing
     *    to incorporate JXplorer.
     *
     */
/*
    public static void start(Properties myProperties)
    {
        CBUtility.log("operating system name: " + System.getProperty("os.name"));
        CBUtility.log("operating system architecture: " + System.getProperty("os.arch"));
        CBUtility.log("operating system version: " + System.getProperty("os.version"));

        if (checkJavaEnvironment()==false) return;

        JXplorer newExplorer = new JXplorer();
    }
*/
    /**
     * Checks that the java and system environment is
     * adequate to run in.
     */

    public static boolean checkJavaEnvironment()
    {
        log.info("running java from: " + System.getProperty("java.home"));
        String javaVersion = System.getProperty("java.version");
        log.info("running java version " + javaVersion);
        if (javaVersion.compareTo("1.4") < 0)
        {
            log.severe(CBIntText.get("TERMINATING: JXplorer requires Security Extensions and other features found only in java 1.4.0 or better."));
            JOptionPane.showMessageDialog(null, CBIntText.get("TERMINATING: JXplorer requires java 1.4.0 or better"), CBIntText.get("The Current Java Version is {0}", new String[]{javaVersion}), JOptionPane.ERROR_MESSAGE);
            return false;
        }

        return true;
    }

    /**
     * Checks that the file directories are valid, and
     * contain any vital files.
     */

    public static boolean checkFileEnvironment()
    {
        return true;
    }

    /**
     * Convenience function to access both the System
     * property list, or failing that the internal JXplorer
     * properties list.
     *
     * @param key property key; see the java.util.Properties class.
     */

    public static String getProperty(String key)
    {
        if (System.getProperty(key) != null)
            return System.getProperty(key);

        if (myProperties.containsKey(key))
            return myProperties.getProperty(key);

        return null;
    }


    /**
     * Convenience function to access the internal JXplorer properties list.
     *
     * @param key          property key; see the java.util.Properties class.
     * @param defaultValue the default value for the property if none is found pre-defined
     */

    public static String getProperty(String key, String defaultValue)
    {
        if (myProperties == null) return defaultValue;
        return myProperties.getProperty(key, defaultValue);
    }

    /**
     * Sometimes it is more convenient to simply pass the whole properties
     * hash to a subordinate class, rather than using the get...() methods
     */

    public static Properties getMyProperties()
    {
        return myProperties;
    }


    /**
     * sets a property in the dxserver property list.
     */
    public static void setProperty(String key, String value)
    {
        if (key != null)
            myProperties.setProperty(key, value);

    }

    /**
     * Activate any special actions linked to
     * changes in logging levels (currently BER and SSL tracing)
     */
    public void checkSpecialLoggingActions()
    {
            if (CBUtility.getTrueLogLevel(log) == Level.ALL)
                jndiBroker.setTracing(true);
            else
                jndiBroker.setTracing(false);
    }

    /**
     * Sets the default values for properties.  This is overridden if the
     * property appears in the system properties list or config text file.
     * (The system properties list always takes priority.)<p>
     *
     * @param key   the unique key of the property.
     * @param value the actual value to set.
     * @return the final value of the property.
     */

    public static String setDefaultProperty(String key, String value)
    {
        if (System.getProperty(key) != null)
            return System.getProperty(key);

        if (myProperties.containsKey(key))
            return myProperties.getProperty(key);

        myProperties.setProperty(key, value);
        return value;
    }

    /**
     * Sets the default values for properties.  This is overridden if the
     * property appears in the system properties list or config text file.
     * (The system properties list always takes priority.)<p>
     * <p/>
     * Also adds a comment for the property file.
     *
     * @param key     the unique key of the property
     * @param value   the actual value to set
     * @param comment an optional comment
     * @return the final value of the property
     */
    public static String setDefaultProperty(String key, String value, String comment)
    {
        if (comment != null && comment.length() > 0)
        {
            myProperties.put(key + ".comment", comment);
        }

        return setDefaultProperty(key, value);
    }


    /**
     * Initialises the myProperties property list, and sets
     * default values for properties not in the config file.
     * <p/>
     * Note that file names use the File.separator character, (which
     * is system dependant) while URLs use '/' always.
     */


    public static void loadProperties(Properties suppliedProperties)
    {
        localDir = System.getProperty("user.dir") + File.separator;

        if (suppliedProperties == null)  // the usual case
        {
            String configFileName = "jxconfig.txt";
            propertyFile = CBUtility.getPropertyConfigPath(configFileName);

            myProperties = CBUtility.readPropertyFile(propertyFile);

        }
        else
        {
            myProperties = suppliedProperties;
        }

        // initialise the 'myProperties' variable (also used in setDefaultProperty())

        setDefaultProperty("url.defaultdirectory", "localhost", "default for empty connection screen GUI - rarely used");
        setDefaultProperty("url.defaultdirectory.port", "389", "default for empty connection screen GUI - rarely used");
        setDefaultProperty("url.defaultadmin", "localhost", "default value for a specific 3rd party plug in; rarely used");
        setDefaultProperty("url.defaultadminport", "3389", "default value for a specific 3rd party plug in; rarely used");
        setDefaultProperty("baseAdminDN", "cn=Management System", "default value for a specific 3rd party plug in; rarely used");


        /*
         *    File and URL defaults.  Many of these are the same thing in two forms;
         *    one for accessing the files directly, the other for accessing them
         *    as a URL.
         *
         *    The properties are of the form dir.* or url.*, with dir referring to
         *    a file directory and url referring to the directory as a URL access.
         *
         *    dir.local and url.local is the directory the app is currently running in.
         *    *.htmldocs is the directory with the doco in it; i.e. help etc.
         *    *.templates is the root directory for the html attribute template files.
         *
         *    XXX this is wierd.  Rewrite it all nicer.
         */

        setProperty("dir.comment", "this sets the directories that JXplorer reads its resources from.");
        setDefaultLocationProperty("dir.local", localDir);
        setDefaultLocationProperty("dir.help", localDir + "help" + File.separator);
        setDefaultLocationProperty("dir.plugins", localDir + "plugins" + File.separator);

        setDefaultProperty("width", "800", "set by client GUI - don't change");

        setDefaultProperty("height", "600", "set by client GUI - don't change");

        setDefaultProperty("baseDN", "c=au", "the default base DN for an empty connection - rarely used");

        setDefaultProperty("ldapversion", "3", "set by client GUI - don't change");

        // java log setup

        setDefaultProperty(".level", "WARNING", "(java loggin variable) - allowable values are 'OFF', 'SEVERE', 'WARNING', 'INFO', 'FINE', 'FINER', 'FINEST' and 'ALL'");

        setDefaultProperty("com.ca.level", "UNUSED", " (java loggin variable) partial logging is also available.  Be warned that the Sun logging system is a very buggy partial reimplementation of log4j, and doesn't seem to do inheritance well.");


        //setDefaultProperty("logging", "console");
        //setProperty("logging.comment", "allowable log modes: none | console | file | both");
        //setDefaultProperty("log4j.config", "log4j.xml");
        //setProperty("logging.comment", "this is not used for all logging - the logging system is still a bit primative, and in the process of migraing to java logging");

        setDefaultProperty("handlers", "java.util.logging.ConsoleHandler", "(java logging variable) This sets the log level for console reporting");

        setDefaultProperty("java.util.logging.ConsoleHandler.level", "ALL", "(java logging variable) This sets the log level for console reporting");

        setDefaultProperty("java.util.logging.ConsoleHandler.formatter", "java.util.logging.SimpleFormatter", "(java logging variable) This sets the built in formatter to use for console reporting");

        setDefaultProperty("java.util.logging.FileHandler.level", "ALL", "(java loggin variable) This sets the log level for log file reporting");

        setDefaultProperty("java.util.logging.FileHandler.pattern", "JX%u.log", "(java loggin variable) The name of the log file (see java.util.logging.FileHandler java doc)");

        setDefaultProperty("java.util.logging.FileHandler.formatter", "java.util.logging.SimpleFormatter", "(java loggin variable) This sets the built in formatter to use for file reporting");


        //setDefaultProperty("log.debuglevel", "warning", "allowable debug levels; severe, warning (default), info, fine, finer, finest (includes  BER dump) - usually set by client GUI.");

        setDefaultProperty("null.entry.editor", "defaulteditor", "the editor displayed for null entries is pluggable and can be set to a custom java class");

        setDefaultProperty("plugins.ignoreUniqueness", "false", "whether to allow multiple plugins for the same object class: 'true' or 'false");

        setDefaultProperty("option.ignoreSchemaOnSubmission", "false", "Skip client side schema checks; useful if JXplorer is getting confused or the schema is inconsistent");

        setDefaultProperty("option.ldap.timeout", "0", "the maximum time to allow a query to run before cancelling - '0' = 'as long as the server allows'");

        setDefaultProperty("option.ldap.limit", "0", "The maximum number of entries to return - '0' = 'all the server allows'");

        setDefaultProperty("option.ldap.referral", JNDIOps.DEFAULT_REFERRAL_HANDLING, "this is a jdni variable determinning how referrals are handled: 'ignore','follow' or 'throw'");   // 'ignore'


        setDefaultProperty("option.ldap.browseAliasBehaviour", JNDIOps.DEFAULT_ALIAS_HANDLING, "jndi variable setting how aliases are handled while browsing: 'always','never','finding','searching'");    	// behaviour when browsing tree (= 'finding')

        setDefaultProperty("option.ldap.searchAliasBehaviour", "searching", "jndi variable setting how aliases are handled while searching: 'always','never','finding','searching'");  	// behaviour when making search request

        setDefaultProperty("option.confirmTableEditorUpdates", "false", "whether the user is prompted before updates; usually set by GUI");  		//TE: set false by default for dxadmin (bug 2848).

        setDefaultProperty("option.url.handling", "JXplorer", "override URL handling to launch JXplorer rather than default browser");  		            //TE: set the URL handling to displaying JXplorer rather than launch into default browser.

        setDefaultProperty("option.ldap.sendVerboseBinarySuffix", "false", "some directories require ';binary' to be explicitly appended to binary attribute names: 'true' or 'false'");

        setDefaultProperty("option.drag.and.drop", "true", "set to 'false' to disable drag and drop in the left hand tree view");

        setDefaultProperty("jxplorer.cache.passwords", "true", "whether JX should keep a (run time only) cache of passwords for reuse and reconnection");
        
        setDefaultProperty("mask.raw.passwords", "true", "whether to mask userPassword in the entry password editor");

        setDefaultProperty("sort.by.naming.attribute", "false", "if true, this sorts entries in the tree editor by naming attribute first, then by attribute value");

        if ("true".equals(getProperty("option.ldap.sendVerboseBinarySuffix")))
        {
            log.fine("using verbose binary suffix ';binary'");  // Warning: logger may not yet be initialised
            DXAttribute.setVerboseBinary(true);  // default if 'false'
        }
        /*
         *    Security defaults
         */

        setDefaultProperty(CA_PATH_PROPERTY, localDir + "security" + File.separator + "cacerts");
        setDefaultProperty(CLIENT_PATH_PROPERTY, localDir + "security" + File.separator + "clientcerts");
        setDefaultProperty(CLIENT_TYPE_PROPERTY, "JKS");
        setDefaultProperty(CA_TYPE_PROPERTY, "JKS");
        setDefaultProperty(ALLOW_CONNECTION_CERT_IMPORT, "true");
        // echo the above back as a system property so that independant trust stores can access it globally.  Ugly? Yes.
        System.setProperty(ALLOW_CONNECTION_CERT_IMPORT, getProperty(ALLOW_CONNECTION_CERT_IMPORT));

//        setDefaultProperty("securityProvider", "sun.security.provider.Sun");
//        set default security provider to match alljssl.jar
        setDefaultProperty("securityProvider", "com.sun.net.ssl.internal.ssl.Provider");
        setProperty("securityProvider.comment", "the security provider can be changed, and three more can be added by creating 'securityProperty0', 'securityProperty1' and 'securityProperty2'.");

        // SECURITY/SSL HANDLER
        setDefaultProperty("ldap.sslsocketfactory", "com.ca.commons.jndi.JndiSocketFactory");
        setProperty("ldap.sslsocketfactory.comment", "This is the built in ssl factory - it can be changed if required.");

        setDefaultProperty("gui.lookandfeel", UIManager.getSystemLookAndFeelClassName());    //TE: sets the default look and feel to the system default.
        setDefaultProperty("gui.lookandfeel.comment", "Can set to com.sun.java.swing.plaf.mac.MacLookAndFeel for OSX");    //TE: sets the default look and feel to the system default.

        setDefaultProperty("last.search.filter", "default");    //TE: sets the last filter property to 'default'.

        /*
         *    Check if we need to read system properties.
         */

        setDefaultProperty("getSystemEnvironment.comment", "Set this to true if you wish to add the system environment properties to the JX list (e.g. if you are setting JX properties via system variables)");
        setDefaultProperty("getSystemEnvironment", "false");

        if (getProperty("getSystemEnvironment").equalsIgnoreCase("true"))
        {
            CBSystemProperties.loadSystemProperties();
        }



        // XXX something of a hack - manually set these properties in CertViewer,
        // XXX simply because it is a 'sorta' pluggable editor, that may be called
        // XXX directly without an opportunity to pass them directly.

        CertViewer.setProperties(myProperties);

        //TE: sets up the help link in the cert viewer so that a help button is
        //TE: added to the dialog which links to the appropriate topic in the help.
        CertViewer.setupHelpLink(HelpIDs.SSL_VIEW);



        // optional support for xml in ldif files.
        setDefaultProperty("xml.ldif.rfc", "false");    //option to save XML text in ldif files
        setDefaultProperty("xml.ldif.rfc.comment", "Experimental support for saving XML in LDIF files in editable form (e.g. not base64 encoded)");
        if ("true".equals(getProperty("xml.ldif.rfc")))
            LdifUtility.setSupportXML_LDIF_RFC(true);


        // write out default property file if non exists...

        if (new File(propertyFile).exists() == false)
            writePropertyFile();
    }

    /**
     * loads a property representing a file directory (XXX or url), and checks that
     * that it exists.  If either the property doesn't exist, or the actual
     * directory doesn't exist, then the default is used instead...
     */
    protected static void setDefaultLocationProperty(String propName, String defaultLocation)
    {
        setDefaultProperty(propName, defaultLocation);
        String newLocation = getProperty(propName);
        if (!newLocation.equals(defaultLocation))
        {
            File test = new File(newLocation);
            if (!test.exists())
            {
                log.warning("Uunable to find location '" + newLocation + "' -> reverting to '" + defaultLocation + "'");
                setProperty(propName, defaultLocation);
            }
        }
    }

    public void initJNDIBroker()
    {
        jndiBroker = new JNDIBroker();
        if (CBUtility.getTrueLogLevel(log) == Level.ALL)
            jndiBroker.setTracing(true);  // set BER tracing on.

        jndiBroker.setTimeout(Integer.parseInt(getProperty("option.ldap.timeout")));
        jndiBroker.setLimit(Integer.parseInt(getProperty("option.ldap.limit")));

        jndiThread = new Thread(jndiBroker, "jndiBroker Thread");
        jndiThread.start();
    }

    public void initSearchBroker()
    {
        searchBroker = new JNDIBroker(jndiBroker);
        searchThread = new Thread(searchBroker, "searchBroker Thread");
        searchThread.start();
    }


    public void initSchemaBroker()
    {
        schemaBroker = new SchemaBroker(jndiBroker);

        schemaThread = new Thread(schemaBroker, "schemaBroker Thread");
        schemaThread.start();
    }

    /**
     * initialise the offline broker, used for viewing ldif
     * files independantly of a working directory.
     */
    public void initOfflineBroker()
    {
        offlineBroker = new OfflineBroker(this);

        offlineThread = new Thread(offlineBroker, "offlineBroker Thread");
        offlineThread.start();
    }

    public void initStopMonitor()
    {
        Broker[] brokerList = {jndiBroker, searchBroker, schemaBroker, offlineBroker};
        stopMonitor = new StopMonitor(brokerList, this);
    }

    /**
     * returns the current stop monitor object.
     */

    public StopMonitor getStopMonitor()
    {
        return stopMonitor;
    }

    /**
     * Starts the GUI off, calling subroutines to initialise the
     * overall window, the menu and the main panel.
     */

    protected void setupGUI()
    {
        setupLookAndFeel();

        setupWindowButtons();    // set response to window button clicks

        setupHelp();             // set up the JavaHelp system

        setupMenu();             // setup the menu items

        setupMainPanel();        // set up the main viewing panel

        setupStatusDisplay();    // set up the status panel

        setupFrills();           // do funny icons and logos 'n stuff

        positionBrowser();       // set the size and location of the main browser window.
    }


    /**
     * Set the position and size of the browser to be (where possible) the same
     * As it was the last time it was used.
     */

    protected void positionBrowser()
    {
        int width, height, xpos, ypos;

        try
        {
            width = Integer.parseInt(getProperty("width"));
            height = Integer.parseInt(getProperty("height"));
            xpos = Integer.parseInt(getProperty("xpos"));
            ypos = Integer.parseInt(getProperty("ypos"));
        }
        catch (Exception e)
        {
            width = 800;
            height = 600;  // emergency fallbacks
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            xpos = (screen.width - width) / 2;
            ypos = (screen.height - height) / 2;
        }

        // In this unusual case, the centering will be slightly off - but we probably don't care.
        if (width < 100) width = 100;
        if (height < 100) height = 100;

        setBounds(xpos, ypos, width, height);
        setSize(width, height);
    }

    /**
     * This sets the initial look and feel to the local system
     * look and feel, if possible; otherwise uses the java default.
     * (Note that user can change this using the 'view' menu item,
     * set up below.)
     */

    protected void setupLookAndFeel()
    {
        try
        {
            UIManager.setLookAndFeel(getProperty("gui.lookandfeel"));        //TE: gets the look and feel from the property file.
        }
        catch (Exception exc)
        {
            log.warning("WARNING: Can't load Look and Feel: " + exc);
            try
            {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                setProperty("gui.lookandfeel", UIManager.getCrossPlatformLookAndFeelClassName());
            }
            catch (Exception exc2)
            {
                log.warning("ERRROR: Can't load sys Look and Feel either! : " + exc2);
            }
        }
    }

    /**
     * Sets up the window behaviour to close the application on
     * a window close.
     */

    protected void setupWindowButtons()
    {
        addWindowListener(new WindowAdapter()
        {
            public void windowClosing(WindowEvent ev)
            {
                shutdown();
            }
        });
    }


    /**
     * Sets up the menu items, along with appropriate listeners
     */

    protected void setupMenu()
    {
        if (getProperty("gui.menu", "true").equals("true"))
            mainMenu = new MainMenu(this);
    }


    /**
     * Returns Main Menu.
     *
     * @return the main menue.
     */

    public MainMenu getMainMenu()
    {
        return mainMenu;
    }


    /**
     * Returns the JNDIBroker used for searching, and the search tree.
     *
     * @return the search broker.
     */

    public JNDIBroker getSearchBroker()
    {
        return searchBroker;
    }


    /**
     * Returns the tree's tabbed pane.
     *
     * @return the tabbed pane.
     */

    public JTabbedPane getTreeTabPane()
    {
        return treeTabPane;
    }


    /**
     * Returns the Explore panel.
     *
     * @return the explore panel.
     */

    public JScrollPane getExplorePanel()
    {
        return explorePanel;
    }


    /**
     * Returns the panel for displaying the search results.
     *
     * @return the results panel.
     */

    public JScrollPane getResultsPanel()
    {
        return resultsPanel;
    }


    /**
     * Returns the search display tree.
     *
     * @return the search (or results) tree.
     */

    public SmartTree getSearchTree()
    {
        return searchTree;
    }


    /**
     * Returns the display tree.
     *
     * @return the explore tree.
     */

    public SmartTree getTree()
    {
        return mrTree;
    }


    /**
     * Returns the schema display tree.
     *
     * @return the schema tree.
     */

    public SmartTree getSchemaTree()
    {
        return schemaTree;
    }


    /**
     * Returns the JXplorer frame.
     *
     * @return the root frame.
     */

    public static JFrame getRootFrame()
    {
        return rootFrame;
    }


    /**
     * Returns the Attribute Display.
     *
     * @return the main viewer.
     */

    public AttributeDisplay getAttributeDisplay()
    {
        return mainViewer;
    }


    /**
     * Sets up the main panel, and places the directory viewing
     * panels in it.
     */

    protected void setupMainPanel()
    {

        setupToolBars();

        /*  XXX - seems to cause some sort of evil thread contention stuff - DISABLE
         *
         *  Spot of evil - stick these slow graphics init-ing options in a background thread
         *  to give the user a chance to do stuff (i.e. open a connection) while these are
         *  still loading...
         */
/*
        Thread worker = new Thread()
        {
            public void run()
            {
*/
        setupActiveComponents();   // initialise the tree, result panel etc.
        // prior to adding to the tabbed panes below
        setupMainWorkArea();       // set up the split screen and tabbed panels.

        mainPane.setBackground(Color.lightGray);

        mainViewer.registerClassLoader(classLoader);

        validate();
/*
            }
        };
        worker.setPriority(2);
        worker.start();
*/
    }

    protected void setupToolBars()
    {

        topPanel = new CBPanel();
        searchBar = new SearchBar(this);  // set up the tool bar with quick search
        buttonBar = new ButtonBar(this);  // sets up the tool bar with the graphics icons

        //buttonBar.setSize(topPanel.getWidth(), 20);
        topPanel.makeWide();

        topPanel.add(buttonBar);
        topPanel.addln(searchBar);
        mainPane.add(topPanel, BorderLayout.NORTH);

        mainPane.setBackground(Color.white);

        if (getProperty("gui.buttonbar", "true").equals("false"))
            buttonBar.setVisible(false);
        if (getProperty("gui.searchbar", "true").equals("false"))
            searchBar.setVisible(false);
    }

    /**
     * this is where smart objects such as the tree
     * viewer, the results viewer (from the search class)
     * and the attribute viewers get added to the panes.
     */
    protected void setupActiveComponents()
    {
        mainViewer = new AttributeDisplay(myProperties, JXplorer.this, resourceLoader);

        mrTree = new SmartTree(this, CBIntText.get("Explore"), resourceLoader);
        mrTree.setBackground(new Color(0xF7F9FF));
        initialiseTree(mrTree, mainViewer, this);

        searchTree = new SmartTree(this, CBIntText.get("Results"), resourceLoader);
        searchTree.setBackground(new Color(0xEEFFFF));
        initialiseTree(searchTree, mainViewer, this);

        schemaTree = new SmartTree(this, CBIntText.get("Schema"), resourceLoader);
        schemaTree.setBackground(new Color(0xEEFFEE));
        schemaTree.getTree().setEditable(false);
        initialiseTree(schemaTree, mainViewer, this);

        mainViewer.registerComponents(mainMenu, buttonBar, mrTree.getTree(), mrTree.getPopupTool(), this);
    }

    public void initialiseTree(SmartTree tree, DataSink viewer, JXplorerEventGenerator gen)
    {
        if (viewer != null) tree.registerDataSink(viewer);
        if (gen != null) tree.registerEventPublisher(gen);
    }
    /**
     * The Status panel is the small (one line) panel at the bottom
     * of the browser that reports to users what is happening with
     * the browser (e.g. 'connecting', 'disconnected' etc.)
     */
    protected void setupStatusDisplay()
    {
        statusDisplay = new CBPanel();
        statusDisplay.makeHeavy();
        displayLabel = new JLabel(CBIntText.get("initialising..."));
        statusDisplay.addln(displayLabel);
        mainPane.add(statusDisplay, BorderLayout.SOUTH);
    }

    public String getStatus()
    {
        return displayLabel.getText();
    }

    /**
     * Sets a status message that is displayed on the bottom of
     * the screen.
     */

    public void setStatus(String s)
    {
        displayLabel.setText(s);
        displayLabel.repaint();          // XXX paintology
    }

    /**
     * saves the old Status message on the status stack
     * for later use, and sets status to a new message.
     *
     * @param newMessage the new status message to set
     *                   (note - this new Message is *not* saved on the stack!)
     */

    public void pushStatus(String newMessage)
    {
        statusStack.push(displayLabel.getText());
        setStatus(newMessage);
    }

    /**
     * recalls a status message saved via @pushStatus,
     * as well as setting it using @setStatus.
     *
     * @return the saved status message, in case anyone cares
     */

    public String popStatus()
    {
        String status;
        if (statusStack.empty())
            status = "";   // sanity check
        else
            status = (String) statusStack.pop();

        setStatus(status);
        return status;    // in case someone is interested...
    }

    /**
     * Sets up the main work area, below the tool bar,
     * which displays the tree/browser panel, and the
     * results panel...
     */

    protected void setupMainWorkArea()
    {
        // make sure stuff has been done already is correct...


        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, false);
        mainPane.add(splitPane, BorderLayout.CENTER);

        treeTabPane = new JTabbedPane();
        treeTabPane.setMinimumSize(new Dimension(100, 100));

        if (isLinux())
            treeTabPane.setPreferredSize(new Dimension(265, 100));  	//TE: bug 2538.
        else
            treeTabPane.setPreferredSize(new Dimension(240, 100));   //TE: was 220x100 but increased size to fit icons.

        /*
         *    Initialise the work area scroll panes.  Our user defined
         *    classes will be added to these, and will become magically
         *    scrollable.
         */

        explorePanel = new JScrollPane(mrTree);
        resultsPanel = new JScrollPane(searchTree);
        schemaPanel = new JScrollPane(schemaTree);

        explorePanel.getVerticalScrollBar().setUnitIncrement(16); // ScrollPane's aren't respecting scrollable tree component's getScrollableUnitIncrement() methods; who knows why.
        resultsPanel.getVerticalScrollBar().setUnitIncrement(16);
        schemaPanel.getVerticalScrollBar().setUnitIncrement(16);

        splitPane.add(treeTabPane, JSplitPane.LEFT, 0);

        if (JXplorer.getProperty("gui.viewPanel", "true").equals("true"))
        {
            userViewPanel = new JPanel(new BorderLayout());
            userViewPanel.add(mainViewer, BorderLayout.CENTER);
            splitPane.add(userViewPanel, JSplitPane.RIGHT, 1);
        }

        if (mrTree != null) treeTabPane.addTab(mrTree.getName(), new ImageIcon(Theme.getInstance().getDirImages() + "explore.gif"), explorePanel, "Displays the directory tree, and allows the user to graphically browse the directory.");     //TE: sets the tabs up with name, icon, component and tool tip.
        if (searchTree != null) treeTabPane.addTab(searchTree.getName(), new ImageIcon(Theme.getInstance().getDirImages() + "find.gif"), resultsPanel, "Displays the search results, and allows the user to graphically browse these results.");
        if (schemaTree != null) treeTabPane.addTab(schemaTree.getName(), new ImageIcon(Theme.getInstance().getDirIcons() + "schema.gif"), schemaPanel, "Displays the directory schema, and allows the user to graphically browse the schema.");


        // nb. Don't add Tab for Admin, this only appears if the user
        // successfully establishes at least one admin connection...

        /**
         *	This change listener is intended to listen for tab changes.
         *	It makes sure the entry is updated in the editor pane so that
         *	when changing between for example schema and explore, the last
         *	schema data is not displayed...instead the entry that is selected
         *	in the explore tab is displayed.  (Bug 2243).
         */

        treeTabPane.addChangeListener(new ChangeListener()
        {
            public void stateChanged(ChangeEvent e)
            {
                Component treePane = treeTabPane.getSelectedComponent();
                ButtonRegister br = JXplorer.getButtonRegister();
                if (treePane == explorePanel)			    // Explore.
                {
                    setStatus(CBIntText.get("Connected To ''{0}''", new String[]{url}));
                    if (br != null && isConnected())  //todo and only if connected!
                        br.setCommonState(true);            //TE: enable buttons.
                    mrTree.refreshEditorPane();
                }
                else if (treePane == resultsPanel) 		    // Search.
                {
                    setStatus("Number of search results: " + String.valueOf(searchTree.getNumOfResults()));
                    searchTree.refreshEditorPane();
                }
                else if (treePane == schemaPanel)           // Schema.
                {
                    setStatus(CBIntText.get("Connected To ''{0}''", new String[]{url}));
                    if (br != null)                          //TE: disable buttons.
                        br.setCommonState(false);
                    schemaTree.refreshEditorPane();
                }
            }
        });





/* CB removed - use components (above) instead of indices for clarity...

                int index = treeTabPane.getSelectedIndex();

				switch (index)
				{
					case 0:			//TE: Explore.
					{
						if (mrTree != null)
							mrTree.refreshEditorPane();
						break;
					}
					case 1: 		//TE: Search.
					{
						if (searchTree != null)
							searchTree.refreshEditorPane();
						break;
					}
					case 2: 		//TE: Schema.
					{
						if (schemaTree != null)
							schemaTree.refreshEditorPane();
						break;
					}
				}
*/
    }

    /**
     * A vague flag that is set to true if the user hits the connect button,
     * false if user hits disconnect button.  This is for changing the state
     * of the buttons when flicking between tabs.
     *
     * @return value of connected.
     */
    public boolean isConnected()
    {
        return connected;
    }

    /**
     * A vague flag that is set to true if the user hits the connect button,
     * false if user hits disconnect button.  This is for changing the state
     * of the buttons when flicking between tabs.
     *
     * @param connected state to set connected to.
     */
    public void setConnected(boolean connected)
    {
        this.connected = connected;
    }

    /**
     * Returns the tree that is currently being directly
     * displayed to the user.
     */

    public SmartTree getActiveTree()
    {
        int paneNumber = treeTabPane.getSelectedIndex();

        if (paneNumber == treeTabPane.indexOfTab(CBIntText.get("Explore")))
            return mrTree;
        else if (paneNumber == treeTabPane.indexOfTab(CBIntText.get("Results")))
            return searchTree;
        else if (paneNumber == treeTabPane.indexOfTab(CBIntText.get("Schema")))
            return schemaTree;

        // should have returned by now... this line should never be reached!
        log.warning("ERROR: Unable to establish active tree - panel = " + paneNumber);
        return null;
    }

    /**
     * Make minor additions; the top-right window icon and the
     * title bar text.
     */

    protected void setupFrills()
    {
        //this.setIconImage(new ImageIcon(Theme.getInstance().getDirImages() + "ODlogo.gif").getImage());
        //this.setIconImage(getImageIcon(Theme.getInstance().getDirImages() + "ODlogo.gif").getImage());
        this.setTitle("JXplorer");
        this.setIconImage(getImageIcon("JX32.png").getImage());

        /* java 6 specific code - sigh - not yet
        ArrayList<Image> icons = new ArrayList<Image>();
        icons.add( getImageIcon( "JX16.png" ).getImage() );
        icons.add( getImageIcon( "JX32.png" ).getImage() );
        icons.add( getImageIcon( "JX48.png" ).getImage() );
        icons.add( getImageIcon( "JX128.png" ).getImage() );
        this.setIconImages( icons );
        */
    }

    /**
     * JXplorer utility ftn: load an image from the standard JX images directory.
     *
     * @param name the file name of the image file within the images directory
     * @return the loaded image.
     */

    public static ImageIcon getImageIcon(String name)
    {
        ImageIcon newIcon = new ImageIcon(Theme.getInstance().getDirImages() + name);
        return newIcon;
    }

    /**
     * Initialise the JavaHelp system, pointing it at the right help files.
     */

    protected void setupHelp()
    {
        helpSystem = new CBHelpSystem("JXplorerHelp.hs"); // use default 'JXplorerHelp.hs' help set.
    }

    /**
     * This returns the help system used by JX.  Useful to get
     * if you need to append some more help stuff.
     *
     * @return the current JX HelpSystem.
     */

    public CBHelpSystem getHelpSystem()
    {
        return helpSystem;
    }

    /**
     * Closes the application down
     */

    public void shutdown()
    {
        shutdown(null);
    }

    /**
     * Closes the application down, optionally printing out a message
     *
     * @param msg optional message to be printed out on closing.
     */

    public void shutdown(String msg)
    {
        setProperty("width", String.valueOf(((int) getSize().getWidth())));
        setProperty("height", String.valueOf(((int) getSize().getHeight())));

        setProperty("xpos", String.valueOf(getX()));
        setProperty("ypos", String.valueOf(getY()));

        setProperty("last.search.filter", "default");	//TE: sets the last filter property to 'default' (we don't really need to remember the filter after JX exists).

        writePropertyFile();

        if (msg != null)
            log.severe("shutting down\n" + msg);
        else
            log.warning("shutting down");

        System.exit(0);
    }

    public static void writePropertyFile()
    {
        CBUtility.writePropertyFile(propertyFile, myProperties, new String("# The property file location defaults to where JXplorer is installed\n" +
                "# - this can be over-ridden with the system property 'jxplorer.config'\n" +
                "#   with a config directory location, or set to user home using the\n" +
                "#   flag 'user.home' (e.g. -Djxplorer.config='user.home' on the command line).\n"));
    }

    public String toString()
    {
        return "JXplorer version " + version;
    }


    /**
     * Before a new connection is made, the old display trees should be cleared.
     */
    public void preConnectionSetup()
    {
        if (mrTree == null) return;
        mrTree.clearTree();
        mrTree.setRoot(SmartTree.NODATA);

        treeTabPane.setSelectedIndex(0);

        if (searchTree == null) return;
        searchTree.clearTree();
        searchTree.setRoot(SmartTree.NODATA);

        if (schemaTree == null) return;
        schemaTree.clearTree();
        schemaTree.setRoot(SmartTree.NODATA);

        // TODO: maybe restart jndibroker thread somehow?
    }

    /**
     * Perform necessary setup after a connection has been established.
     * This will link up the trees with their respective data
     * brokers.<p>
     * <p/>
     * This does quite a bit of work, including trying to find the default
     * base naming context(s), and registering schema.
     */

    public boolean postConnectionSetup(JNDIBroker.DataConnectionQuery request)
    {
        searchTree.clearTree();
        if (workOffline == true)
        {
            workOffline = false;
            offlineBroker.clear();
            //mrTree.registerDataSource(jxplorer.jndiBroker, new DN(SmartTree.NODATA));
        }

        String baseDN = request.conData.baseDN;
        DN base = new DN(baseDN);
        DN[] namingContexts = null;

        int ldapV = request.conData.version;

        try
        {
            if (base == null || base.size() == 0 || jndiBroker.getDirOp().exists(base) == false)
            {
                if (ldapV == 2)
                {
                    if (jndiBroker.getDirOp().exists(base) == false) // bail out if we can't find the base DN for ldap v2
                    {
                        CBUtility.error("Error opening ldap v2 connection - bad base DN '" + ((base == null) ? "*null*" : base.toString()) + "' ");
                        disconnect();
                        return false;
                    }
                }
                else // for ldap v3, try to find a valid base.
                {
                    if (base != null && base.size() > 0)
                        log.warning("The Base DN '" + base + "' cannot be found.");

                    base = null;  // set base to a known state.

                    namingContexts = jndiBroker.readFallbackRoot();  // may return null, but that's o.k.

                    if (baseDN.trim().length() > 0)
                    {
// Change from user error message to log message CBErrorWin errWin = new CBErrorWin(this, CBIntText.get("The DN you are trying to access cannot be found or does not exist.  The fall back DN is ") + namingContexts[0].toString(), "DN Not Found"); 	//TE: user info.
                        if (namingContexts != null && namingContexts[0] != null)
                            log.warning("Cannot find the user-specified Base DN - Using the fall back DN '" + namingContexts[0].toString() + "'");
                        else
                            log.warning("WARNING: Cannot find the user-specified Base DN, and cannot read alternative from directory.  Leaving unset for the present.");
                    }

                    if (namingContexts != null && namingContexts.length == 1) // if we only have one context...
                        base = namingContexts[0];                             // ... make it our base
                }
            }

            mrTree.clearTree();
            mrTree.registerDataSource(jndiBroker);
        }
        catch (Exception ex)	// wierd things can go wrong here; especially if ldap v2
        {
            if (ldapV != 2)
            {
                CBUtility.error("Possible errors occurred while opening connection.", ex);  // if not ldap v2, try to carry on anyway
            }
            else  // if ldap v2, just bail out.
            {
                CBUtility.error("Error opening ldap v2 connection (possibly bad base DN?) ", ex);
                disconnect();
                return false;
            }
        }

        //
        //    Set up the initial state of the tree, either with the base DN given, or with
        //    a set of Naming Contexts read from 'jndiBroker.readFallbackRoot()', or by
        //    trying to expand a blank DN (i.e. by doing a list on "").
        //

        if (base != null)    // We've got a single base DN - use it to set the tree root...
        {
            mrTree.setRoot(base);

            if (base.size() == 0)
            {
                mrTree.expandRoot();
                mrTree.getRootNode().setAlwaysRefresh(true);
            }
            else
            {
                mrTree.expandDN(base);
                makeDNAutoRefreshing(base);
            }

        }
        else if (namingContexts != null)  // We've got multiple naming contexts - add them all.
        {
            mrTree.setRoot("");
            for (int i = 0; i < namingContexts.length; i++)         // for each context
            {
                DN namingContext = namingContexts[i];           // get the 'base' DN
                SmartNode node = mrTree.addNode(namingContext); // add that to the tree as a node

                // *Amazing* but harmless Hack for Mr John Q. Birrell
                if (node.getChildCount() == 0)                  // if nothing is there already (might be if server mis-configured)
                    node.add(new SmartNode());                  // make that node expandable
            }

            for (int i = 0; i < namingContexts.length; i++)         // for each context
            {
                mrTree.expandDN(namingContexts[i]);                 // and make the node visible.
                makeDNAutoRefreshing(namingContexts[i]);

            }
        }
        else    // no information; try to expand an empty dn and see what we get!
        {
            mrTree.expandRoot();
            mrTree.getRootNode().setAlwaysRefresh(true);
        }

        searchTree.clearTree();
        searchBroker.registerDirectoryConnection(jndiBroker);
        searchTree.registerDataSource(searchBroker);
        searchTree.setRoot(new DN(SmartTree.NODATA));

        schemaTree.clearTree();

        if (Integer.toString(ldapV) != null && ldapV > 2)
        {
            schemaBroker.registerDirectoryConnection(jndiBroker);
            schemaTree.registerDataSource(schemaBroker);
            schemaTree.setRoot(new DN("cn=schema"));

            DXAttribute.setDefaultSchema(jndiBroker.getSchemaOps());
            DXAttributes.setDefaultSchema(jndiBroker.getSchemaOps());
        }
        else
        {
            DXAttribute.setDefaultSchema(null);
            DXAttributes.setDefaultSchema(null);
        }

        if (base != null)
        {
            jndiBroker.getEntry(base);   // read first entry.
            JXplorer.setDefaultProperty("baseDN", base.toString());

            url = request.conData.url;
            setStatus(CBIntText.get("Connected To ''{0}''", new String[]{url}));
        }

        getButtonRegister().setConnectedState();
        mainMenu.setConnected(true);
        setConnected(true);

        return true;
    }

    protected void makeDNAutoRefreshing(DN dn)
    {
        try
        {
            TreePath path = ((SmartModel) mrTree.getModel()).getPathForDN(dn);
            if (path == null) throw new Exception("null path returned");
            Object[] nodes = path.getPath();
            for (int j = 0; j < nodes.length - 1; j++)
            {
                ((SmartNode) nodes[j]).setAlwaysRefresh(true);  // XXX hack for x500/ldap server compatibility - force refreshing if certain magic nodes are expanded.
            }

        }
        catch (Exception e)
        {
            log.info("INFO: messed up setting auto-expanding nodes for context '" + dn + "'");
        }
    }


    /**
     * Disables/enables the menu and button bar items to reflect a disconnected state.
     * Clears the Explore, Results and Schema trees and also sets the status message
     * to disconnected.
     */

    public void disconnect()
    {
        jndiBroker.disconnect();
        mrTree.clearTree();
        schemaTree.clearTree();
        searchTree.clearTree();
        searchTree.setNumOfResults(0);

        getButtonRegister().setDisconnectState();

        mainMenu.setDisconnected();
        setConnected(false);

        setStatus(CBIntText.get("Disconnected"));
    }


    /**
     * Disables/enables the menu and button bar items to reflect a disconnected state.
     * Also sets the status message to disconnected.
     */

    public void setDisconnectView()
    {
        mainMenu.setDisconnected();
        getButtonRegister().setDisconnectState();

        setStatus(CBIntText.get("Disconnected"));
    }



    //
    //    Make JXplorer into an event generating object, that can
    //    register ActionListeners and trigger actionEvents.
    //

    /**
     * Add the specified JXplorer listener to receive JXplorer events from
     * JXplorer. Currently,the only JXplorer event occurs when a user selects a DN.
     * If l is null, no exception is thrown and no JXplorer is performed.
     *
     * @param l the JXplorer listener
     * @see #removeJXplorerListener
     */

    public synchronized void addJXplorerListener(JXplorerListener l)
    {
        if (l != null)
            eventListeners.add(JXplorerListener.class, l);
    }

    /**
     * Remove the specified JXplorer listener so that it no longer
     * receives JXplorer events from this button. JXplorer events occur
     * when a user presses or releases the mouse over this button.
     * If l is null, no exception is thrown and no JXplorer is performed.
     *
     * @param l the JXplorer listener
     * @see #addJXplorerListener
     */
    public synchronized void removeJXplorerListener(JXplorerListener l)
    {
        if (l != null)
            eventListeners.remove(JXplorerListener.class, l);
    }


    /**
     * Creates JXplorer events
     * by dispatching them to any registered
     * <code>JXplorerListener</code> objects.
     * (Implements the JXplorerEventGenerator interface)
     * <p/>
     *
     * @param e the JXplorer event.
     * @see com.ca.directory.jxplorer.event.JXplorerEventGenerator
     * @see com.ca.directory.jxplorer.event.JXplorerListener
     * @see com.ca.directory.jxplorer.JXplorer#addJXplorerListener
     */
    public void fireJXplorerEvent(JXplorerEvent e)
    {
        Object[] list = eventListeners.getListenerList();
        for (int index = list.length - 2; index >= 0; index -= 2)
        {
            if (list[index] == JXplorerListener.class)
            {
                ((JXplorerListener) list[index + 1]).JXplorerDNSelected(e);
            }
        }
    }


    /**
     * Displays a splash screen with a thin black border in the center of the screen.
     * Splash screen auto-sizes to be very slightly larger than the templates/JXsplash.png image.
     */

    public void showSplashScreen(JWindow splash)
    {
        ImageIcon splashIcon = new ImageIcon(Theme.getInstance().getDirTemplates() + "JXsplash.png");
        int width = splashIcon.getIconWidth();
        int height = splashIcon.getIconHeight();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        splash.setBounds((screen.width - width) / 2, (screen.height - height) / 2, width, height);
        JLabel pic = new JLabel(splashIcon);
        JPanel content = (JPanel) splash.getContentPane();
        content.add(pic);
        splash.setVisible(true);
    }


    /**
     * A variety of plugin possibilities exist in JXplorer.  To make distribution easier, it
     * is possible to package up class files, as well as image/text/etc. files, and use a
     * custom resource loader to access these zipped/jarred resources.  This sets it all up,
     * as well as adding any custom security providers...
     */

    public void setupResourceFiles()
    {
        resourceLoader = new CBResourceLoader();
        classLoader = new CBClassLoader(resourceLoader);

        String pluginPath = JXplorer.getProperty("dir.plugins");
        String[] pluginFiles = CBUtility.readFilteredDirectory(pluginPath, new String[]{"zip", "jar"});
        if (pluginFiles == null)
        {
            log.warning("Unable to access plugins directory: '" + pluginPath + "'");
            return;
        }

        for (int i = 0; i < pluginFiles.length; i++)
            resourceLoader.addResource(new CBJarResource(pluginPath + pluginFiles[i]));

        setupSecurityProviders();

        setupGSSAPIConfig();

    }

    /**
     * This sets up the inital GSSAPI config file if it does not already exist, and sets the
     * login config system property.
     */

    protected void setupGSSAPIConfig()
    {
        try
        {
            String sep = System.getProperty("line.separator");

            // the default gssapi.conf file (provided by Vadim Tarassov).
            String defaultFileText = "com.ca.commons.jndi.JNDIOps {" + sep +
                    "  com.sun.security.auth.module.Krb5LoginModule required client=TRUE" + sep +
                    "  \t\t\t\t\t\t\t\t\t\t\t\t\t\tuseTicketCache=TRUE;" + sep +
                    "};";

            String configFile = CBUtility.getPropertyConfigPath("gssapi.conf");
            File gssapi_conf = new File(configFile);

            // if it doesn't exist, write the default file above - if it does exist,
            // use whatever we're given...
            if (gssapi_conf.exists() == false)
            {
                FileWriter confWriter = new FileWriter(gssapi_conf);
                confWriter.write(defaultFileText);
                confWriter.close();
            }


            System.setProperty("java.security.auth.login.config", gssapi_conf.getCanonicalPath().toString());
        }
        catch (IOException e)
        {
            log.warning("ERROR: Unable to initialise GSSAPI config file " + e);
        }
    }

    protected void setupSecurityProviders()
    {
        // load providers in reverse order, since they are always
        // inserted at the beginning.

        String providerName = getProperty("securityProvider2", null);
        if (providerName != null)
            addSecurityProvider(providerName);

        providerName = getProperty("securityProvider1", null);
        if (providerName != null)
            addSecurityProvider(providerName);

        providerName = getProperty("securityProvider0", null);
        if (providerName != null)
            addSecurityProvider(providerName);

        providerName = getProperty("securityProvider", null);
        if (providerName != null)
            addSecurityProvider(providerName);

        // check to see if the users tried to use more than the above
        // (unlikely, we hope).

        providerName = getProperty("securityProvider3", null);
        if (providerName != null)
        {
            CBUtility.error(CBIntText.get("Too many security providers in config file."));
            printSecurityProviders();
        }
        // print provider list for debugging.
        else if (debugLevel >= 2)
            printSecurityProviders();

        // while we're here, register our custom class loader
        // with JndiSocketFactory, so it can access any custom
        // plugin security providers loaded above.

        JndiSocketFactory.setClassLoader(classLoader);
    }

    /**
     * Allows extra security providers to be manually added.
     *
     * @param providerName the class name of the security provider to
     *                     add (e.g. something like 'sun.security.provider.Sun')
     */

    protected void addSecurityProvider(String providerName)
    {

//XXX is there a fast way of checking if we already have this provider
//XXX (yes - could check for it in the global list of providers - probably not worth it...)

        try
        {

            Class providerClass = classLoader.loadClass(providerName);
            Object providerObject = providerClass.newInstance();

            Security.insertProviderAt((Provider) providerObject, 1);

//            Security.insertProviderAt(new com.sun.net.ssl.internal.ssl.Provider(), 1);
        }
        catch (Exception e)
        {
            System.err.println("\n*** unable to load new security provider: " + ((providerName == null) ? "null" : providerName));
            System.err.println(e);
        }

    }

    protected static void printSecurityProviders()
    {
        log.fine("\n***\n*** LIST OF CURRENT SECURITY PROVIDERS\n***");
        Provider[] current = Security.getProviders();
        {
            for (int i = 0; i < current.length; i++)
            {
                log.fine("provider: " + i + " = " + current[i].getName() + " " + current[i].getInfo());
                log.fine("   (" + current[i].getClass().toString() + ")\n");
            }
        }
        log.fine("\n***\n*** END LIST\n***\n");
    }

    /**
     * Test for solaris (usually to disable features that appear to be more than
     * usually broken on that platform - e.g. drag and drop).
     */

    public static boolean isSolaris()
    {
        String os = System.getProperty("os.name");
        if (os == null) return false;

        os = os.toLowerCase();
        if (os.indexOf("sun") > -1) return true;
        if (os.indexOf("solaris") > -1) return true;
        return false;
    }


    /**
     * Test for Linux (usually to disable features that appear to be more than
     * usually broken on that platform - e.g. JAVA L&F).
     *
     * @return true if Linux OS, false otherwise.
     */

    public static boolean isLinux()
    {
        String os = System.getProperty("os.name");

        if (os != null && os.toLowerCase().indexOf("linux") > -1)
            return true;

        return false;
    }

    /**
     * Test for Linux (usually to disable features that appear to be more than
     * usually broken on that platform - e.g. JAVA L&F).
     *
     * @return true if Linux OS, false otherwise.
     */

    public static boolean isMac()
    {
        String os = System.getProperty("mrj.version"); // mac specific call as per http://developer.apple.com/technotes/tn/tn2042.html
        return (os != null);
    }

    /**
     * Test for Linux (usually to disable features that appear to be more than
     * usually broken on that platform - e.g. JAVA L&F).
     *
     * @return true if Linux OS, false otherwise.
     */

    public static boolean isWindows()
    {
        String os = System.getProperty("os.name"); // mac specific call as per http://developer.apple.com/technotes/tn/tn2042.html

        if (os != null && os.toLowerCase().indexOf("windows") > -1)
            return true;

        return false;
    }

    /**
     * Returns the ButtonRegister object.
     *
     * @return the ButtonRegister object that is used by JXplorer to
     *         register all its buttons and menu items.
     * @see #buttonRegister
     */

    public static ButtonRegister getButtonRegister()
    {
        return buttonRegister;
    }
}

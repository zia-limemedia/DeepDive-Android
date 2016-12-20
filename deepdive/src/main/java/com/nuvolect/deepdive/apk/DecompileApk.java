package com.nuvolect.deepdive.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.googlecode.dex2jar.Method;
import com.googlecode.dex2jar.ir.IrMethod;
import com.googlecode.dex2jar.reader.DexFileReader;
import com.googlecode.dex2jar.v3.Dex2jar;
import com.googlecode.dex2jar.v3.DexExceptionHandler;
import com.jaredrummler.apkparser.ApkParser;
import com.jaredrummler.apkparser.model.CertificateMeta;
import com.nuvolect.deepdive.util.LogUtil;
import com.nuvolect.deepdive.util.OmniFile;
import com.nuvolect.deepdive.util.OmniHash;
import com.nuvolect.deepdive.util.OmniZip;
import com.nuvolect.deepdive.util.TimeUtil;
import com.nuvolect.deepdive.util.Util;
import com.nuvolect.deepdive.webserver.connector.VolUtil;

import org.apache.commons.io.FilenameUtils;
import org.benf.cfr.reader.state.ClassFileSourceImpl;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.util.getopt.GetOptParser;
import org.benf.cfr.reader.util.getopt.OptionsImpl;
import org.benf.cfr.reader.util.output.DumperFactoryImpl;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import javax.security.cert.CertificateException;

import jadx.api.JadxDecompiler;
import jadx.core.utils.exceptions.JadxException;

import static com.nuvolect.deepdive.util.OmniZip.unzipFile;

//import net.dongliu.apk.parser.ApkParser;
//import net.dongliu.apk.parser.bean.CertificateMeta;
//import net.dongliu.apk.parser.bean.DexClass;

/**
 * Utilities to work with APK files.
 * A top level folder is created at CConst.APP_FOLDER_NAME
 * Sub-folders are created for each app named with the package name
 * Sub-folders contain
 * 1. the APK file,
 * 2. the unpacked APK file and
 * 3. decompiled class files in subfolders src{cfr, jadx, fern}
 *
 * _status is for 0/1 apk and dex file exists status
 * _thread status has states {running, stopped, null}
 * running: compile process is running
 * stopped: comple process has stopped, folder exists
 * null: compile process is not running, folder does not exist
 */
public class DecompileApk {

    private static String m_topFolderUrl = "";
    private static String m_topFolderFullPath;
    private static String m_appFolderFullPath;
    private static String m_appFolderRelativePath;
    private static OmniFile m_appFolder;
    private static String m_appFolderUrl;
    private static String m_appApkFullPath;
    private static OmniFile m_dexFile;
    private static OmniFile m_optimizedDexFile;
    private static String m_dexFullPath;
    private static String m_optimizedDexFullPath;
    private static String m_jarFullPath;
    private static OmniFile m_jarFile;
    private static String m_srcCfrFolderPath;
    private static OmniFile m_srcCfrFolder;
    private static String m_srcJadxFolderPath;
    private static OmniFile m_srcJadxFolder;
    private static String m_srcFernFolderPath;
    private static OmniFile m_srcFernFolder;
    private static ProgressStream m_progressStream;

    //    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    //    STACK_SIZE = Integer.valueOf(prefs.getString("thread_stack_size", String.valueOf(20 * 1024 * 1024)));
    //    IGNORE_LIBS = prefs.getBoolean("ignore_libraries", true);
    private static int STACK_SIZE = 20 * 1024 * 1024;

    private static String DEEPDIVE_THREAD_GROUP = "DeepDive Thread Group";
    private static ThreadGroup m_threadGroup = new ThreadGroup(DEEPDIVE_THREAD_GROUP);
    private static Thread m_unpackApkThread = null;
    private static Thread m_optimizeDexThread = null;
    private static Thread m_dex2jarThread = null;
    private static Thread m_cfrThread = null;
    private static Thread m_jadxThread = null;
    private static Thread m_fernThread = null;
    private static String UNZIP_APK_THREAD = "Unpack APK java thread";
    private static String DEX2JAR_THREAD = "DEX to JAR java thread";
    private static String JADX_THREAD = "Jadx jar to java thread";
    private static String FERN_THREAD = "FernFlower jar to java thread";
    private static List<String> ignoredLibs = new ArrayList();
    private static String OPTIMIZED_CLASSES = "optimized_classes";
    private static String OPTIMIZED_CLASSES_EXCLUSION_FILENAME = "/optimized_classes_exclusion.txt";
    private static String[] m_dexFileNames = {};

    // Time when a process is started
    private static long m_unpack_apk_time = 0;
    private static long m_dex2jar_time = 0;
    private static long m_optimize_dex_time = 0;
    private static long m_cfr_time = 0;
    private static long m_jadx_time = 0;
    private static long m_fern_time = 0;

    public enum MY_THREAD { unpack_apk, dex2jar, cfr, jadx, fern_flower, optimize_dex, simple_unpack};

    /**
     * Create a working folder for the app then create a sub-folder based on the package name.
     * @param ctx
     * @return
     */
    public static JSONObject init(Context ctx, String package_name) {

        m_progressStream = new ProgressStream();

        List<String> tmp = new ArrayList<String>();
        tmp.add("classes");
        for( int i = 2; i <=64; i++)
            tmp.add("classes"+i);

        m_dexFileNames = new String[ tmp.size()];
        m_dexFileNames = tmp.toArray(m_dexFileNames);

        m_topFolderFullPath = Util.createAppPublicFolder(ctx);
        if (m_topFolderFullPath.isEmpty()) {
            Toast.makeText(ctx, "SDCARD folder creation error", Toast.LENGTH_LONG).show();
        } else {
            m_topFolderUrl = OmniHash.getHashedServerUrlFullPath(ctx,
                    VolUtil.sdcardVolumeId, m_topFolderFullPath);

            m_appFolderFullPath = m_topFolderFullPath + "/" + package_name;
            String rootPath = VolUtil.getRoot(VolUtil.sdcardVolumeId);
            m_appFolderRelativePath = m_appFolderFullPath.replace(rootPath, "/");
            m_appFolder = new OmniFile(VolUtil.sdcardVolumeId, m_appFolderFullPath);
            if (!m_appFolder.exists()) {
                m_appFolder.mkdir();
            }
            m_appFolderUrl = OmniHash.getHashedServerUrlFullPath(ctx,
                    VolUtil.sdcardVolumeId, m_appFolderFullPath);

        }
        return getStatus(ctx, package_name);
    }

    /**
     * Update member variables paths, and files. Return thread status and URLs.
     * @param ctx
     * @param package_name
     * @return
     */
    public static JSONObject getStatus(Context ctx, String package_name) {

        boolean apkFileExists = false;
        boolean dexFileExists = false;
        boolean optimizedDexExists = false;
        boolean dex2jarFileExists = false;
        boolean cfrFolderExists = false;
        boolean jadxFolderExists = false;
        boolean fernFolderExists = false;
        JSONObject wrapper = new JSONObject();

        /**
         * Update status on tasks performed
         */
        m_appApkFullPath = m_appFolderFullPath+"/"+package_name+".apk";
        OmniFile apkFile = new OmniFile(VolUtil.sdcardVolumeId, m_appApkFullPath);
        apkFileExists = apkFile.exists();

        m_dexFullPath = m_appFolderFullPath+"/classes.dex";
        m_optimizedDexFullPath = m_appFolderFullPath+"/optimized_classes.dex";
        m_dexFile = new OmniFile(VolUtil.sdcardVolumeId, m_dexFullPath);
        m_optimizedDexFile = new OmniFile(VolUtil.sdcardVolumeId, m_optimizedDexFullPath);

        dexFileExists = false;
        for(String fileName : m_dexFileNames){

            File dexFile = new File(m_appFolderFullPath + "/" + fileName + ".dex");
            if( dexFile.exists() && dexFile.isFile()){
                dexFileExists = true;
                break;
            }
        }
        optimizedDexExists = new File(m_appFolderFullPath+ "/"+ OPTIMIZED_CLASSES+".dex").exists();

        dex2jarFileExists = false;
        for(String fileName : m_dexFileNames){

            File jarFile = new File(m_appFolderFullPath + "/" + fileName + ".jar");
            if( jarFile.exists() && jarFile.isFile()){
                dex2jarFileExists = true;
                break;
            }
        }

        m_srcCfrFolderPath = m_appFolderFullPath+"/srcCfr";
        m_srcCfrFolder = new OmniFile(VolUtil.sdcardVolumeId, m_srcCfrFolderPath);
        cfrFolderExists = m_srcCfrFolder.exists();

        m_srcJadxFolderPath = m_appFolderFullPath+"/srcJadx";
        m_srcJadxFolder = new OmniFile(VolUtil.sdcardVolumeId, m_srcJadxFolderPath);
        jadxFolderExists = m_srcJadxFolder.exists();

        m_srcFernFolderPath = m_appFolderFullPath+"/srcFern";
        m_srcFernFolder = new OmniFile(VolUtil.sdcardVolumeId, m_srcFernFolderPath);
        fernFolderExists = m_srcFernFolder.exists();

        try {

            wrapper.put("copy_apk_status", apkFileExists ?1:0);
            wrapper.put("copy_apk_url", m_appFolderUrl);
            wrapper.put("unpack_apk_url", m_appFolderUrl);
            wrapper.put("dex2jar_url", m_appFolderUrl);
            wrapper.put("app_folder_path", m_appFolderRelativePath);

            if(cfrFolderExists){
                String url = OmniHash.getHashedServerUrlFullPath(ctx,
                        VolUtil.sdcardVolumeId, m_srcCfrFolderPath);
                wrapper.put("cfr_url", url);
            }
            else
                wrapper.put("cfr_url", m_appFolderUrl);

            if(jadxFolderExists){
                String url = OmniHash.getHashedServerUrlFullPath(ctx,
                        VolUtil.sdcardVolumeId, m_srcJadxFolderPath);
                wrapper.put("jadx_url", url);
            }
            else
                wrapper.put("jadx_url", m_appFolderUrl);

            if(fernFolderExists){
                String url = OmniHash.getHashedServerUrlFullPath(ctx,
                        VolUtil.sdcardVolumeId, m_srcFernFolderPath);
                wrapper.put("fern_url", url);
            }
            else
                wrapper.put("fern_url", m_appFolderUrl);

            wrapper.put("optimize_dex_status", optimizedDexExists?1:0);

            wrapper.put("unpack_apk_thread",  getThreadStatus( dexFileExists, m_unpackApkThread));
            wrapper.put("dex2jar_thread",     getThreadStatus( dex2jarFileExists, m_dex2jarThread));
            wrapper.put("optimize_dex_thread",getThreadStatus( optimizedDexExists, m_optimizeDexThread));
            wrapper.put("cfr_thread",         getThreadStatus( cfrFolderExists, m_cfrThread));
            wrapper.put("jadx_thread",        getThreadStatus( jadxFolderExists, m_jadxThread));
            wrapper.put("fern_thread",        getThreadStatus( fernFolderExists, m_fernThread));

            wrapper.put("unpack_apk_time",    getThreadTime( m_unpack_apk_time ));
            wrapper.put("dex2jar_time",       getThreadTime( m_dex2jar_time ));
            wrapper.put("optimize_dex_time",  getThreadTime( m_optimize_dex_time ));
            wrapper.put("cfr_time",           getThreadTime( m_cfr_time ));
            wrapper.put("jadx_time",          getThreadTime( m_jadx_time ));
            wrapper.put("fern_time",          getThreadTime( m_fern_time ));

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return wrapper;
    }

    /**
     * Return the status of a compile process. The status can be one of three states:
     * running: compile process is running
     * stopped: comple process has stopped, folder exists
     * empty: compile process is not running, folder does not exist
     *
     * @param folderExists
     * @param aThread
     * @return
     */
    private static String getThreadStatus(boolean folderExists, Thread aThread) {

        if( aThread != null && aThread.isAlive())
            return "running";

        if( folderExists)
            return "stopped";

        return "empty";
    }

    private static String getThreadTime(long startTime){

        if( startTime == 0)
            return "";

        return TimeUtil.deltaTimeHrMinSec( startTime);
    }


    /**
     * Copy the specific APK to working folder.
     * Return a link to the parent folder.
     * @param ctx
     * @return
     */
    public static JSONObject copyApk(Context ctx, String package_name) {

        JSONObject wrapper = new JSONObject();

        try {
            wrapper.put("copy_apk_status", 0);// 0==Start with failed file copy
            m_progressStream.putStream("Copy APK starting");

            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo applicationInfo = pm.getApplicationInfo( package_name, PackageManager.GET_META_DATA);

            java.io.File inputFile = new File( applicationInfo.publicSourceDir);
            InputStream inputStream = new FileInputStream( inputFile );

            if( m_appFolderFullPath == null)
                init( ctx, package_name);

            File outputFile = new File(m_appApkFullPath);
            OutputStream outputStream = new FileOutputStream( outputFile );
            int bytes_copied = Util.copyFile( inputStream, outputStream);
            String formatted_count = NumberFormat.getNumberInstance(Locale.US).format(bytes_copied);

            m_progressStream.putStream("Copy APK complete. Copied: "+formatted_count);

            wrapper.put("copy_apk_status", 1); // Change to success if we get here
            wrapper.put("copy_apk_url", m_appFolderUrl);

        } catch (PackageManager.NameNotFoundException | JSONException | IOException e) {
            LogUtil.logException(LogUtil.LogType.DECOMPILE, e);
            m_progressStream.putStream(e.toString());
            m_progressStream.putStream("Copy APK failed");
        }

        return wrapper;
    }

    public static JSONObject unpackApk(Context ctx, final String package_name) {

        if( m_appFolderFullPath == null)
            init( ctx, package_name);

        final Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {

                LogUtil.log(LogUtil.LogType.DECOMPILE, "Uncaught exception: "+e.toString());
                m_progressStream.putStream("Uncaught exception: "+t.getName());
                m_progressStream.putStream("Uncaught exception: "+e.toString());
            }
        };

        m_unpack_apk_time = System.currentTimeMillis();  // Save start time for tracking

        m_unpackApkThread = new Thread( m_threadGroup, new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {

                    m_progressStream.putStream("Unpack APK starting");
                    OmniFile appZip = new OmniFile(VolUtil.sdcardVolumeId, m_appApkFullPath);
                    if( appZip.exists() && appZip.isFile()){

                        // Extract all files except for XML, to be extracted later
                        success = ApkZipUtil.unzipAllExceptXML(appZip, m_appFolder, m_progressStream);

//                        ApkParser apkParser = new ApkParser( appZip.getStdFile());
                        ApkParser apkParser = ApkParser.create( appZip.getStdFile());

                        // Get a list of all files in the APK and iterate and extract by type
                        List<String> paths = OmniZip.getFilesList( appZip);
                        for( String path : paths){

                            File file = new File( m_appFolderFullPath+"/"+path);
                            File parent = file.getParentFile();
                            parent.mkdirs();
                            String extension = FilenameUtils.getExtension( path);

                            if( extension.contentEquals("xml")){

                                String xml = apkParser.transBinaryXml(path);
                                Util.writeFile( file, xml);
                                m_progressStream.putStream( "Translated: "+path);
                            }
                        }
                        // Write over manifest with unencoded version
                        String manifestXml = apkParser.getManifestXml();
                        File manifestFile = new File( m_appFolderFullPath+"/AndroidManifest.xml");
                        Util.writeFile(manifestFile, manifestXml);
                        m_progressStream.putStream("Translated and parsed: "+"AndroidManifest.xml");

                        // Uses original author CaoQianLi's apk-parser
                        // compile 'net.dongliu:apk-parser:2.1.7'
//                        for( CertificateMeta cm : apkParser.getCertificateMetaList()){
//
//                            m_progressStream.putStream("Certficate base64 MD5: "+cm.getCertBase64Md5());
//                            m_progressStream.putStream("Certficate MD5: "+cm.getCertMd5());
//                            m_progressStream.putStream("Sign algorithm OID: "+cm.getSignAlgorithmOID());
//                            m_progressStream.putStream("Sign algorithm: "+cm.getSignAlgorithm());
//                        }

                        CertificateMeta cm = null;
                        try {
                            cm = apkParser.getCertificateMeta();
                            m_progressStream.putStream("Certficate base64 MD5: "+cm.certBase64Md5);
                            m_progressStream.putStream("Certficate MD5: "+cm.certMd5);
                            m_progressStream.putStream("Sign algorithm OID: "+cm.signAlgorithmOID);
                            m_progressStream.putStream("Sign algorithm: "+cm.signAlgorithm);

                        } catch (IOException e1) {
                            e1.printStackTrace();
                        } catch (CertificateException e1) {
                            e1.printStackTrace();
                        }


                        m_progressStream.putStream("ApkSignStatus: "+ apkParser.verifyApk());

                        /**
                         * Create a file for the user to include classes to omit in the optimize DEX task.
                         */
                        File optimizedDex = new File(m_appFolderFullPath+ OPTIMIZED_CLASSES_EXCLUSION_FILENAME);
                        if( ! optimizedDex.exists()){

                            Util.writeFile( optimizedDex, "");
                            m_progressStream.putStream("File created: "+OPTIMIZED_CLASSES_EXCLUSION_FILENAME);
                        }
                    }else{

                        m_progressStream.putStream("APK not found. Select Copy APK.");
                    }

                } catch (Exception | StackOverflowError e) {
                    m_progressStream.putStream(e.toString());
                }
                String time = TimeUtil.deltaTimeHrMinSec(m_unpack_apk_time);
                m_unpack_apk_time = 0;
                if( success)
                    m_progressStream.putStream("Unpack APK complete: "+time);
                else
                    m_progressStream.putStream("Unpack APK failed: "+time);
            }
        }, UNZIP_APK_THREAD, STACK_SIZE);

        m_unpackApkThread.setPriority(Thread.MAX_PRIORITY);
        m_unpackApkThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        m_unpackApkThread.start();

        final JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("unpack_apk_thread", getThreadStatus( true, m_unpackApkThread));
            wrapper.put("unpack_apk_url", m_appFolderUrl);

        } catch (JSONException e) {
            LogUtil.logException(LogUtil.LogType.DECOMPILE, e);
        }

        return wrapper;
    }
    /**
     * Build a new DEX file excluding classes in the OPTIMIZED_CLASS_EXCLUSION file
     * @return
     */
    public static JSONObject optimizeDex() {

        final Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {

                LogUtil.log(LogUtil.LogType.DECOMPILE, "Uncaught exception: "+e.toString());
                m_progressStream.putStream("Uncaught exception: "+t.getName());
                m_progressStream.putStream("Uncaught exception: "+e.toString());
            }
        };

        m_optimize_dex_time = System.currentTimeMillis();  // Save start time for tracking

        m_optimizeDexThread = new Thread(m_threadGroup, new Runnable() {
            @Override
            public void run() {

                List<ClassDef> classes = new ArrayList<>();
                m_progressStream.putStream("Optimizing classes, reference: "+ OPTIMIZED_CLASSES_EXCLUSION_FILENAME);

                Scanner s = null;
                try {
                    s = new Scanner( new File(m_appFolderFullPath+ OPTIMIZED_CLASSES_EXCLUSION_FILENAME));
                    while (s.hasNext()){
                        String excludeClass = s.next();
                        ignoredLibs.add(excludeClass);
                        m_progressStream.putStream("Exclude class: "+excludeClass);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if( s != null)
                    s.close();

                /**
                 * Current solution reads dex from the APK.  It appears to only read the first classes.dex.
                 * The problem is the classes{n}.dex files are ignored and not all classes can be decompiled.
                 */
//                File dFile = new File( m_appFolderFullPath +"/classes.dex" );
//                try {
//                    DexFile df = new DexFile( dFile);
//                    Enumeration<String> e = df.entries();
//
//                    LogUtil.log( e.toString());
//
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }

                org.jf.dexlib2.iface.DexFile dexFile = null;
                try {
                    dexFile = DexFileFactory.loadDexFile( m_appApkFullPath, 19);
                } catch( Exception e) {
                    m_progressStream.putStream("The app DEX file cannot be decompiled.");
                }

                Set<? extends ClassDef> classSet = dexFile.getClasses();

                for (org.jf.dexlib2.iface.ClassDef classDef : classSet) {
                    if (!isIgnored(classDef.getType())) {
                        final String currentClass = classDef.getType();
                        m_progressStream.putStream("Optimizing_class: " + currentClass);
                        classes.add(classDef);
                    }
                }

                m_progressStream.putStream("Merging classes #"+classSet.size());
                dexFile = new ImmutableDexFile( classes);

                try {
                    m_progressStream.putStream("Writing optimized_classes.dex");
                    DexFileFactory.writeDexFile(m_appFolderFullPath+"/optimized_classes.dex", dexFile);
                } catch( Exception e) {
                    m_progressStream.putStream("The app DEX file cannot be decompiled.");
                }
                m_progressStream.putStream("Optimize DEX complete: "
                        +TimeUtil.deltaTimeHrMinSec(m_optimize_dex_time));
                m_optimize_dex_time = 0;
            }
        }, UNZIP_APK_THREAD, STACK_SIZE);

        m_optimizeDexThread.setPriority(Thread.MAX_PRIORITY);
        m_optimizeDexThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        m_optimizeDexThread.start();

        return new JSONObject();
    }

    private static boolean isIgnored(String className) {
        for (String ignoredClass : ignoredLibs) {
            if (className.startsWith(ignoredClass)) {
                return true;
            }
        }
        return false;
    }

    public static JSONObject dex2jar(Context ctx, final String package_name) {

        // DEX 2 JAR CONFIGS
        final boolean reuseReg = false; // reuse register while generate java .class file
        final boolean topologicalSort1 = false; // same with --topological-sort/-ts
        final boolean topologicalSort = false; // sort block by topological, that will generate more readable code
        final boolean verbose = true; // show progress
        final boolean debugInfo = false; // translate debug info
        final boolean printIR = false; // print ir to System.out
        final boolean optimizeSynchronized = true; // Optimise-synchronised

        if( m_appFolderFullPath == null)
            init( ctx, package_name);

        final Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {

                LogUtil.log(LogUtil.LogType.DECOMPILE, "Uncaught exception: "+e.toString());
                m_progressStream.putStream("Uncaught exception: "+t.getName());
                m_progressStream.putStream("Uncaught exception: "+e.toString());
            }
        };

        m_dex2jar_time = System.currentTimeMillis();  // Save start time for tracking

        m_dex2jarThread = new Thread( m_threadGroup, new Runnable() {
            @Override
            public void run() {

                boolean success = false;
                File dexFile = null;
                File jarFile = null;

                for( String fileName : m_dexFileNames){

                    dexFile = new File(m_appFolderFullPath + "/" + fileName+".dex");

                    if( dexFile.exists() && dexFile.isFile()){

                        String size = NumberFormat.getNumberInstance(Locale.US).format(dexFile.length());
                        m_progressStream.putStream("DEX to JAR starting: "+dexFile.getName()+", "+size);

                        DexExceptionHandlerMod dexExceptionHandlerMod = new DexExceptionHandlerMod();
                        jarFile = new File(m_appFolderFullPath + "/" + fileName + ".jar");

                        if( jarFile.exists())
                            jarFile.delete();

                        try {
                            DexFileReader reader = new DexFileReader(dexFile);
                            Dex2jar dex2jar = Dex2jar
                                    .from(reader)
                                    .reUseReg(reuseReg)
                                    .topoLogicalSort(topologicalSort || topologicalSort1)
                                    .skipDebug(!debugInfo)
                                    .optimizeSynchronized(optimizeSynchronized)
                                    .printIR(printIR)
                                    .verbose(verbose);
                            dex2jar.setExceptionHandler(dexExceptionHandlerMod);
                            dex2jar.to(jarFile);
                            success = true;
                        } catch ( Exception e) {
                            String ex = LogUtil.logException(LogUtil.LogType.DECOMPILE, e);
                            m_progressStream.putStream(ex);
                            success = false;
                        }
                        if( success ){

                            size = NumberFormat.getNumberInstance(Locale.US).format(jarFile.length());
                            m_progressStream.putStream("DEX to JAR succeeded: "+jarFile.getName()+", "+size);
                        }
                        else
                            m_progressStream.putStream("Exception thrown, file cannot be decompiled: "+dexFile.getPath());
                    }
                }
                if( jarFile == null)
                    m_progressStream.putStream("No DEX file found: "+ m_dexFileNames);

                m_progressStream.putStream("DEX to JAR complete: "
                    +TimeUtil.deltaTimeHrMinSec(m_dex2jar_time));
                m_dex2jar_time = 0;
            }

        }, DEX2JAR_THREAD, STACK_SIZE);

        m_dex2jarThread.setPriority(Thread.MAX_PRIORITY);
        m_dex2jarThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        m_dex2jarThread.start();

        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("dex2jar_thread", getThreadStatus( true, m_dex2jarThread));
            wrapper.put("dex2jar_url", m_appFolderUrl);

        } catch (JSONException e) {
            LogUtil.logException(LogUtil.LogType.DECOMPILE, e);
        }

        return wrapper;
    }

    public static JSONObject jar2src(Context ctx, final String package_name, String compile_method) {

        if( m_appFolderFullPath == null)
            init( ctx, package_name);

        JSONObject wrapper = new JSONObject();
        String url = "";
        String processKey = "undef";
        String processStatus = null;
        String urlKey = "";
        boolean success = false;

        MY_THREAD compileMethod = MY_THREAD.valueOf( compile_method);

        LogUtil.log(LogUtil.LogType.DECOMPILE, "compile method: "+compile_method);

        switch( compileMethod){

            case cfr: {

                m_srcCfrFolder.mkdirs();

                Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {

                        LogUtil.log(LogUtil.LogType.DECOMPILE, "Uncaught exception: "+e.toString());
                        m_progressStream.putStream("Uncaught exception: "+t.getName());
                        m_progressStream.putStream("Uncaught exception: "+e.toString());
                    }
                };

                m_cfr_time = System.currentTimeMillis();  // Save start time for tracking

                m_cfrThread = new Thread(m_threadGroup, new Runnable() {
                    @Override
                    public void run() {

                        m_progressStream.putStream("CFR starting");
                        File jarFile = null;
                        try {
                            for (String fileName : m_dexFileNames) {

                                jarFile = new File(m_appFolderFullPath + "/" + fileName + ".jar");

                                if( jarFile.exists() && jarFile.isFile()){

                                    String[] args = {jarFile.toString(), "--outputdir",
                                            m_srcCfrFolder.getStdFile().toString()};
                                    GetOptParser getOptParser = new GetOptParser();

                                    org.benf.cfr.reader.util.getopt.Options options =
                                            getOptParser.parse(args, OptionsImpl.getFactory());

                                    if (!options.optionIsSet(OptionsImpl.HELP) && options.getOption(OptionsImpl.FILENAME) != null) {

                                        final org.benf.cfr.reader.util.getopt.Options finalOptions = options;

                                        m_progressStream.putStream("CFR starting from DEX: "+fileName);

                                        ClassFileSourceImpl classFileSource = new ClassFileSourceImpl(finalOptions);
                                        final DCCommonState dcCommonState = new DCCommonState(finalOptions, classFileSource);
                                        final String path = finalOptions.getOption(OptionsImpl.FILENAME);
                                        DumperFactoryImpl dumperFactory = new DumperFactoryImpl(options);
                                        org.benf.cfr.reader.Main.doJar(dcCommonState, path, dumperFactory);
                                        m_progressStream.putStream("CFR from DEX complete: "+fileName);
                                    }
                                }
                            }

                        }catch(Exception | StackOverflowError e){
                            m_progressStream.putStream(e.toString());
                        }
                        m_progressStream.putStream("CFR complete: "+TimeUtil.deltaTimeHrMinSec(m_cfr_time));
                        m_cfr_time = 0;
                    }
                }, DEEPDIVE_THREAD_GROUP, STACK_SIZE);

                m_cfrThread.setPriority(Thread.MAX_PRIORITY);
                m_cfrThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
                m_cfrThread.start();

                processKey = "cfr_thread";
                processStatus = getThreadStatus( true, m_cfrThread);
                urlKey = "cfr_url";
                url = OmniHash.getHashedServerUrlFullPath(ctx,
                        VolUtil.sdcardVolumeId, m_srcCfrFolderPath);

                LogUtil.log(LogUtil.LogType.DECOMPILE, compile_method+" complete");
                break;
            }

            /**
             * Jadx converts a DEX file directly into Java files.  It does not input JAR files.
             */
            case jadx: {

                m_srcJadxFolder.mkdirs();
                Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {

                        LogUtil.log(LogUtil.LogType.DECOMPILE, "Uncaught exception: "+e.toString());
                        m_progressStream.putStream("Uncaught exception: "+t.getName());
                        m_progressStream.putStream("Uncaught exception: "+e.toString());
                    }
                };

                m_jadx_time = System.currentTimeMillis();  // Save start time for tracking

                m_jadxThread = new Thread( m_threadGroup, new Runnable() {
                    @Override
                    public void run() {

                        m_progressStream.putStream("Jadx starting");
                        List<File> dexList = new ArrayList<>();
                        File dexFile = null;
                        JadxDecompiler jadx = new JadxDecompiler();
                        jadx.setOutputDir(m_srcJadxFolder.getStdFile());

                        for (String fileName : m_dexFileNames) {

                            dexFile = new File(m_appFolderFullPath + "/" + fileName + ".dex");

                            if( dexFile.exists() && dexFile.isFile()) {

                                dexList.add( dexFile);

                                if( fileName.contentEquals( OPTIMIZED_CLASSES))
                                    break;
                            }
                        }
                        try {
                            m_progressStream.putStream("Loading: "+dexList);
                            jadx.loadFiles(dexList);
                            m_progressStream.putStream("Load complete");
                        } catch (JadxException e) {
                            LogUtil.logException(LogUtil.LogType.DECOMPILE, e);
                            m_progressStream.putStream(e.toString());
                        }
                        m_progressStream.putStream("Jadx saveSources start");
                        jadx.saveSources();
                        m_progressStream.putStream("Jadx saveSources complete");

                        m_progressStream.putStream("Jadx complete: "+TimeUtil.deltaTimeHrMinSec(m_jadx_time));
                        m_jadx_time = 0;
                    }
                }, JADX_THREAD, STACK_SIZE);

                m_jadxThread.setPriority(Thread.MAX_PRIORITY);
                m_jadxThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
                m_jadxThread.start();

                processKey = "jadx_thread";
                processStatus = getThreadStatus( true, m_jadxThread);
                urlKey = "jadx_url";
                url = OmniHash.getHashedServerUrlFullPath(ctx,
                        VolUtil.sdcardVolumeId, m_srcJadxFolderPath);

                break;
            }

            /**
             * FernFlower converts JAR files to a zipped decompiled JAR file
             */
            case fern_flower: {// https://github.com/fesh0r/fernflower

                m_srcFernFolder.mkdirs();

                Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {

                        LogUtil.log(LogUtil.LogType.DECOMPILE, "Uncaught exception: "+e.toString());
                        m_progressStream.putStream("Uncaught exception: "+t.getName());
                        m_progressStream.putStream("Uncaught exception: "+e.toString());
                    }
                };

                m_fern_time = System.currentTimeMillis();  // Save start time for tracking

                m_fernThread = new Thread( m_threadGroup, new Runnable() {
                    @Override
                    public void run() {

                        File javaOutputDir = m_srcFernFolder.getStdFile();

                        File jarFile = null;
                        String jarFileName = "";

                        for(int i = 1; i < m_dexFileNames.length; i++) {

                            jarFileName = m_dexFileNames[i]+".jar";
                            jarFile = new File(m_appFolderFullPath + "/" + jarFileName);

                            if( jarFile.exists() && jarFile.isFile()) {

                                boolean success = true;
                                try {
                                    m_progressStream.putStream("FernFlower starting: "+ jarFileName);
                                    PrintStream printStream = new PrintStream(m_progressStream);
                                    System.setErr(printStream);
                                    System.setOut(printStream);
                                    PrintStreamLogger logger = new PrintStreamLogger(printStream);

                                    final Map<String, Object> mapOptions = new HashMap<>();
                                    ConsoleDecompiler decompiler = new ConsoleDecompiler(javaOutputDir, mapOptions, logger);
                                    decompiler.addSpace( jarFile, true);

                                    m_progressStream.putStream("FernFlower decompiler.addSpace complete: "+jarFileName);
                                    decompiler.decompileContext();
                                    m_progressStream.putStream("FernFlower decompiler.decompileContext complete: "+jarFileName);

                                    OmniFile decompiledJarFile = new OmniFile(
                                            VolUtil.sdcardVolumeId, javaOutputDir + "/" + package_name + ".jar"+jarFileName);
                                    success = unzipFile( decompiledJarFile, m_srcFernFolder, null, null);

                                    if (success)
                                        m_progressStream.putStream("FernFlower decompiler.unpack complete: "+jarFileName);
                                    else
                                        m_progressStream.putStream("FernFlower decompiler.unpack failed: "+jarFileName);
                                } catch (Exception e) {
                                    String str = LogUtil.logException(LogUtil.LogType.FERNFLOWER, e);
                                    m_progressStream.putStream("FernFlower exception "+jarFileName);
                                    m_progressStream.putStream(str);
                                    success = false;
                                }
                                /**
                                 * Look for the classes.jar file and unzip it
                                 */
                                if( ! success ){

                                    OmniFile of = new OmniFile(VolUtil.sdcardVolumeId, m_srcFernFolderPath+"/classes.jar");
                                    if( of.exists()){

                                        ApkZipUtil.unzip( of, m_srcFernFolder, m_progressStream);
                                        m_progressStream.putStream("FernFlower utility unzip complete with errors: "+jarFileName);
                                    }
                                    else
                                        m_progressStream.putStream("File does not exist: "+of.getAbsolutePath());
                                }
                            }
                        }
                        m_progressStream.putStream( "FernFlower complete: "+TimeUtil.deltaTimeHrMinSec(m_fern_time));
                        m_fern_time = 0;
                    }
                }, FERN_THREAD, STACK_SIZE);

                m_fernThread.setPriority(Thread.MAX_PRIORITY);
                m_fernThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
                m_fernThread.start();

                processKey = "fern_thread";
                processStatus = getThreadStatus( true, m_fernThread);
                urlKey = "fern_url";
                url = OmniHash.getHashedServerUrlFullPath(ctx,
                        VolUtil.sdcardVolumeId, m_srcFernFolderPath);

                break;
            }
            case simple_unpack: {

                // Simply unpack the jar file
                success = unzipFile(m_jarFile, m_srcCfrFolder, null, null);
                break;
            }
            default:
                LogUtil.log(LogUtil.LogType.DECOMPILE, "unknown compile method: "+compile_method);
        }

        try {
            wrapper.put("url", m_appFolderUrl);
            wrapper.put(processKey, processStatus);
            wrapper.put( urlKey, url);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return wrapper;
    }

    public static JSONObject stopThread(String myThreadName){

        MY_THREAD thread = MY_THREAD.valueOf(myThreadName);
        Thread myThread = null;

        switch(thread){

            case unpack_apk:
                myThread = m_unpackApkThread;
                break;
            case dex2jar:
                myThread = m_dex2jarThread;
                break;
            case optimize_dex:
                myThread = m_optimizeDexThread;
                break;
            case cfr:
                myThread = m_cfrThread;
                break;
            case jadx:
                myThread = m_jadxThread;
                break;
            case fern_flower:
                myThread = m_fernThread;
                break;
            case simple_unpack:
                break;
        }

        if( myThread != null){
            if( myThread.isInterrupted())
                myThread.currentThread().stop();
            else {
                myThread.currentThread().interrupt();
            }
        }

        if( myThread != null) {
            if( myThread.isInterrupted())
                m_progressStream.putStream( "Process is interrupted: "+ myThreadName);
            else
            if( myThread.isAlive())
                m_progressStream.putStream( "Process is alive: "+ myThreadName);
            else
                m_progressStream.putStream( "Process is not alive: "+ myThreadName);
        }
        else
            m_progressStream.putStream( "Process is null: "+ myThreadName);

        JSONObject status = new JSONObject();
        try {
            status.put("stop", myThread != null && myThread.isAlive()?1:0);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return status;
    }

    private static class DexExceptionHandlerMod implements DexExceptionHandler {
        @Override
        public void handleFileException(Exception e) {
            LogUtil.logException(LogUtil.LogType.DECOMPILE, "Dex2Jar Exception", e);
        }

        @Override
        public void handleMethodTranslateException(Method method, IrMethod irMethod, MethodNode methodNode, Exception e) {
            LogUtil.logException(LogUtil.LogType.DECOMPILE, "Dex2Jar Exception", e);
        }
    }

    public static void clearStream() {

        m_progressStream.init();
    }

    public static JSONArray getStream() {

        return m_progressStream.getStream();
    }

}

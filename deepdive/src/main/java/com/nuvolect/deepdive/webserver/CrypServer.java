package com.nuvolect.deepdive.webserver;//

import android.content.Context;

import com.nuvolect.deepdive.main.UserManager;
import com.nuvolect.deepdive.util.CConst;
import com.nuvolect.deepdive.util.CrypUtil;
import com.nuvolect.deepdive.util.LogUtil;
import com.nuvolect.deepdive.util.OmniFile;
import com.nuvolect.deepdive.util.Passphrase;
import com.nuvolect.deepdive.webserver.connector.CmdZipdl;
import com.nuvolect.deepdive.webserver.connector.ServeCmd;
import com.nuvolect.deepdive.webserver.connector.VolUtil;

import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nuvolect.deepdive.util.LogUtil.log;

/**<pre>
 * Server for running webserver on a service or background thread.
 *
 * SECURITY AND AUTHENTICATION
 *
 * Three technqiues are employed
 * 1. https self-signed certificate
 * 2. State/response: login allows minimal css, js files, others blocked
 * 3. Security token in header
 *
 * Each session uses a uniqueId to control security. Each session is authenticated indpendently.
 *
 * </pre>
 */
public class CrypServer extends NanoHTTPD{

    private static boolean DEBUG = LogUtil.DEBUG;
    static Context m_ctx;
    private static HashMap<String, String> sessionMap;
    private static HashMap<String, ArrayList<Long>> sessionMapSelected;
    private static String EMBEDDED_HEADER_KEY = "referer";
    private static String embedded_header_value = "";

    /**
     * System wide security token.
     */
    private static String m_sec_tok = "";
    private static IHTTPSession m_session = null;

    private static boolean mAuthenticated = false;

    private static Response.IStatus HTTP_OK = Response.Status.OK;

    public CrypServer(Context ctx, int port) {
        super(port);

        m_ctx = ctx;

        // Initialize session data
        sessionMap =  new HashMap<String, String>();
        sessionMapSelected =  new HashMap<String, ArrayList<Long>>();

        // Configure security token
        initSecTok(m_ctx);

        /**
         * All access to this IP is blocked unless it is the companion device
         */
        WebUtil.NullHostNameVerifier.getInstance().setHostVerifierEnabled(true);
    }

    @Override
    public Response serve(IHTTPSession session) {

        m_session = session;

//        if( DEBUG)
//            dumpSessionMap();

        CookieHandler cookies = session.getCookies();
        String uniqueId = cookies.read(CConst.UNIQUE_ID);
//        log(LogUtil.LogType.CRYP_SERVER, "uniqueId: "+uniqueId);

        if( uniqueId == null ){

            if( embedded_header_value.isEmpty())
                embedded_header_value = WebUtil.getServerUrl(m_ctx);

            Map<String, String> headers = session.getHeaders();

            for (Map.Entry<String, String> entry : headers.entrySet())
            {
//                LogUtil.log(LogUtil.LogType.CRYP_SERVER, "header: "+entry.getKey() + ":::" + entry.getValue());
                if( entry.getKey().startsWith( EMBEDDED_HEADER_KEY) &&
                        entry.getValue().contains( embedded_header_value)){
                    uniqueId = CConst.EMBEDDED_USER;
//                    LogUtil.log(LogUtil.LogType.CRYP_SERVER, "header MATCH");
                    break;
                }
            }
            if( DEBUG && uniqueId == null){

                LogUtil.log(LogUtil.LogType.CRYP_SERVER, "header value mismatch: "+embedded_header_value);
                for (Map.Entry<String, String> entry : headers.entrySet())
                {
                    LogUtil.log(LogUtil.LogType.CRYP_SERVER, "header: "+entry.getKey() + ":::" + entry.getValue());
                }
            }
        }

        if( uniqueId == null) {

            uniqueId = String.valueOf(System.currentTimeMillis());

            cookies.set(CConst.UNIQUE_ID, uniqueId, 7);
        }

//        if( uniqueId.contentEquals(CConst.EMBEDDED_USER))
//            cookies.set(CConst.UNIQUE_ID, uniqueId, 7);
        /**
         * Session is authenticated when authentication is wide open or
         * session has been previously authenticated.
         */
        mAuthenticated = UserManager.getInstance(m_ctx).isWideOpen()
                || uniqueId.contentEquals(CConst.EMBEDDED_USER)
                || get(uniqueId, CConst.AUTHENTICATED, "0").contentEquals( "1");

        Method method = session.getMethod();
        Map<String, String> params = session.getParms();
        String uri = session.getUri();
        String fileExtension = FilenameUtils.getExtension(uri).toLowerCase(Locale.US);
        params.put("queryParameterStrings", session.getQueryParameterString());

        log(LogUtil.LogType.CRYP_SERVER, method + " '" + uri + "' " + params.toString());

        InputStream is = null;

        try {
            if (uri != null) {

                if( ! mAuthenticated){

                    /**
                     * Minimal files for login page { /, /login.htm, .js, .css, .png, .ico,
                     * /connector cmd == login, cmd == logout}
                     */
                    if( uri.contentEquals("/") || uri.contentEquals("/login.html")) {

                        is = m_ctx.getAssets().open("login.html");
                        return new Response(HTTP_OK, MimeUtil.MIME_HTML, is, -1);

                    } else if( uri.contentEquals("/logout.html")) {

                        is = m_ctx.getAssets().open( "logout.html");
                        return new Response(HTTP_OK, MimeUtil.MIME_HTML, is, -1);

                    } else if (fileExtension.contentEquals("js")) {
                        is = m_ctx.getAssets().open(uri.substring(1));
                        return new Response(HTTP_OK, MimeUtil.MIME_JS, is, -1);

                    } else if (fileExtension.contentEquals("css")) {
                        is = m_ctx.getAssets().open(uri.substring(1));
                        return new Response(HTTP_OK, MimeUtil.MIME_CSS, is, -1);

                    } else if ( fileExtension.contentEquals("png")) {
                        is = m_ctx.getAssets().open(uri.substring(1));
                        return new Response(HTTP_OK, MimeUtil.MIME_PNG, is, -1);

                    } else if ( fileExtension.contentEquals("ico")) {
                        is = m_ctx.getAssets().open(uri.substring(1));
                        return new Response(HTTP_OK, MimeUtil.MIME_ICO, is, -1);

                    } else if (uri.startsWith("/connector") &&
                            params.containsKey("cmd") &&
                            (params.get("cmd").contentEquals("login") ||
                                    params.get("cmd").contentEquals("logout"))) {

                        Map<String, String> files = new HashMap<String, String>();
                        try {
                            session.parseBody(files);
                        } catch (ResponseException e) {
                            e.printStackTrace();
                        }
                        String postBody = session.getQueryParameterString();
                        params.put("postBody", postBody);
                        params.put(CConst.UNIQUE_ID, uniqueId);

                        is = ServeCmd.process(m_ctx, params);
                        return new Response(HTTP_OK, MimeUtil.MIME_HTML, is, -1);

                    } else{
                        is = new ByteArrayInputStream("401 - Unauthorized request".getBytes());
                        return new Response(HTTP_OK, MimeUtil.MIME_TXT, is, -1);
                    }
                }

                /**
                 * The URI can be one of several types
                 * 1. volume + hash
                 * 2. volume + hash + path
                 *    // /l0_L3N0b3JhZ2UvZW11bGF0ZWQvMC90ZXN0L3JldmVhbC5qcy1tYXN0ZXI/test/reveal.js-master/css/reveal.css
                 * 3. /  root file is lobby.htm
                 * 4. /connector
                 * 5. /*  file is in /assets
                 */

                String volumeId = VolUtil.getVolumeIdFromUri(uri);
                /**
                 * Check for a URI with a volumeId, case 1 and 2
                 */
                if( ! volumeId.isEmpty()){

                    OmniFile file = VolUtil.getFileFromUri( uri );
                    String mime = file.getMime();

//                    LogUtil.log(LogUtil.LogType.CRYP_SERVER,
//                            "hashed file path: "+file.getPath()+", mime: "+mime);
//                    LogUtil.log(LogUtil.LogType.CRYP_SERVER, "file exists: "+file.exists());

                    is = file.getFileInputStream();

                    return new Response(HTTP_OK, mime, is, file.length());
                }

                /**
                 * 3. Check for root /
                 */
                if( uri.contentEquals("/")){

                    is = m_ctx.getAssets().open( "lobby.html");
                    return new Response(HTTP_OK, MimeUtil.MIME_HTML, is, -1);
                }

                /**
                 * 4. Check for /connector
                 */
                if (uri.startsWith("/connector")) {

                    if (Method.PUT.equals(method) || Method.POST.equals(method)) {
                        try {
                            Map<String, String> files = new HashMap<String, String>();
                            session.parseBody(files);
                            Set<String> fileSet = files.keySet();
                            JSONArray array = new JSONArray();

                            if (!fileSet.isEmpty()) {

                                for (String key : fileSet) {

                                    JSONObject jsonObject = new JSONObject();
                                    String filePath = files.get(key);
                                    String fileName = params.get(key);
                                    jsonObject.put(CConst.FILE_PATH, filePath);
                                    jsonObject.put(CConst.FILE_NAME, fileName);
                                    array.put(jsonObject);
                                }
                            }
                            params.put("post_uploads", array.toString());

                        } catch (IOException ioe) {
                            LogUtil.logException(LogUtil.LogType.CRYP_SERVER, ioe);
                        } catch (ResponseException re) {
                            LogUtil.logException(LogUtil.LogType.CRYP_SERVER, re);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }

                    // Save for elFinder debugging
                    params.put("url", WebUtil.getServerUrl(m_ctx));

                    InputStream stream = ServeCmd.process(m_ctx, params);

                    /**
                     * Manage a file transfer.
                     * A file can be downloaded or opened in the browser depending
                     * on the download=1/0 parameter.
                     * An appropriate mime is selected based on the filename extension.
                     */
                    if (
                            params.containsKey("cmd") &&
                                    params.get("cmd").contentEquals("file")) {

                        String fileName = "";
                        String mime = "";

                        if( params.containsKey("target")){

                            String target = params.get("target");
                            OmniFile targetFile = VolUtil.getFileFromHash(target);
                            fileName = targetFile.getName();
                            mime = MimeUtil.getMime(targetFile);
                        }else
                        if( params.containsKey("path")){

                            String path = params.get("path");
                            java.io.File file = new java.io.File( path );
                            fileName = file.getName()+".zip";
                            mime = MimeUtil.MIME_ZIP;
                            params.put("download","1");
                        }

                        Response response = new Response(HTTP_OK, mime, stream, -1);

                        if (params.containsKey("download") &&
                                params.get("download").contentEquals("1")) {

                            response.addHeader(
                                    "Content-Disposition: attachment; filename=" + fileName + ";", fileName);
                        }

                        return response;
                    }
                    else if (
                            Method.POST.equals(method) &&
                                    params.containsKey("cmd") &&
                                    params.get("cmd").contentEquals("zipdl")) {

                        String mime = MimeUtil.MIME_ZIP;
                        String fileName = CmdZipdl.zipdlFilename;
                        Response response = new Response(HTTP_OK, mime, stream, -1);
                        response.addHeader(
                                "Content-Disposition: attachment; filename=" + fileName + ";", fileName);
                        return response;
                    }
                    else
                        return new Response(HTTP_OK, MimeUtil.MIME_HTML, stream, -1);
                }

                /**
                 * 5. File is in assets
                 */

                is = m_ctx.getAssets().open(uri.substring(1));
                String mime = MimeUtil.getMime( fileExtension);
                return new Response(HTTP_OK, mime, is, -1);
            }

        } catch (IOException e) {
            log(LogUtil.LogType.CRYP_SERVER, "Error opening file: " + uri.substring(1));
            e.printStackTrace();
        }

        return super.serve(session);
    }

    private void dumpSessionMap() {

        LogUtil.log(LogUtil.LogType.CRYP_SERVER, "sessionMap.size: "+sessionMap.size());
        Iterator it = sessionMap.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, pair.getKey() + " = " + pair.getValue());
        }
    }

    /**
     * Get session data for a unique key with default
     * @param uniqueId
     * @param key
     * @param defaultString
     *
     * @return the value of the mapping with the specified key, or the defaultString
     *         if no mapping for the specified key is found.
     */
    public static String get(String uniqueId, String key, String defaultString) {

        if( uniqueId == null || uniqueId.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_1 uniqueId is null");
            return null;
        }
        if( key == null || key.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_2 key is null");
            return null;
        }

        String v = sessionMap.get(key + uniqueId);
        if( v == null){
            v = defaultString;
            sessionMap.put(key + uniqueId, defaultString);
        }

        return v;
    }

    /**
     * Get session data for a unique key.
     * @param uniqueId
     * @param key
     *
     * @return the value of the mapping with the specified key, or {@code null}
     *         if no mapping for the specified key is found.
     */
    public static String get(String uniqueId, String key) {

        if( uniqueId == null || uniqueId.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_3 uniqueId is null");
            return null;
        }
        if( key == null || key.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_4 key is null");
            return null;
        }

        return sessionMap.get(key + uniqueId);
    }

    /**
     * Save session data to the hashmap
     * @param uniqueId
     * @param key
     * @param value
     */
    public static void put(String uniqueId, String key, String value){


        if( uniqueId == null || uniqueId.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_5 uniqueId is null");
            return;
        }
        if( key == null || key.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_6 key is null");
            return;
        }

        sessionMap.put(key + uniqueId, value);
    }

    /**
     * Save session data to the hashmap
     * @param uniqueId
     * @param key
     * @param intValue
     */
    public static void put(String uniqueId, String key, int intValue){

        if( uniqueId == null || uniqueId.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_7 uniqueId is null");
            return;
        }
        if( key == null || key.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_8 key is null");
            return;
        }

        sessionMap.put(key + uniqueId, String.valueOf(intValue));
    }
    /**
     * Save session data to the hashmap
     * @param uniqueId
     * @param key
     * @param longValue
     */
    public static void put(String uniqueId, String key, long longValue){

        if( uniqueId == null || uniqueId.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_9 uniqueId is null");
            return;
        }
        if( key == null || key.isEmpty()){
            LogUtil.log(LogUtil.LogType.CRYP_SERVER, "ERROR_10 key is null");
            return;
        }
        sessionMap.put(key + uniqueId, String.valueOf(longValue));
    }
    /**
     * Return a notification message.  If the message duration has expired, clear the
     * message from the system and return an empty string.
     * @param uniqueId
     * @return
     */
    public static String getNotify(String uniqueId){

        String js = CrypServer.get(uniqueId, "notify_js");
        if( js.isEmpty())
            return "";

        long msgExpire = Long.valueOf(CrypServer.get(uniqueId, "notify_js_duration"));
        if( msgExpire > System.currentTimeMillis())
            return js;
        else {
            CrypServer.put(uniqueId, "notify_js", "");
            return "";
        }
    }

    /**
     * Configure the security token.
     * @param ctx
     */
    private static void initSecTok(Context ctx) {

        setSecTok(m_ctx, CrypUtil.get(ctx,
                CConst.SEC_TOK, Passphrase.generateRandomString(32, Passphrase.SYSTEM_MODE)));
    }

    /**
     * Get the system wide security token
     * @return
     */
    public static String getSecTok() {

        return m_sec_tok;
    }

    /**
     * Set the system wide security token.
     * @param sec_tok String
     */
    public static void setSecTok(Context ctx, String sec_tok) {

        m_sec_tok = sec_tok;
        CrypUtil.put(ctx, CConst.SEC_TOK, sec_tok);
    }

    /**
     * Clear user credentials.
     * This will disable all network access with the exception of login.
     *
     * @param ctx
     * @return
     */
    public static boolean clearCredentials(Context ctx){

        boolean success = false;

        if( m_session != null){

            CookieHandler cookies = m_session.getCookies();
            String uniqueId = cookies.read(CConst.UNIQUE_ID);
            put(uniqueId, CConst.AUTHENTICATED, "0");

            success = true;
        }
        mAuthenticated = false;
        setSecTok(ctx, "");

        return success;
    }

    /**
     * Configure the security token and default page for a new user
     * @param ctx
     * @param uniqueId
     */
    public static void setValidUser(Context ctx, String uniqueId) {

        initSecTok( ctx);

        put(uniqueId, CConst.AUTHENTICATED, "1");

        LogUtil.log(LogUtil.LogType.CRYP_SERVER, "cookie set authenticated: "+uniqueId);
    }
}


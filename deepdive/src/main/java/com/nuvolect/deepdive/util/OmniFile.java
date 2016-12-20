package com.nuvolect.deepdive.util;//

import android.net.Uri;
import android.util.Log;

import com.nuvolect.deepdive.webserver.MimeUtil;
import com.nuvolect.deepdive.webserver.connector.FileObj;
import com.nuvolect.deepdive.webserver.connector.VolUtil;

import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Represent a file that can either be an encrypted file or a standard java file.
 * Mirror support methods as necessary.
 */
public class OmniFile {

    static boolean DEBUG = false; //LogUtil.DEBUG;

    /**
     * Volume ID for the specified file. The volume ID ends with the '_' underscore character.
     */
    private final String m_volumeId;
    /**
     * Standard unix clear-text file. When the crypt file is in use this file is null.
     */
    private java.io.File m_std_file;
    /**
     * Encrypted unix file. When the standard java file is in use thsi file is null.
     */
    private info.guardianproject.iocipher.File m_cry_file;

    /**
     * Convenience boolean for standard(clear)/encrypted file.
     */
    private boolean m_isCryp;

    /**
     * Debugging only
     */
    private String m_name;
    private String m_path;

    /**
     * Create an OmniFile from volumeId and path components.
     * @param volumeId
     * @param path
     */
    public OmniFile(String volumeId, String path) {

        m_volumeId = volumeId;

        m_isCryp = volumeId.contentEquals(VolUtil.cryptoVolumeId);

        if( m_isCryp ){
            m_cry_file = new info.guardianproject.iocipher.File( path );
//            long time = System.currentTimeMillis();
//            boolean success = m_cry_file.setLastModified( time );
//            if( ! success)
//                LogUtil.log(LogUtil.LogType.OMNI_FILE, "setLastModfiedFailed for: "+path);
//            if( time != lastModified())
//                LogUtil.log(LogUtil.LogType.OMNI_FILE,
//                        "time: "+time+" not equal "+lastModified());
        }
        else
            m_std_file = new java.io.File( path );

        if( DEBUG ){
            m_name = this.getName();
            m_path = this.getPath();
            long ts = this.lastModified();
            if( DEBUG )
                LogUtil.log("OmniFile:  "+ m_name+" last modified: "+TimeUtil.friendlyTimeString( ts));
        }
    }

    /**
     * Create an OmniFile from a volumeHash which has the
     * volume id on the front end of a hash.
     * @param volumeHash
     */
    public OmniFile( String volumeHash){

        String segments[] = volumeHash.split("_");
        m_volumeId = segments[0] + "_";
        m_isCryp = m_volumeId.contentEquals(VolUtil.cryptoVolumeId);
        String path = OmniHash.decode(segments[1]);

        if( m_isCryp )
            m_cry_file = new info.guardianproject.iocipher.File( path );
        else
            m_std_file = new java.io.File( path );

        if( DEBUG ){
            m_name = this.getName();
            m_path = this.getPath();
        }
    }

    /**
     * Get the has of the file not including the volume root.
     * @return
     */
    public String getHash(){

        if( m_isCryp )
            return m_volumeId+ OmniHash.encode( m_cry_file.getPath() );
        else
            return m_volumeId+ OmniHash.encode( m_std_file.getPath() );
    }

    /**
     * Returns a new file made from the pathname of the parent of this file.
     * This is the path up to but not including the last name. {@code null} is
     * returned when there is no parent.
     *
     * @return a new file representing this file's parent or {@code null}.
     */
    public OmniFile getParentFile(){

        if( m_isCryp ){

            info.guardianproject.iocipher.File parent = m_cry_file.getParentFile();
            if( parent == null)
                return null;
            else
            return new OmniFile( m_volumeId, parent.getPath());
        }
        else {
            java.io.File parent = m_std_file.getParentFile();
            if( parent == null)
                return null;
            else
                return new OmniFile(m_volumeId, parent.getPath());
        }
    }

    public String getVolumeId() {
        return m_volumeId;
    }

    public String getUriFromFile() {

        if( m_isCryp ){

            return Uri.fromFile( m_cry_file).toString();
        }else{
            return Uri.fromFile( m_std_file).toString();
        }
    }

    public String getPath() {

        if( m_isCryp )
            return m_cry_file.getPath();
        else
            return m_std_file.getPath();
    }

    public String getAbsolutePath() {

        if( m_isCryp )
            return m_cry_file.getAbsolutePath();
        else
            return m_std_file.getAbsolutePath();
    }

    public String getName() {

        String name;
        if( m_isCryp )
            name = m_cry_file.getName();
        else
            name = m_std_file.getName();

        if( this.isRoot())
            name = VolUtil.getVolumeName( this.getVolumeId());

        return name;
    }

    public String getExtension() {

        return FilenameUtils.getExtension( getName()).toLowerCase(Locale.US);
    }

    public OmniFile[] listFiles() {

        if( m_isCryp ) {

            info.guardianproject.iocipher.File files[] = m_cry_file.listFiles();
            if( files == null || files.length == 0)
                return new OmniFile[0];
            OmniFile[] omniFiles = new OmniFile[files.length];

            for( int i = 0; i < files.length; i++)
                omniFiles[i] = new OmniFile(m_volumeId, files[i].getPath());

            return omniFiles;
        }
        else {
            java.io.File files[] = m_std_file.listFiles();
            if( files == null || files.length == 0)
                return new OmniFile[0];
            OmniFile[] omniFiles = new OmniFile[files.length];

            for( int i = 0; i < files.length; i++)
                omniFiles[i] = new OmniFile(m_volumeId, files[i].getPath());

            return omniFiles;
        }
    }

    public long lastModified() {
        if( m_isCryp )
            return m_cry_file.lastModified();
        else
            return m_std_file.lastModified();
    }

    public void setLastModified(long timeInSec) {

        if( timeInSec <= 0)
            return;

        if( m_isCryp )
            m_cry_file.setLastModified( timeInSec);
        else
            m_std_file.setLastModified( timeInSec);
    }

    /**
     * Set last modfied time based on the current time.
     */
    public void setLastModified() {
        long timeInSec = System.currentTimeMillis() / 1000;
        if( m_isCryp )
            m_cry_file.setLastModified( timeInSec);
        else
            m_std_file.setLastModified( timeInSec);
    }

    public long length() {
        if( m_isCryp )
            return m_cry_file.length();
        else
            return m_std_file.length();
    }

    public long getTotalSpace() {
        if( m_isCryp )
            return m_cry_file.getTotalSpace();
        else
            return m_std_file.getTotalSpace();
    }

    public long getFreeSpace() {
        if( m_isCryp )
            return m_cry_file.getFreeSpace();
        else
            return m_std_file.getFreeSpace();
    }

    public boolean canRead() {
        if( m_isCryp )
            return m_cry_file.canRead();
        else
            return m_std_file.canRead();
    }

    public boolean canWrite() {
        if( m_isCryp )
            return m_cry_file.canWrite();
        else
            return m_std_file.canWrite();
    }

    public boolean exists() {
        if( m_isCryp )
            return m_cry_file.exists();
        else
            return m_std_file.exists();
    }

    public boolean isFile() {
        if( m_isCryp )
            return m_cry_file.isFile();
        else
            return m_std_file.isFile();
    }

    public boolean isDirectory() {
        if( m_isCryp )
            return m_cry_file.isDirectory();
        else
            return m_std_file.isDirectory();
    }

    public boolean mkdirs() {
        if( m_isCryp )
            return m_cry_file.mkdirs();
        else
            return m_std_file.mkdirs();
    }

    public boolean mkdir() {
        if( m_isCryp )
            return m_cry_file.mkdir();
        else
            return m_std_file.mkdir();
    }

    public boolean createNewFile() {
        try {
            if( m_isCryp )
                return m_cry_file.createNewFile();
            else
                return m_std_file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean renameFile(OmniFile newFile) {
        if( m_isCryp )
            return m_cry_file.renameTo(newFile.getCryFile());
        else
            return m_std_file.renameTo( newFile.getStdFile());
    }

    public boolean isCryp() {
        return m_isCryp;
    }

    public boolean isStd() {
        return ! m_isCryp;
    }

    public info.guardianproject.iocipher.File getCryFile() {
        return m_cry_file;
    }

    public java.io.File getStdFile() {
        return m_std_file;
    }

    public OutputStream getOutputStream() throws FileNotFoundException {
        if( m_isCryp )
            return new info.guardianproject.iocipher.FileOutputStream(m_cry_file);
        else
            return new java.io.FileOutputStream(m_std_file);
    }

    public InputStream getFileInputStream() {

        try {

            if( m_isCryp)
                return new info.guardianproject.iocipher.FileInputStream( m_cry_file );
            else
                return new java.io.FileInputStream( m_std_file );

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean delete() {
        if( m_isCryp )
            return m_cry_file.delete();
        else
            return m_std_file.delete();
    }

    public String getMime() {

        return MimeUtil.getMime(this);
    }

    public boolean isRoot() {

        if( m_isCryp)
            return m_cry_file.getPath().contentEquals("/");
        else{

            String volumeRoot = VolUtil.getRoot( m_volumeId);
            String path = m_std_file.getPath();

            if( path.contentEquals( volumeRoot))
                return true;
            if( (path + "/").contentEquals(volumeRoot))
                return true;
            else
                return false;
        }
    }

    /**
     * Return an arrayList of file objects for this file.
     * @param httpIpPort
     * @return
     */
    public JSONArray listFileObjects(String httpIpPort) {

        if( DEBUG )
            LogUtil.log("ListFileObjects from:  "+ this.getPath()
                + ", hash: " + this.getHash());

        JSONArray filesArray = new JSONArray();
        OmniFile[] files = this.listFiles();
        if( files == null || files.length == 0)
            return filesArray;

        int i = 0;
        String indent = "";

        for( OmniFile file: files){

            String volumeId = file.getVolumeId();
            JSONObject fileObj = FileObj.makeObj(volumeId, file, httpIpPort);
            filesArray.put( fileObj);

            String type = file.isDirectory()? " dir  ": " file ";
            if( DEBUG )
                LogUtil.log("ListFileObjects "+ indent + ++i + type + file.getName()
                    + ", hash: " + file.getHash());
        }

        return filesArray;
    }

    public JSONObject getFileObject(String httpIpPort) {

        String volumeId = this.getVolumeId();
        JSONObject fileObj = FileObj.makeObj(volumeId, this, httpIpPort);

        return fileObj;
    }

    /**
     * Return contents of a file as a string.
     * @return
     */
    public String readFile() {

        String fileContents = "";
        StringBuilder sb = new StringBuilder();

        try {
            InputStream is = this.getFileInputStream();

            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {

                String s = new String( buffer, 0, len, "UTF-8");
                sb.append( s );
            }
            fileContents = sb.toString();

            if( is != null)
                is.close();
        } catch (FileNotFoundException e) {
            Log.e(LogUtil.TAG, "Exception while getting FileInputStream", e);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return fileContents;
    }

    /**
     * Write a string to the file.
     * @param fileContents
     * @return
     */
    public boolean writeFile(String fileContents){

        boolean success = true;
        try {
            OutputStream out = null;

            out = new BufferedOutputStream( this.getOutputStream());

            out.write(fileContents.getBytes());

            if( out != null)
                out.close();
        }
        catch (IOException e) {
            Log.e("Exception", "File write failed: " + e.toString());
            success = false;
        }
        return success;
    }

    /**
     * Return the dimensions of an file if it is an image.
     * Return an empty string if the file is not an image.
     * @return
     */
    public String getDim() {

        return OmniImage.getDim( this );
    }

    /**
     * Return a PhotoSwipe object
     * @param httpIpPort
     * @return JSONObject
     */
    public JSONObject getPsObject(String httpIpPort) {

        JSONObject psObject = new JSONObject();

        try {

            psObject.put("name", this.getName());
            psObject.put("src", httpIpPort+"/"+this.getHash());
            OmniImage.addPsImageSize(this, psObject);

        } catch (JSONException e) {
            e.printStackTrace();
        }
        return psObject;
    }
}

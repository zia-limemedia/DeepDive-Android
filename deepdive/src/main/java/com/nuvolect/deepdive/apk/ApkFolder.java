package com.nuvolect.deepdive.apk;

import android.content.Context;

import com.nuvolect.deepdive.license.AppSpecific;
import com.nuvolect.deepdive.util.OmniHash;
import com.nuvolect.deepdive.util.Persist;
import com.nuvolect.deepdive.webserver.connector.VolUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;


/**
 * Manage user uploaded APK folders.
 */
public class ApkFolder {

    public static JSONObject folderAdd(Context ctx, String folder_name) {

        String root = VolUtil.getRoot(VolUtil.sdcardVolumeId);
        String folder_path = root + AppSpecific.getAppFolderPath(ctx) + "/" + folder_name;
        File folder = new File( folder_path);

        folder.mkdirs();

        JSONArray array = Persist.addFolder(ctx, folder_name);
        array = addFolderUrl( ctx, array );

        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("error", "");
            wrapper.put("list", array);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return wrapper;
    }

    public static JSONObject folderRemove(Context ctx, String folder_name) {

        String error = Persist.deleteFolder( ctx, folder_name)?"":"Folder not found";
        JSONArray array = Persist.getFolderList(ctx);
        array = addFolderUrl( ctx, array );

        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("error", error);
            wrapper.put("list", array);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return wrapper;
    }

    public static JSONObject folderGetList(Context ctx) {

        JSONArray array = Persist.getFolderList( ctx );
        array = addFolderUrl( ctx, array );

        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("error", "");
            wrapper.put("list", array);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return wrapper;
    }

    private static JSONArray addFolderUrl(Context ctx, JSONArray array) {

        JSONArray fatList = new JSONArray();
        String appPath = AppSpecific.getAppFolderPath(ctx) + "/";
        try {

            for( int i = 0; i < array.length(); i++){

                JSONObject object = new JSONObject();
                object.put("name", array.get( i ));

                String url = OmniHash.getHashedServerUrlRelativePath(
                        ctx, VolUtil.sdcardVolumeId, appPath+array.get( i ));
                object.put("url", url);
                fatList.put( object);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return fatList;
    }
}
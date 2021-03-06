/*
 * Copyright (c) 2018 Nuvolect LLC.
 * This software is offered for free under conditions of the GPLv3 open source software license.
 * Contact Nuvolect LLC for a less restrictive commercial license if you would like to use the software
 * without the GPLv3 restrictions.
 */

package com.nuvolect.deepdive.webserver.connector;//

import com.nuvolect.deepdive.util.LogUtil;
import com.nuvolect.deepdive.util.OmniFile;
import com.nuvolect.deepdive.util.OmniFiles;

import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Map;

/**
 * <pre>
 * paste

 Copies or moves a directory / files

 Arguments:

 cmd : paste
 src : hash of the directory from which the files will be copied / moved (the source)
 dst : hash of the directory to which the files will be copied / moved (the destination)
 targets : An array of hashes for the files to be copied / moved
 cut : 1 if the files are moved, missing if the files are copied
 renames : Filename list of rename request
 suffix : Suffixes during rename (default is "~")
 Response:

 If the copy / move is successful:

 added : (Array) array of file and directory objects pasted. Information about File/Directory
 removed : (Array) array of file and directory 'hashes' that were successfully deleted
 {
 "added": [{
 "mime": "text\/plain",
 "ts": 1380910690,
 "read": 1,
 "write": 1,
 "size": 51,
 "hash": "l2_dW50aXRsZWQgZm9sZGVyL1JlYWQgVGhpcyBjb3B5IDEudHh0",
 "name": "Read This copy 1.txt",
 "phash": "l2_dW50aXRsZWQgZm9sZGVy"
 }],
 "removed": ["l2_UmVhZCBUaGlzIGNvcHkgMS50eHQ"]
 }
 Caution

 If the file name of the rename list exists in the directory,
 The command should rename the file to "filename + suffix"
 The command should stop copying at the first error. Is not allowed to overwrite
 files / directories with the same name. But the behavior of this command depends on
 some options on connector (if the user uses the default one). Please, take look the options:
 https://github.com/Studio-42/elFinder/wiki/Connector-configuration-options-2.1#copyoverwrite
 https://github.com/Studio-42/elFinder/wiki/Connector-configuration-options-2.1#copyjoin

 * Example:
 * GET '/servlet/connector'
 * {
 *   cmd=paste,
 *   cut=0,
 *   targets[]=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMC9Eb3dubG9hZC9mcm96ZW4gcm9zZSBjb3B5IDEuanBn,
 *   dst=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMC90bXA,
 *   suffix=~, _=1459348591420,
 *   src=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMC9Eb3dubG9hZA,
 * }
 * Example 2, copy /Music from standard volume to paste crypto volume
 * {
 * targets[]=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMC9NdXNpYw,
 * suffix=~,
 * _=1459527851332,
 * src=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMA,
 * cmd=paste,
 * dst=c0_Lw,
 * queryParameterStrings=cmd=paste
 *   &dst=c0_Lw
 *   &targets%5B%5D=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMC9NdXNpYw
 *   &cut=0
 *   &src=l0_L3N0b3JhZ2UvZW11bGF0ZWQvMA
 *   &suffix=~
 *   &_=1459527851332,
 * cut=0
 * }
 *
 * </pre>
 */
public class CmdPaste {

    public static ByteArrayInputStream go(Map<String, String> params) {


        String httpIpPort = params.get("url");
        OmniFile dst = new OmniFile( params.get("dst"));

        boolean cut = false;
        if( params.containsKey("cut"))
            cut = params.get("cut").contentEquals("1");

        String suffix = params.get("suffix");
        JSONArray added = new JSONArray();
        JSONArray removed = new JSONArray();

        /**
         * Params only has the first element of the targets[] array.
         * This is fine if there is only one target but an issue for multiple file operations.
         * Manually parse the query parameter strings to get all targets.
         */
        ArrayList<String> sourceFiles = new ArrayList<>();
        String[] qps = params.get("queryParameterStrings").split("&");

        for(String candidate : qps){

            if( candidate.contains("targets")){
                String[] parts = candidate.split("=");
                sourceFiles.add(parts[1]);
            }
        }

        /**
         * Iterate over the source files and copy each to the destination folder.
         * Files can be single files or directories and cut/copy/paste works across
         * different volume types, clear to encrypted, etc.
         * If the cut flag is set also delete each file.
         */
        for( int i = 0; i < sourceFiles.size(); i++){

            OmniFile fromFile = new OmniFile( sourceFiles.get(i));
            String toPath = dst.getPath()+"/"+fromFile.getName();
            OmniFile toFile = null;
            for( int dupCount = 0; dupCount < 10; dupCount++){ // add no more than 10 tilda
                toFile = new OmniFile( dst.getVolumeId(), toPath);

                if( ! toFile.exists())
                    break;

                // For duplicates, add ~ to filename, keep extension
                String extension = FilenameUtils.getExtension( toPath);
                if( ! extension.isEmpty())
                    extension = "."+extension;
                toPath = FilenameUtils.removeExtension( toPath) + suffix;
                toPath = toPath + extension;
            }
            boolean success = true;
            if( fromFile.isDirectory())
                success = OmniFiles.copyDirectory(fromFile, toFile);
            else
                success = OmniFiles.copyFile(fromFile, toFile);

            if( success )
                added.put( FileObj.makeObj(toFile, httpIpPort));// note: full depth of directory not added

            if( success && cut){
                if( fromFile.delete()){
                    removed.put(FileObj.makeObj(fromFile, httpIpPort));
                }else{
                    LogUtil.log(LogUtil.LogType.CMD_PASTE, "File delete failure: "+ fromFile.getPath());
                }
            }
        }
        JSONObject wrapper = new JSONObject();

        try {
            wrapper.put("added", added);
            wrapper.put("removed", removed);

            return new ByteArrayInputStream(wrapper.toString().getBytes("UTF-8"));

        } catch (JSONException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return null;
    }
}

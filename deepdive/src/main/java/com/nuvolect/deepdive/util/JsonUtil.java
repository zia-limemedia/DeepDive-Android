/*
 * Copyright (c) 2018 Nuvolect LLC.
 * This software is offered for free under conditions of the GPLv3 open source software license.
 * Contact Nuvolect LLC for a less restrictive commercial license if you would like to use the software
 * without the GPLv3 restrictions.
 */

package com.nuvolect.deepdive.util;//


import org.json.JSONException;
import org.json.JSONObject;


/**
 * String and JSON utilities.
 */
public class JsonUtil {
    /**
     * Fetch a long from a JSON object.
     * JSON conversion to a long is lossy in the few most upper bits because it passes the long
     * through floating point double. This method first extracts it to a string and then uses
     * a Long utility to convert it to a long, and is lossless.
     * @param key
     * @param longObj
     * @return
     * @throws JSONException
     */
    public static long getLong( String key, JSONObject longObj) throws JSONException {

        long answer = 0;

        String stringObj = longObj.getString( key );
        answer = Long.parseLong(stringObj);

        return answer;
    }
}

package com.nuvolect.deepdive.probe;

import android.support.annotation.NonNull;

import com.nuvolect.deepdive.ddUtil.LogUtil;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;

    public class ProgressStream extends OutputStream {

        private JSONArray m_array;

        public ProgressStream() {

            synchronized (ProgressStream.class) {

                m_array = new JSONArray();
            }
        }

        public void init(){

            synchronized (ProgressStream.class) {

                m_array = new JSONArray();
            }
        }

        /**
         * Get the stream and return it in reverse order, the newest entry is
         * at [0] or at the top.
         * @return
         */
        public JSONArray getStream(){

            synchronized (ProgressStream.class){

                JSONArray arrayCopy = new JSONArray();
                try {
                    for( int i=m_array.length()-1; i>=0; i--)
                        arrayCopy.put( m_array.get(i));

                } catch (JSONException e) {
                    e.printStackTrace();
                }
                m_array = new JSONArray();
                return arrayCopy;
            }
        }

        public void putStream(String addString){

            synchronized (ProgressStream.class) {

                m_array.put(addString);
            }
        }

        public void write(@NonNull byte[] data, int i1, int i2) {
            String str = new String(data).trim();
            str = str.replace("\n", "").replace("\r", "");
            str = str.replace("INFO:", "").replace("ERROR:", "").replace("WARN:","");
            str = str.replace("\n\r", "");
            str = str.replace("... done", "").replace("at","");
            str = str.trim();
            if (!str.equals("")) {
                LogUtil.log(LogUtil.LogType.DECOMPILE, str);

                putStream( str);

//                Log.i("PS",str);
//                broadcastStatus("progress_stream", str);mkk
            }
        }

        @Override
        public void write(int arg0) throws IOException {
        }
    }
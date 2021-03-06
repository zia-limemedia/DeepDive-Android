/*
 * Copyright (c) 2018 Nuvolect LLC.
 * This software is offered for free under conditions of the GPLv3 open source software license.
 * Contact Nuvolect LLC for a less restrictive commercial license if you would like to use the software
 * without the GPLv3 restrictions.
 */

package com.nuvolect.deepdive.show;//

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.nuvolect.deepdive.util.ActionBarUtil;
import com.nuvolect.deepdive.util.MyWebViewClient;
import com.nuvolect.deepdive.util.MyWebViewFragment;
import com.nuvolect.deepdive.webserver.WebUtil;

public class ShowItemWebFragment extends MyWebViewFragment {

    private static String m_item_url;
    private static final String SHOW_ITEM_FILE = "/show-item.htm";
    private static Activity m_act;


    static ShowItemWebFragment newInstance(String item_url){

        m_item_url = item_url;

        return new ShowItemWebFragment();
    }

    @Override
    public void onResume() {
        super.onResume();

        ActionBarUtil.hide(getActivity());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        m_act = getActivity();

        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View result=super.onCreateView(inflater, container, savedInstanceState);

        final WebView wv = getWebView();
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
//        settings.setAllowFileAccess(true);
//        settings.setAllowContentAccess(true);

        wv.setWebChromeClient( new WebChromeClient(){


        });

        wv.setWebViewClient(
                new MyWebViewClient(getActivity())
                {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {

                        view.loadUrl( url );
                        return true;
                    }

                    @Override
                    public void onLoadResource(WebView view, String url) {
                    }

                    public void onPageFinished(WebView view, String url){

                        wv.loadUrl("javascript:init('" + m_item_url + "')");
                    }
                }
        );

        String url = WebUtil.getAssetsFileUrl(getActivity(), SHOW_ITEM_FILE);

        wv.loadUrl(url);

        return(result);
    }

}

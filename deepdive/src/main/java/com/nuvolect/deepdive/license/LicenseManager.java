package com.nuvolect.deepdive.license;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import com.nuvolect.deepdive.BuildConfig;
import com.nuvolect.deepdive.main.CConst;
import com.nuvolect.deepdive.util.LogUtil;

import java.util.Date;


/**
 * Class to manage the license related activities of startup.
 * In the onCreate method of each entry class of the app call LicenseManager with
 * a listener to get the LicenseResult.
 * <pre>
 * Startup process:
 *
 * 1. Test for first time startup, if so
 * 1.a Prompt for concurrence with terms and conditions, LicenseResult.REJECT_TERMS
 *
 * 2 Confirm app version has not expired, note below. LicenseResult.APP_EXPIRED
 *
 * 3 Check for whitelist user, LicenseResult.WHITELIST_USER
 *
 * 4.a Check for pro user, license not expired, LicenseResult.PRO_USER
 * 4.b Check for pro user, license expired, LicenseResult.PRO_USER_EXPIRED
 *      The user will always be a pro user but when license period expires
 *      the user will lose nearly all pro privileges.
 *
 * 5 User not white_list or pro user is an appreciated user, LicenseResult.APPRECIATED_USER
 *
 * - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
 * About LicenseResult.APP_EXPIRED
 *
 * The purpose is to defeat rogue and outdated versions of the app.
 *
 * Many illicit copies of the app are made and distributed and even sold outside
 * of Google Play and outside of the control of Nuvolect. This app is not open source
 * and is not free, it is for-profit and illicit copies can interfere with Nuvolect's
 * rights and business models.
 *
 * A hard-date will be used to expire a version of the app.
 *
 * The user will be notified within 30 days of expiring:
 *   "This app is getting old and requires update by: mm/dd/yyyy"
 *
 * When the app expires a dialog is shown requesting the user to upgrade.
 *
 * Each version of the app published has an absolute expire date.
 * - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
 *</pre>
 */
public class LicenseManager {

    private final boolean DEBUG = LogUtil.DEBUG;

    /**
     * Set the user as a pro user.
     * Set the current time as when user upgrade.
     *
     * @param ctx
     */
    public static void upgradeLicense(Context ctx) {

        LicensePersist.setIsProUser(ctx, true);
        LicensePersist.setProUserUpgradeTime(ctx);
    }

    /**
     * License type is saved in the ordinal position.
     */
    public enum LicenseResult { NIL,
        REJECTED_TERMS,
        APP_EXPIRED,
        WHITELIST_USER,
        PRO_USER,
        PRO_USER_EXPIRED,  // Read access external storage, otherwise same as APPERCIATED_USER
        APPRECIATED_USER,
    }

    private Activity m_act;
    private static LicenseManager sInstance;

    public static boolean mIsWhitelistUser;
    public static boolean mIsProUser;
    public static boolean mIsProUserExpired;

    /** Short description of current license for the Settings page */
    public String mLicenseSummary = "";

    private LicenseCallbacks mListener;
    AlertDialog dialog_alert = null;
    /**
     * Manage the class as a singleton.
     * @param context
     * @return
     */
    public static LicenseManager getInstance(Context context) {
        if (sInstance == null) {
            //Always pass in the Application Context
            sInstance = new LicenseManager(context.getApplicationContext());

            mIsWhitelistUser = false;// is also a pro user
            mIsProUser = false;
            mIsProUserExpired = false;
        }
        return sInstance;
    }

    private LicenseManager(Context context) {
    }

    /**
     * A callback interface that all activities containing this class must implement.
     */
    public interface LicenseCallbacks {

        public void licenseResult(LicenseResult licenseResult);
    }

    public void checkLicense(Activity act, LicenseCallbacks listener){
        if(DEBUG)LogUtil.log( "LicenseManager: step_0");

        m_act = act;
        mListener = listener;

        step_1a_check_concurrence_with_terms();
    }

    private void step_1a_check_concurrence_with_terms() {
        if(DEBUG)LogUtil.log( "LicenseManager: step_1a_check_concurrence_with_terms");

        if( LicensePersist.getLegalAgree(m_act)){

            step_2_confirm_version_not_expired();

        }else{

            String message = "By using this application you agree to "+AppSpecific.TOC_HREF_URL
                    +" and "+AppSpecific.PP_HREF_URL;

            AlertDialog.Builder builder = new AlertDialog.Builder(m_act);
            builder.setTitle("Please confirm Terms and Conditions and Privacy Policy");
            builder.setMessage( Html.fromHtml(message));
            builder.setCancelable(false);
            builder.setIcon(AppSpecific.SMALL_ICON);

            builder.setPositiveButton("I Agree", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int labelIndex) {

                    LicensePersist.setLegalAgree(m_act, true);

                    step_2_confirm_version_not_expired();
                }

            });
            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {

                    mListener.licenseResult( LicenseResult.REJECTED_TERMS);
                    dialog_alert.cancel();
                    // All done here, calling class will take over with returned result
                }
            });
            dialog_alert = builder.create();
            dialog_alert.show();

            // Activate the HTML
            TextView tv = ((TextView) dialog_alert.findViewById(android.R.id.message));
            tv.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }
    void step_2_confirm_version_not_expired(){

        Date buildDate = new Date(BuildConfig.BUILD_TIMESTAMP);
        long appBuildTimeDate = buildDate.getTime();
        long timeAppExpires = appBuildTimeDate + CConst.APP_VALID_DURATION;

        if( System.currentTimeMillis() < timeAppExpires){

            step_3_check_for_whitelist_user(); // app is still valid
        }else{

            mLicenseSummary = "App version expired";
            mListener.licenseResult(LicenseResult.APP_EXPIRED);
            // All done here, calling class will take over with returned result
        }
    }

    void step_3_check_for_whitelist_user(){
        if(DEBUG)LogUtil.log( "LicenseManager: step_3_check_for_whitelist_user");

        String whiteListAccount = Whitelist.getWhiteListCredentials(m_act);

        if( ! whiteListAccount.isEmpty()) {

            mIsProUser = true;
            mIsWhitelistUser = true;
            mLicenseSummary = "Whitelist user: " +whiteListAccount;
            mListener.licenseResult( LicenseResult.WHITELIST_USER);
            // All done here, calling class will take over with returned result
        }else{

            step_4_check_for_pro_user_license_not_expired();
        }
    }

    void step_4_check_for_pro_user_license_not_expired(){

        if (DEBUG) LogUtil.log("LicenseManager: step_4_check_for_pro_user");

        if( LicensePersist.isProUser(m_act)) {

            long timeLastProUpgrade = LicensePersist.getProUserUpgradeTime( m_act);
            long timeProExpires = timeLastProUpgrade + CConst.PRO_LICENSE_DURATION;

            if( timeProExpires < System.currentTimeMillis()){

                mIsProUser = true;
                mLicenseSummary = "Pro user";
                mListener.licenseResult(LicenseResult.PRO_USER);
                // All done here, calling class will take over with returned result
            }else{

                mIsProUserExpired = true;
                mLicenseSummary = "Pro user, license expired";
                mListener.licenseResult(LicenseResult.PRO_USER_EXPIRED);
                // All done here, calling class will take over with returned result
            }

        }else{
            step_5_user_is_appreciated_user();
        }
    }

    private void step_5_user_is_appreciated_user() {

        mLicenseSummary = "Appreciated user";
        mListener.licenseResult(LicenseResult.APPRECIATED_USER);
        // All done here, calling class will take over with returned result
    }
}

/***************************************************************************************
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import com.ichi2.async.Connection;
import com.ichi2.async.Connection.Payload;
import com.tomgibara.android.veecheck.util.PrefSettings;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class Feedback extends Activity {
    protected static String REPORT_ASK = "2";
    protected static String REPORT_NEVER = "1";
    protected static String REPORT_ALWAYS = "0";

    public static String STATE_WAITING = "0";
    public static String STATE_UPLOADING = "1";
    public static String STATE_SUCCESSFUL = "2";
    public static String STATE_FAILED = "3";

    public static String TYPE_STACKTRACE = "crash-stacktrace";
    public static String TYPE_FEEDBACK = "feedback";
    public static String TYPE_ERROR_FEEDBACK = "error-feedback";
    public static String TYPE_OTHER_ERROR = "other-error";
	
    protected static SimpleDateFormat df1 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
    protected static SimpleDateFormat df2 = new SimpleDateFormat("Z", Locale.US);
    protected static TimeZone localTz = TimeZone.getDefault();

	// This is used to group the batch of bugs and notes sent on the server side
	protected long mNonce;
	
	protected List<HashMap<String, String>> mErrorReports;
    protected SimpleAdapter mErrorAdapter;
    protected ListView mLvErrorList;
    protected EditText mEtFeedbackText;
    protected boolean mPostingFeedback;
    protected InputMethodManager mImm = null;
    protected AlertDialog mNoConnectionAlert = null;

    
    protected String mFeedbackUrl; 
    protected String mErrorUrl; 
    
    @Override
    public void onBackPressed() {
        deleteFiles(true, false);
        setResult(RESULT_OK);
        finish();
    }
    
    /**
     * Create AlertDialogs used on all the activity
     */
    private void initAllAlertDialogs() {
        Resources res = getResources();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle(res.getString(R.string.connection_error_title));
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setMessage(res.getString(R.string.connection_needed));
        builder.setPositiveButton(res.getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mPostingFeedback = false;
                refreshInterface();
            }
        });
        mNoConnectionAlert = builder.create();
    }

    private void refreshInterface() {
        Resources res = getResources();
        Button btnSend = (Button) findViewById(R.id.btnFeedbackSend);
        Button btnKeepLatest = (Button) findViewById(R.id.btnFeedbackKeepLatest);
        Button btnClearAll = (Button) findViewById(R.id.btnFeedbackClearAll);
        ProgressBar pbSpinner = (ProgressBar) findViewById(R.id.pbFeedbackSpinner);

        int numErrors = mErrorReports.size();
        if (numErrors == 0) {
            mLvErrorList.setVisibility(View.GONE);
            btnKeepLatest.setVisibility(View.GONE);
            btnClearAll.setVisibility(View.GONE);
            btnSend.setText(res.getString(R.string.feedback_send_feedback));
        } else {
            mLvErrorList.setVisibility(View.VISIBLE);
            btnKeepLatest.setVisibility(View.VISIBLE);
            btnClearAll.setVisibility(View.VISIBLE);
            btnSend.setText(res.getString(R.string.feedback_send_feedback_and_errors));
            refreshErrorListView();
            if (numErrors == 1) {
                btnKeepLatest.setEnabled(false);
            } else {
                btnKeepLatest.setEnabled(true);
            }
        }
        
        if (mPostingFeedback) {
            int buttonHeight = btnSend.getHeight();
            btnSend.setVisibility(View.GONE);
            pbSpinner.setVisibility(View.VISIBLE);
            LinearLayout topLine = (LinearLayout) findViewById(R.id.llFeedbackTopLine);
            topLine.setMinimumHeight(buttonHeight);

            mEtFeedbackText.setEnabled(false);
            mImm.hideSoftInputFromWindow(mEtFeedbackText.getWindowToken(), 0);
        } else {
            btnSend.setVisibility(View.VISIBLE);
            pbSpinner.setVisibility(View.GONE);
            mEtFeedbackText.setEnabled(true);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Resources res = getResources();

        Context context = getBaseContext();
        SharedPreferences sharedPreferences = PrefSettings.getSharedPrefs(context);
        String reportErrorMode = sharedPreferences.getString("reportErrorMode", REPORT_ASK);
        
        mNonce = UUID.randomUUID().getMostSignificantBits();
        mFeedbackUrl = res.getString(R.string.feedback_post_url);
        mErrorUrl = res.getString(R.string.error_post_url);
        mImm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        mPostingFeedback = false;
        initAllAlertDialogs();

        getErrorFiles();
        if (reportErrorMode.equals(REPORT_ALWAYS)) { // Always report
            try {
                //sendErrorReports();
            } catch (Exception e) {
                Log.e(AnkiDroidApp.TAG, e.toString());
            }

            deleteFiles(true, false);
            //setResult(RESULT_OK);
            //finish();

            return;
        } else if (reportErrorMode.equals(REPORT_NEVER)) { // Never report
            deleteFiles(false, false);
            //setResult(RESULT_OK);
            //finish();
        }

        setContentView(R.layout.feedback);

        Button btnSend = (Button) findViewById(R.id.btnFeedbackSend);
        Button btnKeepLatest = (Button) findViewById(R.id.btnFeedbackKeepLatest);
        Button btnClearAll = (Button) findViewById(R.id.btnFeedbackClearAll);
        mEtFeedbackText = (EditText) findViewById(R.id.etFeedbackText);
        mLvErrorList = (ListView) findViewById(R.id.lvFeedbackErrorList);

        mErrorAdapter = new SimpleAdapter(this, mErrorReports,
                R.layout.error_item, new String[] {"name", "state", "result"}, new int[] {
                    R.id.error_item_text, R.id.error_item_progress, R.id.error_item_status});
        mErrorAdapter.setViewBinder(new SimpleAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Object arg1, String text) {
                switch(view.getId()) {
                    case R.id.error_item_progress:
                        if (text.equals(STATE_UPLOADING)) {
                            view.setVisibility(View.VISIBLE);
                        } else {
                            view.setVisibility(View.GONE);
                        }
                        return true;
                    case R.id.error_item_status:
                        if (text.length() == 0) {
                            view.setVisibility(View.GONE);
                            return true;
                        } else {
                            view.setVisibility(View.VISIBLE);
                            return false;
                        }
                }
                return false;
            }
        });

        btnClearAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteFiles(false, false);
                refreshErrorListView();
                refreshInterface();
            }
        });

        mLvErrorList.setAdapter(mErrorAdapter);

        btnSend.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mPostingFeedback) {
                    String feedback = mEtFeedbackText.getText().toString();
                    Connection.sendFeedback(mSendListener, new Payload(new Object[] {
                            mFeedbackUrl, mErrorUrl, feedback, mErrorReports, mNonce, getApplication()}));
                    if (mErrorReports.size() > 0) {
                        mPostingFeedback = true;
                    }
                    if (feedback.length() > 0) {
                        mPostingFeedback = true;
                    }
                    refreshInterface();
                }
            }
        });

        btnKeepLatest.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteFiles(false, true);
                refreshErrorListView();
                refreshInterface();
            }
        });

        refreshInterface();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        
    }

    private void refreshErrorListView() {
        mErrorAdapter.notifyDataSetChanged();
    }
    
    private void getErrorFiles() {
        mErrorReports = new ArrayList<HashMap<String, String>>();
        String[] errors = fileList();

        for (String file : errors) {
            if (file.endsWith(".stacktrace")) {
                HashMap<String, String> error = new HashMap<String, String>();
                error.put("filename", file);
                error.put("name", file);
                error.put("state", STATE_WAITING);
                error.put("result", "");
                mErrorReports.add(error);
            }
        }
    }

    private void deleteFiles(boolean onlyProcessed, boolean keepLatest) {
        
        for (int i = (keepLatest? 1: 0); i < mErrorReports.size(); ) {
            try {
                String errorState = mErrorReports.get(i).get("state");
                if (!onlyProcessed || errorState.equals(STATE_SUCCESSFUL)) {
                    deleteFile(mErrorReports.get(i).get("filename"));
                    mErrorReports.remove(i);
                } else {
                    i++;
                }
            } catch (Exception e) {
                Log.e(AnkiDroidApp.TAG, String.format("Could not delete file: %s", mErrorReports.get(i)));
            }
        }
    }

    public static boolean isErrorType(String postType) {
        return !(postType.equals(TYPE_FEEDBACK) || postType.equals(TYPE_ERROR_FEEDBACK));
    }

    Connection.TaskListener mSendListener = new Connection.TaskListener() {

        @Override
        public void onDisconnected() {
            if (mNoConnectionAlert != null) {
                mNoConnectionAlert.show();
            }
        }

        @Override
        public void onPostExecute(Payload data) {
            mPostingFeedback = false;
            refreshInterface();
        }

        @Override
        public void onPreExecute() {
            // pass
        }

        @Override
        public void onProgressUpdate(Object... values) {
            Resources res = getResources();
            
            String postType = (String)values[0];
            int errorIndex = (Integer)values[1];
            String state = (String)values[2];

            if (isErrorType(postType)) {
                mErrorReports.get(errorIndex).put("state", state);
                if (!state.equals(Feedback.STATE_UPLOADING)) {
                    int returnCode = (Integer)values[3];
                    if (returnCode == 200) {
                        // The result is either: "new" (for first encountered bug), "known" (for existing bugs) or
                        // ("issue:xxx:<status>" for known and linked)
                        String result = (String)values[4];
                        if (result.equalsIgnoreCase("new")) {
                            mErrorReports.get(errorIndex).put("name", res.getString(R.string.feedback_error_reply_new));
                        } else if (result.equalsIgnoreCase("known")) {
                            mErrorReports.get(errorIndex).put("name", res.getString(R.string.feedback_error_reply_known));
                        } else if (result.startsWith("issue:")) {
                            String[] resultPieces = result.split(":");
                            int issue = Integer.parseInt(resultPieces[1]);
                            String status = "";
                            if (resultPieces.length > 1) {
                                if (resultPieces.length > 2) {
                                    status = resultPieces[2];
                                }
                                if (status.length() == 0) {
                                    mErrorReports.get(errorIndex).put("name",
                                            res.getString(R.string.feedback_error_reply_issue_unknown, issue));
                                } else if (status.equalsIgnoreCase("fixed")) {
                                    mErrorReports.get(errorIndex).put("name",
                                            res.getString(R.string.feedback_error_reply_issue_fixed_prod, issue));
                                } else if (status.equalsIgnoreCase("fixedindev")) {
                                    mErrorReports.get(errorIndex).put("name",
                                            res.getString(R.string.feedback_error_reply_issue_fixed_dev, issue));
                                } else {
                                    mErrorReports.get(errorIndex).put("name",
                                            res.getString(R.string.feedback_error_reply_issue_status, issue, status));
                                }
                            } else {
                                mErrorReports.get(errorIndex).put("result",
                                        res.getString(R.string.feedback_error_reply_malformed));
                            }
                        } else {
                            mErrorReports.get(errorIndex).put("result",
                                    res.getString(R.string.feedback_error_reply_malformed));
                        }
                    } else {
                        mErrorReports.get(errorIndex).put("result", res.getString(R.string.feedback_error_reply_failed));
                    }
                }
                refreshErrorListView();
            } else {
                if (state.equals(STATE_SUCCESSFUL)) {
                    mEtFeedbackText.setText("");
                    Toast.makeText(Feedback.this,
                            res.getString(R.string.feedback_message_sent_success), Toast.LENGTH_LONG).show();
                } else if (state.equals(STATE_FAILED)) {
                    int respCode = (Integer)values[3];
                    if (respCode == 0) {
                        onDisconnected();
                    } else {
                        Toast.makeText(Feedback.this, res.getString(R.string.feedback_message_sent_failure, respCode),
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
    };


    // Run in AsyncTask

    private static void addTimestamp(List<NameValuePair> pairs) {
        Date ts = new Date();
        df1.setTimeZone(TimeZone.getTimeZone("UTC"));
        
        String reportsentutc = String.format("%s", df1.format(ts));
        String reportsenttzoffset = String.format("%s", df2.format(ts));
        String reportsenttz = String.format("%s", localTz.getID());

        pairs.add(new BasicNameValuePair("reportsentutc", reportsentutc));
        pairs.add(new BasicNameValuePair("reportsenttzoffset", reportsenttzoffset));
        pairs.add(new BasicNameValuePair("reportsenttz", reportsenttz));
    }

    private static List<NameValuePair> extractPairsFromError(String type, String errorFile, String groupId, int index, Application app) {
        List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        
        pairs.add(new BasicNameValuePair("type", "crash-stacktrace"));
        pairs.add(new BasicNameValuePair("groupid", groupId));
        pairs.add(new BasicNameValuePair("index", String.valueOf(index)));
        addTimestamp(pairs);

        String singleLine = null;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(app.openFileInput(errorFile)));
            while((singleLine = br.readLine()) != null) {
                int indexOfEquals = singleLine.indexOf('=');
                
                if(indexOfEquals==-1)
                    continue;
                
                String key = singleLine.substring(0, indexOfEquals).toLowerCase();
                String value = singleLine.substring(indexOfEquals+1,singleLine.length());
                
                if(key.equals("stacktrace")) {
                    StringBuilder sb = new StringBuilder(value);
                    
                    while((singleLine = br.readLine()) != null) {
                        sb.append(singleLine);
                        sb.append("\n");
                    }
                    
                    value = sb.toString();
                }
                pairs.add(new BasicNameValuePair(key, value));
            }
            br.close();
        } catch (FileNotFoundException e) {
            Log.w(AnkiDroidApp.TAG, "Couldn't open crash report " + errorFile);
            return null;
        } catch (IOException e) {
            Log.w(AnkiDroidApp.TAG, "Couldn't read crash report " + errorFile);
            return null;
        }
        
        return pairs;
    }
    
    /**
     * Posting feedback or error info to the server.
     * This is called from the AsyncTask.
     * @param url The url to post the feedback to.
     * @param type The type of the info, eg Feedback.TYPE_CRASH_STACKTRACE.
     * @param feedback For feedback types this is the message. For error/crash types this is the path to the error file. 
     * @param groupId A single time generated ID, so that errors/feedback send together can be grouped together.
     * @param index The index of the error in the list
     * @return A Payload file showing success, response code and response message.
     */
    public static Payload postFeedback(String url, String type, String feedback, String groupId, int index, Application app) {
        Payload result = new Payload(null);

        List<NameValuePair> pairs = null;
        if (!isErrorType(type)) { 
            pairs = new ArrayList<NameValuePair>();
            pairs.add(new BasicNameValuePair("type", type));
            pairs.add(new BasicNameValuePair("groupid", groupId));
            pairs.add(new BasicNameValuePair("index", "0"));
            pairs.add(new BasicNameValuePair("message", feedback));
            addTimestamp(pairs);
        } else {
            pairs = Feedback.extractPairsFromError(type, feedback, groupId, index, app);
            if (pairs == null) {
                result.success = false;
                result.result = null;
            }
        }

        HttpClient httpClient = new DefaultHttpClient();
        HttpPost httpPost = new HttpPost(url);
        httpPost.addHeader("User-Agent", "AnkiDroid");
        try {
            httpPost.setEntity(new UrlEncodedFormEntity(pairs));
            HttpResponse response = httpClient.execute(httpPost);
            Log.e(AnkiDroidApp.TAG, String.format("Bug report posted to %s", url));

            int respCode = response.getStatusLine().getStatusCode();
            switch(respCode) {
                case 200:
                    result.success = true;
                    result.returnType = respCode;
                    result.result = Utils.convertStreamToString(response.getEntity().getContent());
                    Log.i(AnkiDroidApp.TAG, String.format("postFeedback OK: %s", result.result));
                    break;

                default:
                    Log.e(AnkiDroidApp.TAG, String.format("postFeedback failure: %d - %s",
                                response.getStatusLine().getStatusCode(),
                                response.getStatusLine().getReasonPhrase()));
                    result.success = false;
                    result.returnType = respCode;
                    result.result = response.getStatusLine().getReasonPhrase();
                    break;
            }
        } catch (ClientProtocolException ex) {
            Log.e(AnkiDroidApp.TAG, "ClientProtocolException: " + ex.toString());
            result.success = false;
            result.result = new String(ex.toString());
        } catch (IOException ex) {
            Log.e(AnkiDroidApp.TAG, "IOException: " + ex.toString());
            result.success = false;
            result.result = new String(ex.toString());
        }
        return result;
    }
    
}


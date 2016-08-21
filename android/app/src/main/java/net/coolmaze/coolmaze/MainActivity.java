package net.coolmaze.coolmaze;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.ProgressBar;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import com.loopj.android.http.*;
import org.json.JSONException;
import org.json.JSONObject;
import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.InputStreamEntity;
import cz.msebera.android.httpclient.message.BasicHeader;

public class MainActivity extends AppCompatActivity {

    static final String FRONTPAGE_DOMAIN = "coolmaze.net";
    //static final String FRONTPAGE_DOMAIN = "coolmaze.io";   maybe later
    static final String FRONTPAGE_URL = "https://" + FRONTPAGE_DOMAIN;
    static final String BACKEND_URL = "https://cool-maze.appspot.com";
    // static final String BACKEND_URL = "https://dev-dot-cool-maze.appspot.com";

    static final String SCAN_INVITE = "Open " + FRONTPAGE_DOMAIN + " on target computer and scan it!";
    static final AsyncHttpResponseHandler blackhole = new BlackholeHttpResponseHandler();

    private String messageToSignal = "<?>";
    private String qrKeyToSignal = "<?>";

    // Scanning and Uploading occur concurrently, they need synchronization.
    private Object workflowLock = new Object();
    private boolean finishedScanning = false;
    private boolean finishedUploading = false;

    // "Hold on while I'm requesting permissions". Then, use the intent in the onRequestPermissionsResult callback.
    private Intent holdOnIntent;

    boolean checkPermissions() {
        // Cool-Maze can't work at all without acces to Camera.
        // Also, it (currently) needs READ_EXTERNAL_STORAGE when sending a file.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Version >= 23
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                return false;
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    void requestPermissions(){
        // Cool-Maze can't work at all without acces to Camera.
        // Also, it (currently) needs READ_EXTERNAL_STORAGE when sending a file.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int requestCode = 0; //??
            requestPermissions( new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
            }, requestCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if ( checkPermissions() )
            scanAndSend(holdOnIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Get intent, action and MIME type
        Intent intent = getIntent();
        Log.i("CoolMazeEvent", "onResume(): Intent="+intent);
        if ( intent == null )
            return;

        if ( !Intent.ACTION_SEND.equals(intent.getAction()) )
            return;  // MAIN, etc.

        if ( !isOnline() ){
            showError("No internet access found.");
            return;
        }

        wakeupBackend();

        if (!checkPermissions()){
            holdOnIntent = intent;
            requestPermissions();
            return;
        }

        scanAndSend(intent);
        // "consume the intent" so it won't be processed again
        setIntent(null);
    }

    protected void scanAndSend(Intent intent) {
        String type = intent.getType();
        String[] typeParts = type.split("/");
        String typeCat = typeParts[0];
        switch (typeCat) {
            case "text":
                // Short text, URL, etc. are sent directly to the broker
                // (no need to upload a file)
                messageToSignal = intent.getStringExtra(Intent.EXTRA_TEXT);
                finishedScanning = false;
                finishedUploading = true;
                new IntentIntegrator(MainActivity.this)
                        //.setOrientationLocked(false)
                        .addExtra("PROMPT_MESSAGE", SCAN_INVITE)
                        .initiateScan();
                return;
            case "image":
            case "video":
            case "audio":
            case "application":
                // 1) We request a pair of upload/download URLs from the backend
                // 2) We upload the file
                // 3) We send the download URL to the broker
                Uri localFileUri = intent.getData();
                if ( localFileUri == null ) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                        ClipData clip = intent.getClipData();
                        if ( clip.getItemCount() == 0 ) {
                            Log.e("CoolMazeSignal", "ClipData having 0 item :(");
                            return;
                    }
                        ClipData.Item item = clip.getItemAt(0);
                        localFileUri = item.getUri();
                    }else{
                        Log.e("CoolMazeSignal", "Intent.getClipData() needs at least JELLY_BEAN :(");
                        return;
                    }
                }
                Log.w("CoolMazeSignal", "Initiating upload of " + localFileUri + " ...");
                setContentView(R.layout.activity_main);
                showSpinning();
                showCaption("Uploading...");

                finishedScanning = false;
                finishedUploading = false;
                String mimeType = extractMimeType(intent, localFileUri);
                gentleUploadStep1(localFileUri, mimeType);
                return;
            default:
                // TODO other types of files?
                Log.w("CoolMazeSignal", "Intent type is " + intent.getType());
                return;
        }
    }

    String extractMimeType(Intent intent, Uri localFileUri) {
        // Trying strategies that work in most cases: photos, videos, pdf, etc,
        // should work fine for "file://..." and "content://..." as well.
        String mimeType = intent.getType();
        if ( mimeType==null || "".equals(mimeType) || mimeType.endsWith("/*") ){
            //String resolved = MimeTypeMap.getSingleton().getExtensionFromMimeType(getContentResolver().getType(localFileUri));
            String resolved = getContentResolver().getType(localFileUri);
            if ( resolved!=null )
                mimeType = resolved;
            else {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(localFileUri.toString());
                resolved = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.toLowerCase());
                if ( resolved!=null )
                    mimeType = resolved;
            }
        }
        // TODO: what about application/txt ?
        // Are all video formats properly handled?
        // Should we default image/*, video/* to some more specific value?
        return mimeType;
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i("CoolMazeEvent", "onActivityResult(" + requestCode + ", " + resultCode + ", " + data + ")");
        //
        // User returns from the "Scan QR-code with camera" intent.
        //

        if (resultCode == RESULT_CANCELED) {
            // User was on the Scan screen, but hit her Back button or similar
            Log.i("CoolMazeSignal", "Scan was canceled by user");
            finish();
            return;
        }

        if (!isOnline()) {
            showError("No internet access found.");
            return;
        }

        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanResult == null) {
            Log.e("CoolMazeError", "IntentResult parsing by ZXing failed :(");
            return;
        }

        Log.i("CoolMazeSignal", "IntentResult successfully parsed by ZXing");
        qrKeyToSignal = scanResult.getContents();

        if (!isValidQrKey(qrKeyToSignal)) {
            setContentView(R.layout.activity_main);
            showError("Please open webpage coolmaze.net on your computer and scan its QR-code.");
            finish();
            // Try again
            /*
            new IntentIntegrator(MainActivity.this)
                    .addExtra("PROMPT_MESSAGE", SCAN_INVITE)
                    .initiateScan();
            */
            return;
        }

        // This short vibration means "Hey cool, you've just scanned something!"
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(100);

        boolean dispatchNow = false;
        synchronized (workflowLock) {
            finishedScanning = true;
            if (finishedUploading)
                dispatchNow = true;
        }
        if (dispatchNow)
            dispatch();
        else
            notifyScan();
    }

    private void notifyScan() {
        // This is a small request sent in the background. It shows nothing on the Android device screen.
        // It should however show some acknowledgement on the freshly scanned coolmaze.net browser tab.
        //
        // It is optional (workflow not broken if notif is lost, or not sent at all).
        // If the payload is a small piece of text, notifyScan() is not called.
        // If the upload is already complete before the scan is complete, notifyScan() is not called.

        RequestParams params = new RequestParams("qrKey", qrKeyToSignal);
        newAsyncHttpClient().post(
                BACKEND_URL + "/scanned",
                params,
                blackhole);

    }

    // Here "dispatch" means "send message to broker, for immediate delivery to target".
    // It is triggered either after Scan or after Upload, depending which one arrives last.
    //
    // In case the resource is a file, when dispatch is called the file is already completely
    // uploaded, and the message consists in the file download URL.
    void dispatch(){
        Log.i("CoolMazeSignal", "Sending to " + qrKeyToSignal + " message [" + messageToSignal + "]");
        if ( "<?>".equals(messageToSignal) ){
            showError("Unfortunately, we're experiencing bug #55. The message was not sent to the dispatch server.");
            return;
        }
        RequestParams params = new RequestParams("qrKey", qrKeyToSignal, "message", messageToSignal);
        // conn.setReadTimeout(15000);
        // conn.setConnectTimeout(15000);
        newAsyncHttpClient().post(
                BACKEND_URL + "/dispatch",
                params,
                new AsyncHttpResponseHandler() {
                    @Override
                    public void onSuccess(int statusCode, Header[] headers, byte[] response) {
                        Log.i("CoolMazeEvent", "sendMessage successful POST");
                        // This long vibration should mean "The target has received your message!",
                        // ...but it doesn't, yet.
                        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        v.vibrate(500);
                        showSuccess();
                        closeCountdown(2);
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, byte[] errorResponse, Throwable e) {
                        Log.e("CoolMazeEvent", "sendMessage POST request response code " + statusCode);
                        showError("Unfortunately, we could not send this message to the dispatch server.");
                    }
                });

        // Show some feedback on the screen
        setContentView(R.layout.activity_main);
        showSpinning();
        showCaption("Sending to target...");
    }

    boolean isValidQrKey(String s) {
        // A valid qrKey is a string encoded in a QR-code on page coolmaze.net .
        // Currently it is an int in range [0..99999].
        return s.matches("[0-9]{1,5}");
    }

    void showSpinning(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar progress = ((ProgressBar) findViewById(R.id.progressBar));
                if(progress!=null)
                    progress.setVisibility(View.VISIBLE);
                ImageView check = ((ImageView) findViewById(R.id.checkMark));
                if(check!=null)
                    check.setVisibility(View.INVISIBLE);
            }
        });
    }

    void showSuccess(){
        showCaption("Sent!");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar progress = ((ProgressBar) findViewById(R.id.progressBar));
                if(progress!=null)
                    progress.setVisibility(View.INVISIBLE);
                ImageView check = ((ImageView) findViewById(R.id.checkMark));
                if(check!=null)
                    check.setVisibility(View.VISIBLE);
            }
        });
    }

    void showCaption(final String title){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toolbar toolbar = ((Toolbar) findViewById(R.id.toolbar));
                toolbar.setTitle(title);
            }
        });
    }

    void closeCountdown(int seconds) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1000 * seconds);
    }

    private void gentleUploadStep1(final Uri localFileUri, final String mimeType) {
        newAsyncHttpClient().post(
                BACKEND_URL + "/new-gcs-urls",
                new RequestParams("type", mimeType),
                new JsonHttpResponseHandler() {
                    @Override
                    public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                        Log.i("CoolMazeEvent", "Signed URLs request success :) \n ");
                        try {
                            String urlPut = response.getString("urlPut");
                            String urlGet = response.getString("urlGet");
                            gentleUploadStep2(urlPut, urlGet, localFileUri, mimeType);
                        } catch (JSONException e) {
                            Log.e("CoolMazeSignal", "JSON signed URLs extract failed :( from " + response);
                        }
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                        showError("Unfortunately, we could not obtain secure upload URLs.");
                    }
                });

        // This begins *while uploading is still working*
        new IntentIntegrator(MainActivity.this)
                //.setOrientationLocked(false)
                .addExtra("PROMPT_MESSAGE", SCAN_INVITE)
                .initiateScan();
    }

    private void gentleUploadStep2(String resourcePutUrl, final String resourceGetUrl, Uri localFileUri, final String mimeType) {
        File localFile = new File(localFileUri.getPath());
        // FileEntity entity = new FileEntity(localFile);
        // Resource URIs like "content://..." don't work well as Files, better as InputStreams
        InputStream inputStream;
        try {
            inputStream = getContentResolver().openInputStream(localFileUri);
        } catch (FileNotFoundException e) {
            Log.e("CoolMazeSignal", "Not found :( " + e);
            return;
        }
        InputStreamEntity entity = new InputStreamEntity(inputStream);
        Context context = null; // ?

        Log.i("CoolMazeSignal", "Uploading resource " + resourcePutUrl.split("\\?")[0] );
        newAsyncHttpClient().put(
                context,
                resourcePutUrl,
                new Header[]{ new BasicHeader("Content-Type", mimeType) },
                entity,
                mimeType,
                new AsyncHttpResponseHandler() {
                    @Override
                    public void onSuccess(int statusCode, Header[] headers, byte[] response) {
                        Log.i("CoolMazeSignal", "Upload resource success :)");

                        // When the target desktop receives the URL, it immediately follows it
                        messageToSignal = resourceGetUrl;

                        boolean dispatchNow = false;
                        synchronized (workflowLock) {
                            finishedUploading = true;
                            if ( finishedScanning )
                                dispatchNow = true;
                        }
                        if(dispatchNow)
                            dispatch();
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, byte[] errorResponse, Throwable e) {
                        Log.e("CoolMazeSignal", "Upload resource failed :( " + e + " " + new String(errorResponse));
                        showError("Unfortunately, the upload failed.");
                    }
                });
    }

    private void wakeupBackend() {
        // Send a kind of custom warmup request to the backend.
        // Make it async and ignore the response.
        newAsyncHttpClient().get(BACKEND_URL + "/wakeup", blackhole);
    }

    private AsyncHttpClient newAsyncHttpClient(){
        // TODO: reuse them instead of instanciating each time...?
        AsyncHttpClient client = new AsyncHttpClient();
        client.addHeader("User-Agent", "Cool Maze android app");
        return client;
    }

    void showError(String message){
        Log.e("CoolMazeError", "Alert dialog [" + message + "]");
        new AlertDialog.Builder(this)
                .setTitle("Error :(")
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    // From http://stackoverflow.com/a/4009133/871134
    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }
}

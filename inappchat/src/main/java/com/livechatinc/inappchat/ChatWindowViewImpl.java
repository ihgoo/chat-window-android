package com.livechatinc.inappchat;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.livechatinc.inappchat.models.NewMessageModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Created by szymonjarosz on 19/07/2017.
 */

public class ChatWindowViewImpl extends FrameLayout implements ChatWindowView {
    private WebView webView;
    private ProgressBar progressBar;
    private ChatWindowEventsListener eventsListener;
    private static final int REQUEST_CODE_FILE_UPLOAD = 21354;
    private static final int REQUEST_CODE_AUDIO_PERMISSIONS = 89292;
    private ValueCallback<Uri> mUriUploadCallback;
    private ValueCallback<Uri[]> mUriArrayUploadCallback;
    private ChatWindowConfiguration config;
    private boolean initialized;
    private boolean chatUiReady = false;

    /**
     * 在chat组件显示时，避免错误信息反复回调给上级
     * 让错误仅此能触发一次，关闭chat组件或重新打开chat组件，错误flag会被还原
     */
    private boolean isErrorOccur = false;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private PermissionRequest webRequestPermissions;


    public ChatWindowViewImpl(@NonNull Context context) {
        super(context);
        initView(context);
    }

    public ChatWindowViewImpl(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initView(context);
    }

    private void onlyShowProgressBar(){
        progressBar.setVisibility(VISIBLE);
        webView.setVisibility(GONE);
    }

    private void onlyShowWebView(){
        progressBar.setVisibility(GONE);
        webView.setVisibility(VISIBLE);
    }

    private void initView(Context context) {
        setFitsSystemWindows(true);
        LayoutInflater.from(context).inflate(R.layout.view_chat_window_internal, this, true);
        webView = findViewById(R.id.chat_window_web_view);
        progressBar = findViewById(R.id.chat_window_progress);

        onlyShowProgressBar();

        if (Build.VERSION.RELEASE.matches("4\\.4(\\.[12])?")) {
            String userAgentString = webView.getSettings().getUserAgentString();
            webView.getSettings().setUserAgentString(userAgentString + " AndroidNoFilesharing");
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);

        webView.setFocusable(true);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setSupportMultipleWindows(true);
        webSettings.setDomStorageEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.setMediaPlaybackRequiresUserGesture(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new LCWebViewClient());
        webView.setWebChromeClient(new LCWebChromeClient());

        webView.requestFocus(View.FOCUS_DOWN);

        webView.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        if (!v.hasFocus()) {
                            v.requestFocus();
                        }
                        break;
                }
                return false;
            }
        });
        webView.addJavascriptInterface(new ChatWindowJsInterface(this), ChatWindowJsInterface.BRIDGE_OBJECT_NAME);
        adjustResizeOnGlobalLayout(webView, getActivity());
    }

    private Activity getActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private void adjustResizeOnGlobalLayout(final WebView webView, final Activity activity) {
        if (!shouldAdjustLayout(getActivity())) return;
        final View decorView = activity.getWindow().getDecorView();
        layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                final View decorView = getActivity().getWindow().getDecorView();
                final ViewGroup viewGroup = ChatWindowViewImpl.this;

                DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
                Rect rect = new Rect();
                decorView.getWindowVisibleDisplayFrame(rect);
                int paddingBottom = displayMetrics.heightPixels - rect.bottom;

                if (viewGroup.getPaddingBottom() != paddingBottom) {
                    // showing/hiding the soft keyboard
                    viewGroup.setPadding(viewGroup.getPaddingLeft(), viewGroup.getPaddingTop(), viewGroup.getPaddingRight(), paddingBottom);
                } else {
                    // soft keyboard shown/hidden and padding changed
                    if (paddingBottom != 0) {
                        // soft keyboard shown, scroll active element into view in case it is blocked by the soft keyboard
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            webView.evaluateJavascript("if (document.activeElement) { document.activeElement.scrollIntoView({behavior: \"smooth\", block: \"center\", inline: \"nearest\"}); }", null);
                        }
                    }
                }
            }
        };

        decorView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeLayoutListener();
        webView.destroy();
        super.onDetachedFromWindow();
    }

    private void removeLayoutListener() {
        if (layoutListener == null) return;
        final View decorView = getActivity().getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        } else {
            decorView.getViewTreeObserver().removeGlobalOnLayoutListener(layoutListener);
        }
    }

    private boolean shouldAdjustLayout(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return false;
        }
        final int flags = activity.getWindow().getAttributes().flags;
        final boolean isFullScreen = (flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
        return isFullScreen;
    }

    @Deprecated
    private void reload() {
        if (initialized) {
            chatUiReady = false;
            webView.reload();
        } else {
            reinitialize();
        }
    }

    private void reinitialize() {
        onlyShowProgressBar();

        initialized = false;
        initialize();
    }

    private String constructChatUrl(JSONObject jsonResponse) {
        String chatUrl = null;
        try {
            chatUrl = jsonResponse.getString("chat_url");

            chatUrl = chatUrl.replace("{%license%}", config.getParams().get(ChatWindowConfiguration.KEY_LICENCE_NUMBER));
            chatUrl = chatUrl.replace("{%group%}", config.getParams().get(ChatWindowConfiguration.KEY_GROUP_ID));
            chatUrl = chatUrl + "&native_platform=android";

            if (config.getParams().get(ChatWindowConfiguration.KEY_VISITOR_NAME) != null) {
                chatUrl = chatUrl + "&name=" + URLEncoder.encode(config.getParams().get(ChatWindowConfiguration.KEY_VISITOR_NAME), "UTF-8").replace("+", "%20");
            }

            if (config.getParams().get(ChatWindowConfiguration.KEY_VISITOR_EMAIL) != null) {
                chatUrl = chatUrl + "&email=" + URLEncoder.encode(config.getParams().get(ChatWindowConfiguration.KEY_VISITOR_EMAIL), "UTF-8");
            }

            final String customParams = escapeCustomParams(config.getParams(), chatUrl);
            if (!TextUtils.isEmpty(customParams)) {
                chatUrl = chatUrl + "&params=" + customParams;
            }

            if (!chatUrl.startsWith("http")) {
                chatUrl = "https://" + chatUrl;
            }
        } catch (JSONException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return chatUrl;
    }

    private String escapeCustomParams(Map<String, String> param, String chatUrl) {
        String params = "";
        for (String key : param.keySet()) {
            if (key.startsWith(ChatWindowConfiguration.CUSTOM_PARAM_PREFIX)) {
                final String encodedKey = Uri.encode(key.replace(ChatWindowConfiguration.CUSTOM_PARAM_PREFIX, ""));
                final String encodedValue = Uri.encode(param.get(key));

                if (!TextUtils.isEmpty(params)) {
                    params = params + "&";
                }

                params += encodedKey + "=" + encodedValue;
            }
        }
        return Uri.encode(params);
    }

    private void checkConfiguration() {
        if (config == null) {
            throw new IllegalStateException("Config must be provided before initialization");
        }
        if (initialized) {
            throw new IllegalStateException("Chat Window already initialized");
        }
    }

    protected void onHideChatWindow() {
        isErrorOccur = false;
        post(new Runnable() {
            @Override
            public void run() {
                ChatWindowViewImpl.this.setVisibility(GONE);
                hideChatWindow();
            }
        });
    }

    @Override
    public void showChatWindow() {
        ChatWindowViewImpl.this.setVisibility(VISIBLE);
        isErrorOccur = false;
        if (chatUiReady){
            onlyShowWebView();
        } else {
            reload(true);
        }

        if (eventsListener != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    eventsListener.onChatWindowVisibilityChanged(true);
                }
            });
        }
    }

    @Override
    public void hideChatWindow() {
        ChatWindowViewImpl.this.setVisibility(GONE);
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        if (eventsListener != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    eventsListener.onChatWindowVisibilityChanged(false);
                }
            });
        }
    }

    @Override
    public boolean isChatLoaded() {
        return chatUiReady;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_AUDIO_PERMISSIONS && webRequestPermissions != null) {
            String[] PERMISSIONS = {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE
            };
            webRequestPermissions.grant(PERMISSIONS);
            webRequestPermissions = null;

            return true;

        }
        return false;
    }

    @Override
    public boolean onBackPressed() {
        if (ChatWindowViewImpl.this.isShown()) {
            onHideChatWindow();
            return true;
        }
        return false;
    }

    @Override
    public boolean setConfiguration(@NonNull ChatWindowConfiguration config) {
        final boolean isEqualConfig = this.config != null && this.config.equals(config);
        this.config = config;
        return !isEqualConfig;
    }

    @Override
    public void initialize() {
        checkConfiguration();
        initialized = true;
        RequestQueue queue = Volley.newRequestQueue(getContext());
        JsonObjectRequest stringRequest = new JsonObjectRequest(Request.Method.GET, "https://cdn.livechatinc.com/app/mobile/urls.json",
                null,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d("ChatWindowView", "Response: " + response);
                        String chatUrl = constructChatUrl(response);
                        Log.d("ChatWindowView", "constructed url: " + chatUrl);
                        initialized = true;
                        if (chatUrl != null && getContext() != null) {
                            webView.loadUrl(chatUrl);
                        }
                        if (eventsListener != null) {
                            eventsListener.onWindowInitialized();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.d("ChatWindowView", "Error response: " + error);
                        initialized = false;
                    }
                });
        queue.add(stringRequest);
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_FILE_UPLOAD) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                receiveUploadedData(data);
            } else {
                resetAllUploadCallbacks();
            }
            return true;
        }
        return false;
    }

    @Override
    public void setEventsListener(ChatWindowEventsListener listener) {
        eventsListener = listener;
    }

    @Override
    public void reload(Boolean fullReload) {
        if (fullReload) {
            reinitialize();
        } else {
            chatUiReady = false;
            webView.reload();
        }
    }

    private void receiveUploadedData(Intent data) {
        if (isUriArrayUpload()) {
            receiveUploadedUriArray(data);
        } else if (isVersionPreHoneycomb()) {
            receiveUploadedUriPreHoneycomb(data);
        } else {
            receiveUploadedUri(data);
        }
    }

    private boolean isUriArrayUpload() {
        return mUriArrayUploadCallback != null;
    }

    private boolean isVersionPreHoneycomb() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB;
    }

    protected void onUiReady() {
        chatUiReady = true;
        post(new Runnable() {
            @Override
            public void run() {
                onlyShowWebView();
            }
        });
    }


    protected void onNewMessageReceived(final NewMessageModel newMessageModel) {
        if (eventsListener != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    eventsListener.onNewMessage(newMessageModel, isShown());
                }
            });
        }
    }

    class LCWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (webView != null&&chatUiReady) {
                onlyShowWebView();
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        @Override
        public void onReceivedError(final WebView view, final WebResourceRequest request, final WebResourceError error) {
            boolean isVisible = ChatWindowViewImpl.this.getVisibility() == View.VISIBLE;
            if (!isErrorOccur && isVisible){
                isErrorOccur = true;
                final boolean errorHandled = eventsListener != null && eventsListener.onError(ChatWindowErrorType.WebViewClient, error.getErrorCode(), String.valueOf(error.getDescription()));
                post(new Runnable() {
                    @Override
                    public void run() {
                        onErrorDetected(errorHandled, ChatWindowErrorType.WebViewClient, error.getErrorCode(), String.valueOf(error.getDescription()));
                    }
                });

                super.onReceivedError(view, request, error);
                onHideChatWindow();
            }
            Log.e("ChatWindow Widget", "onReceivedError: " + error.getErrorCode() + ": desc: " + error.getDescription() + " url: " + request.getUrl());
        }

        @Override
        public void onReceivedError(WebView view, final int errorCode, final String description, String failingUrl) {
            boolean isVisible = ChatWindowViewImpl.this.getVisibility() == View.VISIBLE;
            if (!isErrorOccur && isVisible){
                isErrorOccur = true;
                final boolean errorHandled = eventsListener != null && eventsListener.onError(ChatWindowErrorType.WebViewClient, errorCode, description);
                post(new Runnable() {
                    @Override
                    public void run() {
                        onErrorDetected(errorHandled, ChatWindowErrorType.WebViewClient, errorCode, description);
                    }
                });
                super.onReceivedError(view, errorCode, description, failingUrl);
                onHideChatWindow();
                Log.e("ChatWindow Widget", "onReceivedError: " + errorCode + ": desc: " + description + " url: " + failingUrl);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            final Uri uri = Uri.parse(url);
            return handleUri(view, uri);
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            final Uri uri = request.getUrl();
            return handleUri(view, uri);
        }

        private boolean handleUri(WebView webView, final Uri uri) {
            String uriString = uri.toString();
            Log.i("ChatWindowView", "handle url: " + uriString);
            boolean facebookLogin = uriString.matches("https://.+facebook.+(/dialog/oauth\\?|/login\\.php\\?|/dialog/return/arbiter\\?).+");

            if (facebookLogin) {
                return false;
            } else {
                String originalUrl = webView.getOriginalUrl();
                if (uriString.equals(originalUrl) || isSecureLivechatIncDomain(uri.getHost())) {
                    return false;
                } else {
                    if (eventsListener != null && eventsListener.handleUri(uri)) {

                    } else {
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        getContext().startActivity(intent);
                    }

                    return true;
                }
            }
        }
    }

    private void onErrorDetected(boolean errorHandled, ChatWindowErrorType errorType, int errorCode, String errorDescription) {
        isErrorOccur = true;
        if (!errorHandled){
            hideChatWindow();
        }
    }


    private static boolean isSecureLivechatIncDomain(String host) {
        return host != null && Pattern.compile("(secure-?(lc|dal|fra|)\\.(livechat|livechatinc)\\.com)").matcher(host).find();
    }

    class LCWebChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            Log.d("onCloseWindow", "called");
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> uploadMsg) {
            chooseUriToUpload(uploadMsg);
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
            chooseUriToUpload(uploadMsg);
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
            chooseUriToUpload(uploadMsg);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> uploadMsg, FileChooserParams fileChooserParams) {
            chooseUriArrayToUpload(uploadMsg);
            return true;
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            webRequestPermissions = request;
            String[] runtimePermissions = {Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.MODIFY_AUDIO_SETTINGS};
            eventsListener.onRequestAudioPermissions(runtimePermissions, REQUEST_CODE_AUDIO_PERMISSIONS);
        }

        @Override
        public boolean onConsoleMessage(final ConsoleMessage consoleMessage) {
            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
//                final boolean errorHandled = eventsListener != null && eventsListener.onError(ChatWindowErrorType.Console, -1, consoleMessage.message());
//                post(new Runnable() {
//                    @Override
//                    public void run() {
//                        onErrorDetected(errorHandled, ChatWindowErrorType.Console, -1, consoleMessage.message());
//                    }
//                });
            }
            Log.i("ChatWindowView", "onConsoleMessage" + consoleMessage.messageLevel().name() + " " + consoleMessage.message());
            return super.onConsoleMessage(consoleMessage);
        }
    }

    private void receiveUploadedUriArray(Intent data) {
        Uri[] uploadedUris;
        try {
            uploadedUris = new Uri[]{Uri.parse(data.getDataString())};
        } catch (Exception e) {
            uploadedUris = null;
        }

        mUriArrayUploadCallback.onReceiveValue(uploadedUris);
        mUriArrayUploadCallback = null;
    }

    private void receiveUploadedUriPreHoneycomb(Intent data) {
        Uri uploadedUri = data.getData();

        mUriUploadCallback.onReceiveValue(uploadedUri);
        mUriUploadCallback = null;
    }

    private void receiveUploadedUri(Intent data) {
        Uri uploadedFileUri;
        try {
            String uploadedUriFilePath = UriUtils.getFilePathFromUri(getContext(), data.getData());
            File uploadedFile = new File(uploadedUriFilePath);
            uploadedFileUri = Uri.fromFile(uploadedFile);
        } catch (Exception e) {
            uploadedFileUri = null;
        }

        mUriUploadCallback.onReceiveValue(uploadedFileUri);
        mUriUploadCallback = null;
    }

    private void resetAllUploadCallbacks() {
        resetUriUploadCallback();
        resetUriArrayUploadCallback();
    }

    private void resetUriUploadCallback() {
        if (mUriUploadCallback != null) {
            mUriUploadCallback.onReceiveValue(null);
            mUriUploadCallback = null;
        }
    }

    private void resetUriArrayUploadCallback() {
        if (mUriArrayUploadCallback != null) {
            mUriArrayUploadCallback.onReceiveValue(null);
            mUriArrayUploadCallback = null;
        }
    }

    private void chooseUriToUpload(ValueCallback<Uri> uriValueCallback) {
        resetAllUploadCallbacks();
        mUriUploadCallback = uriValueCallback;
        startFileChooserActivity();
    }

    private void chooseUriArrayToUpload(ValueCallback<Uri[]> uriArrayValueCallback) {
        resetAllUploadCallbacks();
        mUriArrayUploadCallback = uriArrayValueCallback;
        startFileChooserActivity();
    }

    private void startFileChooserActivity() {
        if (eventsListener != null) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            eventsListener.onStartFilePickerActivity(intent, REQUEST_CODE_FILE_UPLOAD);
        } else {
            Log.e("ChatWindowView", "You must provide a listener to handle file sharing");
//            Toast.makeText(getContext(), R.string.cant_share_files, Toast.LENGTH_SHORT).show();
        }
    }
}

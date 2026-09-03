package org.telegram.ui.web;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;

import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class HttpGetBitmapTask extends AsyncTask<String, Void, Bitmap> {

    // See HttpGetTask -- HttpURLConnection defaults to an infinite timeout
    // otherwise.
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private final HashMap<String, String> headers = new HashMap<>();
    private final Utilities.Callback<Bitmap> callback;
    private Exception exception;

    public HttpGetBitmapTask(Utilities.Callback<Bitmap> callback) {
        this.callback = callback;
    }

    public HttpGetBitmapTask setHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    @Override
    protected Bitmap doInBackground(String... params) {
        String urlString = params[0];

        try {
            URL url = new URL(urlString);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                urlConnection.setRequestProperty(e.getKey(), e.getValue());
            }
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);

            int statusCode = urlConnection.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                if (urlConnection.getContentType() != null && urlConnection.getContentType().contains("svg")) {
                    return SvgHelper.getBitmap(new BufferedInputStream(urlConnection.getInputStream()), 64, 64, false);
                }
                return BitmapFactory.decodeStream(new BufferedInputStream(urlConnection.getInputStream()));
            } else {
                urlConnection.disconnect();
                return null;
            }
        } catch (Exception e) {
            this.exception = e;
            return null;
        }
    }

    @Override
    protected void onPostExecute(Bitmap result) {
        if (callback != null) {
            if (exception == null) {
                callback.run(result);
            } else {
                callback.run(null);
            }
        }
    }
}

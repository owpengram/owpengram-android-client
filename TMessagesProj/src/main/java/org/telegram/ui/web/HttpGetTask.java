package org.telegram.ui.web;


import android.os.AsyncTask;

import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class HttpGetTask extends AsyncTask<String, Void, String> {

    // HttpURLConnection defaults to an infinite timeout for both connect and
    // read -- an unreachable/wrong host:port (e.g. mid-typed in Add Server)
    // would otherwise hang far longer than any UI flow should ever wait.
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private final HashMap<String, String> headers = new HashMap<>();
    private final Utilities.Callback<String> callback;
    private Exception exception;

    public HttpGetTask(Utilities.Callback<String> callback) {
        this.callback = callback;
    }

    public HttpGetTask setHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    @Override
    protected String doInBackground(String... params) {
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
            BufferedReader in;
            if (statusCode >= 200 && statusCode < 300) {
                in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            } else {
                in = new BufferedReader(new InputStreamReader(urlConnection.getErrorStream()));
            }

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            return response.toString();

        } catch (Exception e) {
            this.exception = e;
            return null;
        }
    }

    @Override
    protected void onPostExecute(String result) {
        if (callback != null) {
            if (exception == null) {
                callback.run(result);
            } else {
                callback.run(null);
            }
        }
    }
}
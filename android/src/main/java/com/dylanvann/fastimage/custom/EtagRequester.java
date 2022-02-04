package com.dylanvann.fastimage.custom;

import com.dylanvann.fastimage.custom.persistence.ObjectBox;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EtagRequester {
    /**
     * Requests etag at the server. Won't call callback on failure or if
     * etas hasn't changed.
     * @param url
     * @param callback
     */
    public static void requestEtag(final String url, final EtagCallback callback) {
        final String prevEtag = ObjectBox.getEtagByUrl(url);

        OkHttpClient client = SharedOkHttpClient.getInstance(null).getClient();
        Request.Builder request = new Request.Builder()
                .url(url);

        if (prevEtag != null) {
            request.addHeader("If-None-Match", prevEtag);
        }

        client.newCall(request.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                // we only want to report an error when we have no etag
                // and the etag request failed
                if (prevEtag == null) {
                    callback.onError("Failure when requesting etag: " + e.getMessage());
                } else {
                    callback.onEtag(prevEtag);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.code() == 200) {
                    String etag = response.header("etag");
                    callback.onEtag(etag);
                } else if (response.code() > 308) {
                    callback.onError("Unexpected http code: " + response.code());
                } else {
                    callback.onEtag(prevEtag);
                }
            }
        });
    }
}
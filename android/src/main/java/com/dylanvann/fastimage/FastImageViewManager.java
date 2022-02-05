package com.dylanvann.fastimage;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.dylanvann.fastimage.custom.EtagCallback;
import com.dylanvann.fastimage.custom.EtagRequester;
import com.dylanvann.fastimage.custom.persistence.ObjectBox;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import static com.dylanvann.fastimage.FastImageRequestListener.REACT_ON_ERROR_EVENT;
import static com.dylanvann.fastimage.FastImageRequestListener.REACT_ON_LOAD_END_EVENT;
import static com.dylanvann.fastimage.FastImageRequestListener.REACT_ON_LOAD_EVENT;

class FastImageViewManager extends SimpleViewManager<FastImageViewWithUrl> implements FastImageProgressListener {

    private static final String REACT_CLASS = "FastImageView";
    private static final String REACT_ON_LOAD_START_EVENT = "onFastImageLoadStart";
    private static final String REACT_ON_PROGRESS_EVENT = "onFastImageProgress";
    private static final Map<String, List<FastImageViewWithUrl>> VIEWS_FOR_URLS = new WeakHashMap<>();

    private static final int FORCE_REFRESH_IMAGE = 1;

    @Nullable
    private RequestManager requestManager = null;

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected FastImageViewWithUrl createViewInstance(ThemedReactContext reactContext) {
        if (isValidContextForGlide(reactContext)) {
            requestManager = Glide.with(reactContext);
        }

        return new FastImageViewWithUrl(reactContext);
    }

    @ReactProp(name = "source")
    public void setSrc(FastImageViewWithUrl view, @Nullable ReadableMap source) {
        view.source = source;

        if (source == null || !source.hasKey("uri") || isNullOrEmpty(source.getString("uri"))) {
            // Cancel existing requests.
            clearView(view);

            if (view.glideUrl != null) {
                FastImageOkHttpProgressGlideModule.forget(view.glideUrl.toStringUrl());
            }
            // Clear the image.
            view.setImageDrawable(null);
            return;
        }

        final FastImageSource imageSource = FastImageViewConverter.getImageSource(view.getContext(), source);
        if (imageSource.getUri().toString().length() == 0) {
            ThemedReactContext context = (ThemedReactContext) view.getContext();
            RCTEventEmitter eventEmitter = context.getJSModule(RCTEventEmitter.class);
            int viewId = view.getId();
            WritableMap event = new WritableNativeMap();
            event.putString("message", "Invalid source prop:" + source);
            eventEmitter.receiveEvent(viewId, REACT_ON_ERROR_EVENT, event);

            // Cancel existing requests.
            if (requestManager != null) {
                requestManager.clear(view);
            }

            if (view.glideUrl != null) {
                FastImageOkHttpProgressGlideModule.forget(view.glideUrl.toStringUrl());
            }
            // Clear the image.
            view.setImageDrawable(null);
            return;
        }

        load(view, source);
    }

    private void load(final FastImageViewWithUrl view, @NonNull final ReadableMap source) {
        //final GlideUrl glideUrl = FastImageViewConverter.getGlideUrl(view.getContext(), source);
        final FastImageSource imageSource = FastImageViewConverter.getImageSource(view.getContext(), source);
        final GlideUrl glideUrl = imageSource.getGlideUrl();

        view.glideUrl = glideUrl;

        final String key = glideUrl.toStringUrl();
        FastImageOkHttpProgressGlideModule.expect(key, this);
        List<FastImageViewWithUrl> viewsForKey = VIEWS_FOR_URLS.get(key);
        if (viewsForKey != null && !viewsForKey.contains(view)) {
            viewsForKey.add(view);
        } else if (viewsForKey == null) {
            List<FastImageViewWithUrl> newViewsForKeys = new ArrayList<>(Collections.singletonList(view));
            VIEWS_FOR_URLS.put(key, newViewsForKeys);
        }

        final ThemedReactContext context = (ThemedReactContext) view.getContext();
        final RCTEventEmitter eventEmitter = context.getJSModule(RCTEventEmitter.class);
        final int viewId = view.getId();
        eventEmitter.receiveEvent(viewId, REACT_ON_LOAD_START_EVENT, new WritableNativeMap());

        final RequestOptions options = FastImageViewConverter.getOptions(context, imageSource, source);
        if (requestManager != null) {
            final String url = imageSource.getGlideUrl().toStringUrl();
            if (!url.startsWith("http")) {
                requestManager
                        // This will make this work for remote and local images. e.g.
                        //    - file:///
                        //    - content://
                        //    - res:/
                        //    - android.resource://
                        //    - data:image/png;base64
                        .load(imageSource.getSourceForLoad())
                        .apply(options)
                        .listener(new FastImageRequestListener(key))
                        .into(view);
                return;
            }

            loadImage(view, url, options, key);
        }
    }

    /**
     * This will make a head request to the URL to get the ETAG.
     * The request is then forwarded to glide, which uses the
     * ETAG as signature, see {@link #loadImageWithSignature}.
     **/
    private void loadImage(final FastImageViewWithUrl view, final String url, @Nullable final RequestOptions options, final @Nullable String key) {
        String prevEtag = ObjectBox.getEtagByUrl(url);

        // when we have a prevEtag there will be (very likely) a cached version
        // of the image that we want to display even before sending out any req
        if (prevEtag != null && requestManager != null) {
            RequestBuilder<Drawable> builder = requestManager
                    .load(url)
                    .onlyRetrieveFromCache(true)
                    .signature(new ObjectKey(prevEtag))
                    .listener(new FastImageRequestListener(key));
            if (options != null) {
                builder = builder.apply(options);
            }
            builder.into(view);
        }

        // We need to make a head request to the URL with the ETAG attached.
        // - When we get a new etag Glide will send out another request (as signature has changed)
        // - If the signature (etag) didn't change, Glide won't bother sending out a request
        EtagRequester.requestEtag(url, prevEtag, new EtagCallback() {
            @Override
            public void onEtag(String etag) {
                // Note: here at this point the etag in the ObjectBox has been updated
                // to the new etag. That's why we pass down the the previous reference.
                loadImageWithSignature(view, url, etag, prevEtag, options, key);
            }

            @Override
            public void onError(String error) {
                loadImageWithSignature(view, url, prevEtag, prevEtag, options, key);
            }
        });
    }

    /**
     * This loads the actual image either from server or from cache
     * depending on whether a cache entry for the given signature
     * exists yet.
     * If a prev signature is passed it will show the image for the
     * url + prevSignature as long as the new image from the url (with
     * the new signature) is being loaded.
     */
    private void loadImageWithSignature(
            final FastImageViewWithUrl view,
            final String url,
            @Nullable String signature,
            @Nullable String prevSignature,
            @Nullable final RequestOptions options,
            @Nullable final String key)
    {
        getActivityFromContext(view.getContext()).runOnUiThread(() -> {
            if (requestManager == null) {
                Log.e(FastImageViewManager.class.getSimpleName(), "Can't refresh as requestManager was null!");
                return;
            }

            // cancel any previous requests
            requestManager.clear(view);

            // Request the new image
            RequestBuilder<Drawable> imageRequest = requestManager
                    .load(url);

            // When we have a previous signature we want to show the previous
            // image, until the new one is loaded. This is done with a
            // thumbnail request. Without this there would be a "white flickering"
            // until the (new) image is loaded.
            if (prevSignature != null) {
                // Create a "thumbnail" which is literally the cached image while we load the new image
                RequestBuilder<Drawable> thumbnailRequest = requestManager
                        .load(url)
                        .onlyRetrieveFromCache(true);
                thumbnailRequest = thumbnailRequest.signature(new ObjectKey(prevSignature));
                imageRequest = imageRequest
                        .thumbnail(thumbnailRequest);
            }
            // important: otherwise it may take the memory cache image for the
            // same url and save that as new result for the new signature
            imageRequest = imageRequest.skipMemoryCache(true);

            if (signature != null) {
                imageRequest = imageRequest.signature(new ObjectKey(signature));
            }
            if (options != null) {
                imageRequest = imageRequest.apply(options);
            }
            if (key != null) {
                imageRequest = imageRequest.listener(new FastImageRequestListener(key));
            }

            // finally, load the image
            imageRequest.into(view);
        });
    }

    private void refresh(final FastImageViewWithUrl view, final ReadableMap source) {
        load(view, source);
    }

    @ReactProp(name = "tintColor", customType = "Color")
    public void setTintColor(FastImageViewWithUrl view, @Nullable Integer color) {
        if (color == null) {
            view.clearColorFilter();
        } else {
            view.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    @ReactProp(name = "resizeMode")
    public void setResizeMode(FastImageViewWithUrl view, String resizeMode) {
        final FastImageViewWithUrl.ScaleType scaleType = FastImageViewConverter.getScaleType(resizeMode);
        view.setScaleType(scaleType);
    }

    @Override
    public void onDropViewInstance(@NonNull FastImageViewWithUrl view) {
        // This will cancel existing requests.
        clearView(view);

        if (view.glideUrl != null) {
            final String key = view.glideUrl.toString();
            FastImageOkHttpProgressGlideModule.forget(key);
            List<FastImageViewWithUrl> viewsForKey = VIEWS_FOR_URLS.get(key);
            if (viewsForKey != null) {
                viewsForKey.remove(view);
                if (viewsForKey.size() == 0) VIEWS_FOR_URLS.remove(key);
            }
        }

        super.onDropViewInstance(view);
    }

    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put(REACT_ON_LOAD_START_EVENT, MapBuilder.of("registrationName", REACT_ON_LOAD_START_EVENT))
                .put(REACT_ON_PROGRESS_EVENT, MapBuilder.of("registrationName", REACT_ON_PROGRESS_EVENT))
                .put(REACT_ON_LOAD_EVENT, MapBuilder.of("registrationName", REACT_ON_LOAD_EVENT))
                .put(REACT_ON_ERROR_EVENT, MapBuilder.of("registrationName", REACT_ON_ERROR_EVENT))
                .put(REACT_ON_LOAD_END_EVENT, MapBuilder.of("registrationName", REACT_ON_LOAD_END_EVENT))
                .build();
    }

    @Override
    public void onProgress(String key, long bytesRead, long expectedLength) {
        List<FastImageViewWithUrl> viewsForKey = VIEWS_FOR_URLS.get(key);
        if (viewsForKey != null) {
            for (FastImageViewWithUrl view : viewsForKey) {
                WritableMap event = new WritableNativeMap();
                event.putInt("loaded", (int) bytesRead);
                event.putInt("total", (int) expectedLength);
                ThemedReactContext context = (ThemedReactContext) view.getContext();
                RCTEventEmitter eventEmitter = context.getJSModule(RCTEventEmitter.class);
                int viewId = view.getId();
                eventEmitter.receiveEvent(viewId, REACT_ON_PROGRESS_EVENT, event);
            }
        }
    }

    @Override
    public float getGranularityPercentage() {
        return 0.5f;
    }

    private boolean isNullOrEmpty(final String url) {
        return url == null || url.trim().isEmpty();
    }


    private static boolean isValidContextForGlide(final Context context) {
        Activity activity = getActivityFromContext(context);

        if (activity == null) {
            return false;
        }

        return !isActivityDestroyed(activity);
    }

    private static Activity getActivityFromContext(final Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        }

        if (context instanceof ThemedReactContext) {
            final Context baseContext = ((ThemedReactContext) context).getBaseContext();
            if (baseContext instanceof Activity) {
                return (Activity) baseContext;
            }

            if (baseContext instanceof ContextWrapper) {
                final ContextWrapper contextWrapper = (ContextWrapper) baseContext;
                final Context wrapperBaseContext = contextWrapper.getBaseContext();
                if (wrapperBaseContext instanceof Activity) {
                    return (Activity) wrapperBaseContext;
                }
            }
        }

        return null;
    }

    private static boolean isActivityDestroyed(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return activity.isDestroyed() || activity.isFinishing();
        } else {
            return activity.isDestroyed() || activity.isFinishing() || activity.isChangingConfigurations();
        }
    }

    @androidx.annotation.Nullable
    @Override
    public Map<String, Integer> getCommandsMap() {
        return new HashMap<String, Integer>() {
            {
                put("forceRefreshImage", FORCE_REFRESH_IMAGE);
            }
        };
    }

    @Override
    public void receiveCommand(FastImageViewWithUrl root, int commandId, @Nullable ReadableArray args) {
        switch (commandId) {
            case FORCE_REFRESH_IMAGE: {
                if (root.source != null) {
                    refresh(root, root.source);
                }
                return;
            }
            default:
                throw new IllegalArgumentException(String.format(
                        "Unsupported command %s received by %s.",
                        commandId,
                        root.getClass().getSimpleName()));
        }
    }

    private void clearView(FastImageViewWithUrl view) {
        if (requestManager != null && view != null && view.getTag() != null && view.getTag() instanceof Request) {
            requestManager.clear(view);
        }
    }
}

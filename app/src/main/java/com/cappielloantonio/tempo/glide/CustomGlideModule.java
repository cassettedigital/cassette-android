package com.cappielloantonio.tempo.glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;
import com.cappielloantonio.tempo.util.CassetteHubTrust;
import com.cappielloantonio.tempo.util.Preferences;

import java.io.InputStream;

@GlideModule
public class CustomGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(@NonNull Context context, GlideBuilder builder) {
        int diskCacheSize = Preferences.getImageCacheSize() * 1024 * 1024;
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, "cache", diskCacheSize));
        builder.setDefaultRequestOptions(new RequestOptions().format(DecodeFormat.PREFER_RGB_565));
    }

    /** cassette (security design rule 2): covers from the computer through the door that checks its key. */
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(CassetteHubTrust.callFactory(CassetteHubTrust.getMediaClient())));
    }
}
